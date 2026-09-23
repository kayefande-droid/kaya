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
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Kaya's on-device fast lane (VpnService).
 *
 * Crash-hardening notes (this class used to take the app down when armed):
 *  - [startForeground] runs BEFORE any socket/port work, inside a try/catch,
 *    with a plain PNG small icon (vector resource icons crashed some OEM
 *    launchers' notification shading).
 *  - Every port bind uses a fallback port; if both fail the engine still runs
 *    (DNS answered in-process, UDP passes through untouched) instead of dying.
 *  - The TUN read loop is launched only after establish() returns non-null.
 *  - onRevoke() and onDestroy() are fully idempotent.
 */
class KayaVpnService : VpnService() {

    private val running = AtomicBoolean(false)
    private var tun: ParcelFileDescriptor? = null
    private var tunOut: FileOutputStream? = null
    private var replySocket: DatagramSocket? = null
    private var replyThread: Thread? = null
    private var readThread: Thread? = null
    private val nextTid = AtomicInteger(0x4B59) // "KY"

    private val tunAddr = "198.18.0.2"
    private val tunRouter = "198.18.0.1"
    private var fwdUdpPort = 51975
    private var fwdRelayPort = 51975
    private var replyUdpPort = 51976

    override fun onCreate() {
        super.onCreate()
        KayaState.init(this)
        KayaVpnServiceHolder.vpn = this
        KayaEventHub.emit("vpn", mapOf("state" to "created"))
    }

    override fun onDestroy() {
        val wasRunning = running.getAndSet(false)
        runCatching { replySocket?.close() }
        runCatching { tunOut?.close() }
        runCatching { tun?.close() }
        UdpForwarder.stop()
        TcpProxy.shutdown()
        fwdRelayPort = fwdUdpPort
        KayaVpnServiceHolder.vpn = null
        if (wasRunning) KayaState.update(engine = false)
        KayaEventHub.emit("vpn", mapOf("state" to "stopped"))
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always satisfy the startForegroundService obligation FIRST — stopping
        // without promoting crashes the process on Android 12+.
        promoteToForeground()
        when (intent?.action) {
            ACTION_STOP -> {
                // Tearing down the engine must also release the boost
                // session — otherwise a stopped engine leaves locks, the
                // mitigator and the auto-pilot poller running against a
                // dead tunnel (the on/off-loop bug).
                try {
                    startService(Intent(this, KayaBoostService::class.java).setAction(ACTION_STOP))
                } catch (_: Throwable) {
                }
                stopSelf()
                return START_NOT_STICKY
            }
            else -> if (running.get().not()) startSafely()
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

    /** Promotes to foreground FIRST, tolerating every OEM quirk. */
    private fun promoteToForeground() {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NOTIF_ID,
                    buildNotification(getString(R.string.notif_vpn_title), getString(R.string.notif_vpn_text)),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                @Suppress("DEPRECATION")
                startForeground(
                    NOTIF_ID,
                    buildNotification(getString(R.string.notif_vpn_title), getString(R.string.notif_vpn_text)),
                )
            }
        } catch (t: Throwable) {
            // Some OEMs throw if the channel is mid-creation; a plain retry is fine.
            runCatching {
                @Suppress("DEPRECATION")
                startForeground(
                    NOTIF_ID,
                    buildNotification(getString(R.string.notif_vpn_title), getString(R.string.notif_vpn_text)),
                )
            }
        }
    }

    private fun startSafely() {
        promoteToForeground() // never touch ports before this line

        val fd = try {
            buildVpnInterface()
        } catch (t: Throwable) {
            KayaEventHub.emit("vpn", mapOf("state" to "error", "message" to (t.message ?: "establish")))
            return
        }
        if (fd == null) {
            KayaEventHub.emit("vpn", mapOf("state" to "error", "message" to "consent-or-system"))
            return
        }

        tun = fd
        tunOut = FileOutputStream(fd.fileDescriptor)
        running.set(true)

        startReplyLoop()
        // Bring the UDP relay up (guarded: a busy port must never take the
        // engine down — packets simply pass through untouched instead).
        var relayPort = fwdUdpPort
        for (candidate in intArrayOf(fwdUdpPort, fwdUdpPort + 1, fwdUdpPort + 7, 0)) {
            val started = runCatching {
                UdpForwarder.start(candidate, replyUdpPort) { sock -> protectDatagram(sock) }
                true
            }.getOrDefault(false)
            if (started) {
                relayPort = UdpForwarder.boundPort().takeIf { it > 0 } ?: candidate
                break
            }
        }
        fwdRelayPort = relayPort

        startReadLoop(fd)
        KayaState.update(engine = true)
        KayaEventHub.emit("vpn", mapOf("state" to "active"))
    }

    private fun buildVpnInterface(): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession("Kaya Fast Lane")
            .setMtu(1500)
            .addAddress(tunAddr, 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(tunRouter)
        return try {
            builder.establish()
        } catch (t: Throwable) {
            null
        }
    }

    private fun pickFreePort(preferred: Int): Int {
        // try preferred, then a small fallback range; -1 means "none"
        for (port in intArrayOf(preferred, preferred + 1, preferred + 7, 0)) {
            val probe = DatagramSocket(null)
            try {
                probe.reuseAddress = true
                probe.bind(InetSocketAddress("127.0.0.1", port))
                val bound = probe.localPort
                return bound
            } catch (_: Throwable) {
                // occupied; next candidate
            } finally {
                runCatching { probe.close() }
            }
        }
        return -1
    }

    private fun startReplyLoop() {
        val port = pickFreePort(replyUdpPort)
        if (port == -1) return // engine still works; replies flow via in-process DNS
        replyUdpPort = port
        val reply = DatagramSocket(null)
        try {
            reply.reuseAddress = true
            reply.bind(InetSocketAddress("127.0.0.1", port))
        } catch (t: Throwable) {
            runCatching { reply.close() }
            return
        }
        reply.soTimeout = 500
        replySocket = reply
        replyThread = Thread({
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
                    if (running.get()) runCatching { Thread.sleep(50) }
                }
            }
        }, "kaya-reply")
        replyThread?.start()
    }

    private fun startReadLoop(fd: ParcelFileDescriptor) {
        readThread = Thread({
            val input = FileInputStream(fd.fileDescriptor)
            val packet = ByteArray(32767)
            while (running.get()) {
                try {
                    val len = input.read(packet)
                    if (len < 20) continue
                    processPacket(packet, len)
                } catch (t: Throwable) {
                    if (running.get()) runCatching { Thread.sleep(20) }
                }
            }
        }, "kaya-tun-read")
        readThread?.start()
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
        if (len < ipHeaderLen + 8) return // truncated UDP header
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

        // Relay game/app UDP through the loopback forwarder (best effort).
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
            sock.bind(InetSocketAddress(0))
            sock.send(DatagramPacket(out, out.size, InetAddress.getByName("127.0.0.1"), fwdRelayPort))
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
        if (len < ipHeaderLen + 20) return // truncated header; drop instead of throwing
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
            // tunnel closing; the read loop will notice
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
            .setSmallIcon(R.drawable.ic_notif_kaya) // plain PNG: safe on every OEM
            .setOngoing(true)
            .setContentIntent(open)
        notification.addAction(Notification.Action.Builder(null, "Stop", stop).build())
        return notification.build()
    }

    companion object {
        const val ACTION_STOP = "com.kaya.booster.STOP_VPN"
        private const val NOTIF_ID = 40

        /** Background-safe: returns false instead of throwing when the OS refuses. */
        fun start(context: android.content.Context): Boolean = runCatching {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(Intent(context, KayaVpnService::class.java))
            } else {
                context.startService(Intent(context, KayaVpnService::class.java))
            }
            true
        }.getOrDefault(false)

        fun stop(context: android.content.Context) {
            runCatching {
                context.startService(Intent(context, KayaVpnService::class.java).setAction(ACTION_STOP))
            }
        }
    }
}

object KayaVpnServiceHolder {
    @Volatile var vpn: KayaVpnService? = null
}
