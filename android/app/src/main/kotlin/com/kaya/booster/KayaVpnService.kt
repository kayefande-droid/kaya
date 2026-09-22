package com.kaya.booster

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Kaya's on-device fast lane (VpnService).
 *
 * A TUN device captures:
 *  - DNS (port 53, UDP + TCP): answered on-device by [DnsRacer], which races
 *    the public Anycast resolvers (Cloudflare / Google / Quad9) and replies
 *    from the fastest edge. This is the "smart anycast" trick — Kaya runs no
 *    servers of its own, it simply picks which public resolver answers.
 *  - UDP game traffic: relayed via the loopback [UdpForwarder] to the exact
 *    destination the game (or the DNS answer) selected.
 *  - TCP traffic (store, login, matchmaker HTTP): terminated by the on-device
 *    [TcpProxy] and re-served from a protected upstream socket.
 *  - ICMP echo: answered on-device so in-game ping displays keep working.
 *
 * Everything runs 100% locally. Zero Kaya infrastructure.
 */
class KayaVpnService : VpnService() {

    private val running = AtomicBoolean(false)
    private var tun: ParcelFileDescriptor? = null
    private var tunOut: FileOutputStream? = null
    private var replySocket: DatagramSocket? = null
    private val nextTid = AtomicInteger(0x4B59) // "KY"

    private val tunAddr = "198.18.0.2"
    private val tunRouter = "198.18.0.1"
    private val fwdUdpPort = 51975
    private val replyUdpPort = 51976

    override fun onCreate() {
        super.onCreate()
        KayaVpnServiceHolder.vpn = this
        UdpForwarder.start(fwdUdpPort) { protectDatagram(it) }
        KayaEventHub.emit("vpn", mapOf("state" to "created"))
    }

