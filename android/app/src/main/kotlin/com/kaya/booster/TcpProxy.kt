package com.kaya.booster

import android.os.Handler
import android.os.Looper
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * On-device TCP proxy for the VPN tunnel.
 *
 * Terminates the game's TCP connections at the tunnel (SYN-ACK from Kaya),
 * opens the real connection from a protected upstream socket, and pipes bytes
 * both ways with sequence-number tracking. Deliberately minimal but honest:
 * reliable enough for login / store / matchmaker HTTP, while competitive game
 * traffic itself rides the UDP fast path.
 */
object TcpProxy {

    private const val WINDOW = 65535
    private const val MSS = 1400

    private class Flow(
        val srcIp: ByteArray,
        val srcPort: Int,
        val dstIp: ByteArray,
        val dstPort: Int,
        clientSeq: Long,
    ) {
        val ourIsn: Long = (0x1_0000L..0x7FFF_FFFFL).random()
        var sndNxt: Long = ourIsn + 1
        var rcvNxt: Long = clientSeq + 1
        var upstream: Socket? = null
        var connected = false
        var closed = false
        val pending = java.util.ArrayDeque<ByteArray>()
        val lock = Object()
    }

    private val flows = ConcurrentHashMap<String, Flow>()
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "kaya-tcp").apply { isDaemon = true }
    }
    private val connections = AtomicLong(0)

    fun handle(
        tunOut: FileOutputStream,
        srcIp: ByteArray,
        srcPort: Int,
        dstIp: ByteArray,
        dstPort: Int,
        seq: Long,
        ack: Long,
        flags: Int,
        payload: ByteArray,
        protect: (Socket) -> Boolean,
    ) {
        val key = "${PacketEngine.ipToString(srcIp)}:$srcPort>${PacketEngine.ipToString(dstIp)}:$dstPort"
        val syn = flags and PacketEngine.FLAG_SYN != 0
        val ackFlag = flags and PacketEngine.FLAG_ACK != 0
        val fin = flags and PacketEngine.FLAG_FIN != 0
        val rst = flags and PacketEngine.FLAG_RST != 0

        var flow = flows[key]
        if (flow == null) {
            if (!syn) return // no context; drop
            flow = Flow(srcIp, srcPort, dstIp, dstPort, seq)
            flows[key] = flow
            // SYN-ACK with MSS option (24-byte header)
            sendSegment(tunOut, flow, flow.ourIsn, seq + 1, PacketEngine.FLAG_SYN or PacketEngine.FLAG_ACK, ByteArray(0), withMss = true)
            flow.rcvNxt = seq + 1
            pool.execute {
                val sock = Socket()
                try {
                    protect(sock)
                    sock.tcpNoDelay = true
                    sock.connect(InetSocketAddress(InetAddress.getByAddress(dstIp), dstPort), 5000)
                    flow.upstream = sock
                    synchronized(flow.lock) {
                        flow.connected = true
                        while (flow.pending.isNotEmpty()) {
                            val chunk = flow.pending.poll()
                            sock.getOutputStream().write(chunk)
                        }
                        sock.getOutputStream().flush()
                    }
                    connections.incrementAndGet()
                    pumpUpstream(tunOut, flow, key)
                } catch (t: Throwable) {
                    sendReset(tunOut, flow.srcIp, flow.srcPort, flow.dstIp, flow.dstPort, flow.sndNxt, flow.rcvNxt)
                    closeFlow(flow, key)
                }
            }
            return
        }

        if (rst) {
            closeFlow(flow, key)
            return
        }

        if (payload.isNotEmpty()) {
            val expected = flow.rcvNxt
            if (seq == expected) {
                flow.rcvNxt = seq + payload.size
                val sock = flow.upstream
                val connected = flow.connected
                if (connected && sock != null) {
                    try {
                        synchronized(flow.lock) {
                            sock.getOutputStream().write(payload)
                            sock.getOutputStream().flush()
                        }
                    } catch (t: Throwable) {
                        sendReset(tunOut, flow.srcIp, flow.srcPort, flow.dstIp, flow.dstPort, flow.sndNxt, flow.rcvNxt)
                        closeFlow(flow, key)
                        return
                    }
                } else {
                    synchronized(flow.lock) { flow.pending.add(payload) }
                }
            } else if (seq + payload.size <= flow.rcvNxt) {
                // retransmission; just re-ack below
            } else {
                // out-of-order beyond window: drop, dup-ack triggers retransmit
            }
            sendSegment(tunOut, flow, flow.sndNxt, flow.rcvNxt, PacketEngine.FLAG_ACK, ByteArray(0))
        } else if (ackFlag && !syn) {
            // pure ACK — nothing to do (we do not retransmit; TUN is reliable)
        }

        if (fin) {
            flow.rcvNxt = seq + payload.size + 1
            sendSegment(tunOut, flow, flow.sndNxt, flow.rcvNxt, PacketEngine.FLAG_ACK, ByteArray(0))
            val sock = flow.upstream
            try {
                sock?.shutdownOutput()
            } catch (_: Throwable) {
            }
        }
    }

    private fun pumpUpstream(tunOut: FileOutputStream, flow: Flow, key: String) {
        val sock = flow.upstream ?: return
        try {
            val input = sock.getInputStream()
            val buf = ByteArray(MSS)
            while (!flow.closed) {
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) continue
                sendSegment(tunOut, flow, flow.sndNxt, flow.rcvNxt, PacketEngine.FLAG_PSH or PacketEngine.FLAG_ACK, buf.copyOf(n))
                flow.sndNxt += n
            }
            sendSegment(tunOut, flow, flow.sndNxt, flow.rcvNxt, PacketEngine.FLAG_FIN or PacketEngine.FLAG_ACK, ByteArray(0))
            flow.sndNxt += 1
        } catch (_: Throwable) {
        } finally {
            closeFlow(flow, key)
        }
    }

    private fun sendSegment(
        tunOut: FileOutputStream,
        flow: Flow,
        seq: Long,
        ack: Long,
        flags: Int,
        payload: ByteArray,
        withMss: Boolean = false,
    ) {
        val tcp = PacketEngine.buildTcpHeader(flow.dstPort, flow.srcPort, seq, ack, flags, WINDOW, payload.size)
        val full = if (withMss) {
            // 24-byte header: inject MSS option
            val withOpt = ByteArray(24 + payload.size)
            System.arraycopy(tcp, 0, withOpt, 0, 20)
            // data offset 6 (24 bytes)
            withOpt[12] = 0x60
            withOpt[13] = tcp[13]
            System.arraycopy(tcp, 14, withOpt, 14, 6)
            // MSS option: kind 2, len 4, value 1400
            withOpt[20] = 2
            withOpt[21] = 4
            withOpt[22] = ((MSS shr 8) and 0xFF).toByte()
            withOpt[23] = (MSS and 0xFF).toByte()
            System.arraycopy(payload, 0, withOpt, 24, payload.size)
            withOpt
        } else {
            val plain = ByteArray(tcp.size + payload.size)
            System.arraycopy(tcp, 0, plain, 0, tcp.size)
            System.arraycopy(payload, 0, plain, tcp.size, payload.size)
            plain
        }
        val ip = PacketEngine.buildIpv4Header(
            src = flow.dstIp,
            dst = flow.srcIp,
            protocol = 6,
            payloadLen = full.size,
            identification = 0,
        )
        val checksummed = PacketEngine.withTcpChecksum(flow.dstIp, flow.srcIp, full)
        val out = ByteArray(ip.size + checksummed.size)
        System.arraycopy(ip, 0, out, 0, ip.size)
        System.arraycopy(checksummed, 0, out, ip.size, checksummed.size)
        try {
            tunOut.write(out)
            tunOut.flush()
        } catch (_: Throwable) {
        }
    }

    fun sendReset(
        tunOut: FileOutputStream,
        srcIp: ByteArray,
        srcPort: Int,
        dstIp: ByteArray,
        dstPort: Int,
        seq: Long,
        ack: Long,
    ) {
        val tcp = PacketEngine.buildTcpHeader(srcPort, dstPort, seq, ack, PacketEngine.FLAG_RST or PacketEngine.FLAG_ACK, 0, 0)
        val checksummed = PacketEngine.withTcpChecksum(srcIp, dstIp, tcp)
        val ip = PacketEngine.buildIpv4Header(srcIp, dstIp, 6, checksummed.size, 0)
        val out = ByteArray(ip.size + checksummed.size)
        System.arraycopy(ip, 0, out, 0, ip.size)
        System.arraycopy(checksummed, 0, out, ip.size, checksummed.size)
        runCatching {
            tunOut.write(out)
            tunOut.flush()
        }
    }

    private fun closeFlow(flow: Flow, key: String) {
        flow.closed = true
        flows.remove(key)
        runCatching { flow.upstream?.close() }
    }

    fun shutdown() {
        flows.values.forEach { runCatching { it.upstream?.close() } }
        flows.clear()
        pool.shutdownNow()
    }

    val activeConnections: Int get() = connections.get()
}
