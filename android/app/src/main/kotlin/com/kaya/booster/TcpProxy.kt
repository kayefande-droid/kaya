package com.kaya.booster

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * On-device TCP proxy for the VPN tunnel.
 *
 * Terminates the game's TCP connections at the tunnel (SYN-ACK from Kaya),
 * opens the real connection from a protected upstream socket, and pipes bytes
 * both ways with sequence-number tracking. Deliberately minimal but honest:
 * reliable enough for login / store / matchmaker HTTP, while competitive game
 * traffic itself rides the UDP fast path.
 *
 * v1.1.7 fail-open hardening:
 *  - Client->upstream data flows through a per-flow queue drained by a
 *     background pump. The tun-read thread only enqueues — a slow or stalled
 *     upstream can never freeze the whole tunnel (this used to be the "VPN
 *     broke my internet" bug #2).
 *  - Backpressure is real TCP: when the queue is full we simply don't ACK,
 *     so the client retransmits later — no data loss, no unbounded memory.
 *  - MSS is clamped to the tunnel MTU (1280 - 40 headers = 1240). The old
 *     1400 produced DF-set segments larger than the tunnel could carry,
 *     silently blackholing large downloads.
 *  - All tunnel writes go through [attachWriter] (the live fd), so flows
 *     survive a watchdog interface rebuild — their protected upstream
 *     sockets are unaffected by the fd swap and keep streaming.
 */
object TcpProxy {

    /** Live tunnel writer, injected by KayaVpnService. */
    @Volatile private var tunWriter: ((ByteArray) -> Unit)? = null

    fun attachWriter(w: ((ByteArray) -> Unit)?) {
        tunWriter = w
    }

    private const val WINDOW = 65535

    /** 1280 (tunnel MTU) - 20 (IP) - 20 (TCP): largest payload we can send. */
    private const val MSS = 1240

    /** Per-flow client->upstream queue: ~256 x 1400 B ≈ 360 KB max. */
    private const val QUEUE_CAP = 256

    private const val FIN = 0 // sentinel: empty payload marks client FIN

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
        @Volatile var closed = false
        val outgoing = ArrayBlockingQueue<ByteArray>(QUEUE_CAP)
    }

    private val flows = ConcurrentHashMap<String, Flow>()
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "kaya-tcp").apply { isDaemon = true }
    }
    private val connections = AtomicLong(0)

    fun handle(
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
            sendSegment(flow, flow.ourIsn, seq + 1, PacketEngine.FLAG_SYN or PacketEngine.FLAG_ACK, ByteArray(0), withMss = true)
            flow.rcvNxt = seq + 1
            pool.execute {
                val sock = Socket()
                try {
                    protect(sock)
                    sock.tcpNoDelay = true
                    sock.connect(InetSocketAddress(InetAddress.getByAddress(dstIp), dstPort), 5000)
                    flow.upstream = sock
                    connections.incrementAndGet()
                    // Client -> upstream pump (background thread; pending
                    // data already sits in the queue, so no special drain).
                    pool.execute { pumpClientToUpstream(flow, sock) }
                    // Upstream -> client pump (this thread).
                    pumpUpstream(flow, sock, key)
                } catch (t: Throwable) {
                    sendReset(flow.srcIp, flow.srcPort, flow.dstIp, flow.dstPort, flow.sndNxt, flow.rcvNxt)
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
                // Queue-full = no ACK = client retransmits: real backpressure
                // instead of buffering without bound on the read thread.
                if (flow.outgoing.offer(payload)) {
                    flow.rcvNxt = seq + payload.size
                } else {
                    sendSegment(flow, flow.sndNxt, flow.rcvNxt, PacketEngine.FLAG_ACK, ByteArray(0))
                    return
                }
            } else if (seq + payload.size <= flow.rcvNxt) {
                // retransmission; just re-ack below
            } else {
                // out-of-order beyond window: drop, dup-ack triggers retransmit
            }
            sendSegment(flow, flow.sndNxt, flow.rcvNxt, PacketEngine.FLAG_ACK, ByteArray(0))
        } else if (ackFlag && !syn) {
            // pure ACK — nothing to do (we do not retransmit; TUN is reliable)
        }

        if (fin) {
            flow.rcvNxt = seq + payload.size + 1
            sendSegment(flow, flow.sndNxt, flow.rcvNxt, PacketEngine.FLAG_ACK, ByteArray(0))
            // Tell the client->upstream pump: no more client data coming.
            flow.outgoing.offer(ByteArray(0))
        }
    }

    /** Client -> upstream. Runs on a pool thread; may block freely. */
    private fun pumpClientToUpstream(flow: Flow, sock: Socket) {
        try {
            val out = sock.getOutputStream()
            while (!flow.closed) {
                val chunk = flow.outgoing.poll(500, TimeUnit.MILLISECONDS) ?: continue
                if (chunk.isEmpty()) { // FIN sentinel
                    runCatching { sock.shutdownOutput() }
                    break
                }
                out.write(chunk)
                out.flush()
            }
        } catch (_: Throwable) {
            // upstream gone: the other pump notices via read failure and
            // closes the flow; nothing to do on the read thread.
        }
    }

    /** Upstream -> client. Runs on a pool thread; may block freely. */
    private fun pumpUpstream(flow: Flow, sock: Socket, key: String) {
        try {
            val input = sock.getInputStream()
            val buf = ByteArray(MSS)
            while (!flow.closed) {
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) continue
                sendSegment(flow, flow.sndNxt, flow.rcvNxt, PacketEngine.FLAG_PSH or PacketEngine.FLAG_ACK, buf.copyOf(n))
                flow.sndNxt += n
            }
            sendSegment(flow, flow.sndNxt, flow.rcvNxt, PacketEngine.FLAG_FIN or PacketEngine.FLAG_ACK, ByteArray(0))
            flow.sndNxt += 1
        } catch (_: Throwable) {
        } finally {
            closeFlow(flow, key)
        }
    }

    private fun sendSegment(
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
            // MSS option: kind 2, len 4, value MSS (1240, fits the 1280 MTU)
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
        runCatching { tunWriter?.invoke(out) }
    }

    fun sendReset(
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
        runCatching { tunWriter?.invoke(out) }
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

    val activeConnections: Int get() = connections.get().toInt()
}