    override fun onDestroy() {
        running.set(false)
        runCatching { replySocket?.close() }
        runCatching { tunOut?.close() }
        runCatching { tun?.close() }
        UdpForwarder.stop()
        TcpProxy.shutdown()
        KayaVpnServiceHolder.vpn = null
        KayaEventHub.emit("vpn", mapOf("state" to "stopped"))
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> if (running.get().not()) establish()
        }
        return START_STICKY
    }

    override fun onRevoke() {
        stopSelf()
    }

    fun protectDatagram(sock: DatagramSocket): Boolean =
        runCatching { protect(sock) }.getOrDefault(false)

    fun protectStream(sock: Socket): Boolean =
        runCatching { protect(sock) }.getOrDefault(false)

    private fun establish(): Boolean {
        val builder = Builder()
            .setSession("Kaya Fast Lane")
            .setMtu(1500)
            .addAddress(tunAddr, 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(tunRouter)
            .setBlocking(true)

        val fd = try {
            builder.establish() ?: return false
        } catch (t: Throwable) {
            KayaEventHub.emit("vpn", mapOf("state" to "error", "message" to (t.message ?: "establish")))
            return false
        }

        startForeground(
            NOTIF_ID,
            buildNotification(getString(R.string.notif_vpn_title), getString(R.string.notif_vpn_text)),
        )

        tun = fd
        tunOut = FileOutputStream(fd.fileDescriptor)
        running.set(true)

        // Loopback socket that the UDP forwarder pushes replies into.
        val reply = DatagramSocket(null)
        reply.reuseAddress = true
        reply.bind(InetSocketAddress("127.0.0.1", replyUdpPort))
        reply.soTimeout = 500
        replySocket = reply
        Thread({
            val buf = ByteArray(65535)
            val pkt = DatagramPacket(buf, buf.size)
            while (running.get()) {
                try {
                    reply.receive(pkt)
                    handleForwarderReply(buf, pkt.length)
                    pkt.length = buf.size
                } catch (e: java.net.SocketTimeoutException) {
                    // idle
                } catch (t: Throwable) {
                    if (running.get()) KayaEventHub.emit("vpn", mapOf("state" to "reply_io"))
                }
            }
        }, "kaya-reply").start()

        Thread({
            val input = FileInputStream(fd.fileDescriptor)
            val packet = ByteArray(32767)
            while (running.get()) {
                try {
                    val len = input.read(packet)
                    if (len < 20) continue
                    processPacket(packet, len)
                } catch (t: Throwable) {
                    if (running.get()) KayaEventHub.emit("vpn", mapOf("state" to "tun_io"))
                }
            }
        }, "kaya-tun-read").start()

        KayaEventHub.emit("vpn", mapOf("state" to "active"))
        return true
    }

    private fun processPacket(packet: ByteArray, len: Int) {
        val proto = PacketEngine.ipv4Protocol(packet)
        val ipHeaderLen = PacketEngine.ipv4HeaderLength(packet)
        if (ipHeaderLen < 20 || len < ipHeaderLen) return
        val srcIp = PacketEngine.ipv4Source(packet)
        val dstIp = PacketEngine.ipv4Destination(packet)
        when (proto) {
            17 -> processUdp(packet, len, ipHeaderLen, srcIp, dstIp)
            6 -> processTcp(packet, len, ipHeaderLen, srcIp, dstIp)
            1 -> processIcmp(packet, len, ipHeaderLen, srcIp, dstIp)
        }
    }

    // ------------------------------------------------------------- UDP / DNS

    private fun processUdp(packet: ByteArray, len: Int, ipHeaderLen: Int, srcIp: ByteArray, dstIp: ByteArray) {
        val udpLen = PacketEngine.udpLength(packet, ipHeaderLen)
        if (udpLen < 8 || ipHeaderLen + udpLen > len) return
        val srcPort = PacketEngine.udpSourcePort(packet, ipHeaderLen)
        val dstPort = PacketEngine.udpDestPort(packet, ipHeaderLen)
        val payloadLen = udpLen - 8
        val off = ipHeaderLen + 8
        val payload = packet.copyOfRange(off, off + payloadLen)

        if (dstPort == 53) {
            DnsResponder.answer(srcIp, srcPort, payload) { out -> writeTun(out) }
            return
        }

        // Relay game/app UDP through the loopback forwarder.
        val out = ByteArray(13 + payloadLen)
        out[0] = 1
        System.arraycopy(dstIp, 0, out, 1, 4)
        out[5] = ((dstPort shr 8) and 0xFF).toByte()
        out[6] = (dstPort and 0xFF).toByte()
        System.arraycopy(srcIp, 0, out, 7, 4)
        out[11] = ((srcPort shr 8) and 0xFF).toByte()
        out[12] = (srcPort and 0xFF).toByte()
        System.arraycopy(payload, 0, out, 13, payloadLen)
        runCatching {
            val sock = DatagramSocket(null)
            sock.bind(InetSocketAddress("127.0.0.1", 0))
            sock.send(DatagramPacket(out, out.size, InetAddress.getByName("127.0.0.1"), fwdUdpPort))
            sock.close()
        }
    }

    /**
     * Forwarder reply: [0] version, [1..4] src ip (= original destination),
     * [5..6] src port, [7..10] dst ip (= tunnel client), [11..12] dst port.
     */
    private fun handleForwarderReply(buf: ByteArray, len: Int) {
        if (len < 14) return
        val srcIp = buf.copyOfRange(1, 5)
        val srcPort = ((buf[5].toInt() and 0xFF) shl 8) or (buf[6].toInt() and 0xFF)
        val dstIp = buf.copyOfRange(7, 11)
        val dstPort = ((buf[11].toInt() and 0xFF) shl 8) or (buf[12].toInt() and 0xFF)
        val payloadLen = len - 13
        val udp = PacketEngine.buildUdpHeader(srcPort, dstPort, payloadLen)
        val ip = PacketEngine.buildIpv4Header(
            src = srcIp,
            dst = dstIp,
            protocol = 17,
            payloadLen = udp.size + payloadLen,
            identification = nextTid.incrementAndGet().toShort(),
        )
        val out = ByteArray(ip.size + udp.size + payloadLen)
        System.arraycopy(ip, 0, out, 0, ip.size)
        System.arraycopy(udp, 0, out, ip.size, udp.size)
        System.arraycopy(buf, 13, out, ip.size + udp.size, payloadLen)
        writeTun(out)
    }

    // ------------------------------------------------------------- TCP

    private fun processTcp(packet: ByteArray, len: Int, ipHeaderLen: Int, srcIp: ByteArray, dstIp: ByteArray) {
        val srcPort = PacketEngine.tcpSourcePort(packet, ipHeaderLen)
        val dstPort = PacketEngine.tcpDestPort(packet, ipHeaderLen)
        val flags = PacketEngine.tcpFlags(packet, ipHeaderLen)
        val seq = PacketEngine.tcpSeqNumber(packet, ipHeaderLen)
        val ack = PacketEngine.tcpAckNumber(packet, ipHeaderLen)
        val tcpHeaderLen = PacketEngine.tcpHeaderLength(packet, ipHeaderLen)
        val payloadLen = (len - ipHeaderLen - tcpHeaderLen).coerceAtLeast(0)
        val payload = if (payloadLen > 0) {
            packet.copyOfRange(ipHeaderLen + tcpHeaderLen, ipHeaderLen + tcpHeaderLen + payloadLen)
        } else ByteArray(0)

        if (dstPort == 53 && (flags and PacketEngine.FLAG_SYN) != 0) {
            // DNS-over-TCP: refuse gracefully; UDP DNS is handled natively.
            TcpProxy.sendReset(tunOut ?: return, srcIp, srcPort, dstIp, dstPort, seq, ack)
            return
        }
        TcpProxy.handle(
            tunOut ?: return, srcIp, srcPort, dstIp, dstPort, seq, ack, flags, payload,
        ) { protectStream(it) }
    }

    // ------------------------------------------------------------- ICMP

    private fun processIcmp(packet: ByteArray, len: Int, ipHeaderLen: Int, srcIp: ByteArray, dstIp: ByteArray) {
        if (len < ipHeaderLen + 8) return
        val type = packet[ipHeaderLen].toInt() and 0xFF
        if (type != 8) return // only echo requests
        val out = packet.copyOf(len)
        // swap addresses
        System.arraycopy(srcIp, 0, out, 16, 4)
        System.arraycopy(dstIp, 0, out, 12, 4)
        out[ipHeaderLen] = 0 // echo reply
        out[ipHeaderLen + 2] = 0
        out[ipHeaderLen + 3] = 0
        val icmpLen = len - ipHeaderLen
        val ck = PacketEngine.internetChecksum(out, ipHeaderLen, icmpLen)
        out[ipHeaderLen + 2] = ((ck shr 8) and 0xFF).toByte()
        out[ipHeaderLen + 3] = (ck and 0xFF).toByte()
        PacketEngine.fixIpv4Checksum(out, ipHeaderLen)
        writeTun(out)
    }

    private fun writeTun(out: ByteArray) {
        try {
            tunOut?.write(out)
            tunOut?.flush()
        } catch (t: Throwable) {
            KayaEventHub.emit("vpn", mapOf("state" to "tun_write"))
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName)
                ?: Intent().setClassName(this, "com.kaya.booster.MainActivity"),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 3,
            Intent(this, KayaVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, MainApplication.CHANNEL_VPN)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_kaya_tile)
            .setOngoing(true)
            .setContentIntent(open)
        notification.addAction(Notification.Action.Builder(null, "Stop", stop).build())
        return notification.build()
    }

    companion object {
        const val ACTION_STOP = "com.kaya.booster.STOP_VPN"
        private const val NOTIF_ID = 40

        fun start(context: android.content.Context) {
            context.startService(Intent(context, KayaVpnService::class.java))
        }

        fun stop(context: android.content.Context) {
            context.startService(Intent(context, KayaVpnService::class.java).setAction(ACTION_STOP))
        }
    }
}

object KayaVpnServiceHolder {
    @Volatile var vpn: KayaVpnService? = null
}
