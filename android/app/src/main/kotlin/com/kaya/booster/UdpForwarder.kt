package com.kaya.booster

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap

/**
 * UDP forwarder: relays game packets arriving from the VPN tunnel to the real
 * destination and routes replies back into the tunnel. Kaya ships with no
 * servers; traffic goes straight to the destination the game (or DNS steering)
 * selected. Upstream sockets are protected so they bypass our own tunnel.
 */
object UdpForwarder {

    private const val HEADER_SIZE = 13
    private val sockets = ConcurrentHashMap<Long, DatagramSocket>()
    @Volatile private var running = false
    @Volatile private var boundPortNo = -1
    private var serverSocket: DatagramSocket? = null
    private var thread: Thread? = null
    private var protectFn: ((DatagramSocket) -> Boolean)? = null
    private var replyPort = 51976

    /** Actual local relay port after a successful [start] (fallbacks included). */
    fun boundPort(): Int = boundPortNo

    @Synchronized
    fun start(listenPort: Int, replyToPort: Int, protect: (DatagramSocket) -> Boolean) {
        if (running) return
        protectFn = protect
        replyPort = replyToPort
        val srv = DatagramSocket(null)
        srv.reuseAddress = true
        srv.bind(InetSocketAddress("127.0.0.1", listenPort))
        serverSocket = srv
        boundPortNo = srv.localPort
        running = true
        thread = Thread({
            val buf = ByteArray(65535)
            val pkt = DatagramPacket(buf, buf.size)
            while (running) {
                try {
                    srv.receive(pkt)
                    handlePacket(buf, pkt.length)
                    pkt.length = buf.size
                } catch (e: SocketTimeoutException) {
                    // idle
                } catch (t: Throwable) {
                    if (running) KayaEventHub.emit("udp_forwarder", mapOf("error" to (t.message ?: "io")))
                }
            }
        }, "kaya-udp-forwarder").apply { start() }
    }

    /**
     * Tunnel packet: [0] version, [1..4] dst ip, [5..6] dst port,
     * [7..10] src ip, [11..12] src port, then payload.
     */
    private fun handlePacket(data: ByteArray, len: Int) {
        if (len <= HEADER_SIZE) return
        val dstAddr = data.copyOfRange(1, 5)
        val dstPort = ((data[5].toInt() and 0xFF) shl 8) or (data[6].toInt() and 0xFF)
        val srcAddr = data.copyOfRange(7, 11)
        val srcPort = ((data[11].toInt() and 0xFF) shl 8) or (data[12].toInt() and 0xFF)
        val payloadLen = len - HEADER_SIZE
        val key = keyFor(srcAddr, srcPort, dstAddr, dstPort)

        var sock = sockets[key]
        if (sock == null || sock.isClosed) {
            sock = DatagramSocket(null)
            sock.reuseAddress = true
            sock.bind(InetSocketAddress(0)) // wildcard: real source IP on send
            sock.soTimeout = 120_000
            protectFn?.invoke(sock)
            // QoS: mark game packets DSCP EF (Expedited Forwarding). On WMM
            // Wi-Fi these frames ride the AC_VO queue — the same priority
            // class as VoIP — so a saturated home network degrades a download
            // long before it degrades the match. Devices that ignore the
            // marker lose nothing; there is no downside path.
            runCatching { sock.trafficClass = 0xB8 }
            sockets[key] = sock
            spawnReplyPump(sock, srcAddr, srcPort, dstAddr, dstPort, key)
        }

        try {
            val payload = data.copyOfRange(HEADER_SIZE, len)
            sock.send(DatagramPacket(payload, payloadLen, InetAddress.getByAddress(dstAddr), dstPort))
        } catch (t: Throwable) {
            KayaEventHub.emit("udp_forwarder", mapOf("error" to (t.message ?: "send")))
        }
    }

    /**
     * Reply back into the tunnel: [0] version, [1..4] src ip (original dst),
     * [5..6] src port, [7..10] dst ip (tunnel client), [11..12] dst port.
     */
    private fun spawnReplyPump(
        sock: DatagramSocket,
        srcAddr: ByteArray,
        srcPort: Int,
        dstAddr: ByteArray,
        dstPort: Int,
        key: Long,
    ) {
        Thread({
            val buf = ByteArray(65535)
            val reply = DatagramPacket(buf, buf.size)
            try {
                while (running && !sock.isClosed) {
                    try {
                        sock.receive(reply)
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                    val n = reply.length
                    val out = ByteArray(HEADER_SIZE + n)
                    out[0] = 1
                    System.arraycopy(reply.address.address, 0, out, 1, 4)
                    out[5] = ((reply.port shr 8) and 0xFF).toByte()
                    out[6] = (reply.port and 0xFF).toByte()
                    System.arraycopy(srcAddr, 0, out, 7, 4)
                    out[11] = ((srcPort shr 8) and 0xFF).toByte()
                    out[12] = (srcPort and 0xFF).toByte()
                    System.arraycopy(buf, 0, out, HEADER_SIZE, n)
                    serverSocket?.send(DatagramPacket(out, out.size, InetAddress.getByName("127.0.0.1"), replyPort))
                }
            } catch (_: Throwable) {
            } finally {
                sockets.remove(key)
                runCatching { sock.close() }
            }
        }, "kaya-udp-reply").start()
    }

    private fun keyFor(src: ByteArray, srcPort: Int, dst: ByteArray, dstPort: Int): Long {
        var k = -3750763034362895579L // FNV-1a 64-bit offset basis (written to avoid literal overflow)
        for (b in src + dst) k = (k xor (b.toInt() and 0xFF).toLong()) * 0x100000001b3L
        return k xor (srcPort.toLong() shl 20) xor dstPort.toLong()
    }

    @Synchronized
    fun stop() {
        running = false
        boundPortNo = -1
        runCatching { serverSocket?.close() }
        sockets.values.forEach { runCatching { it.close() } }
        sockets.clear()
    }
}
