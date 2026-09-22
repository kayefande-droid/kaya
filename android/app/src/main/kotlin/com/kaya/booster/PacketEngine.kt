package com.kaya.booster

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Hand-rolled IPv4 packet builder/parser for the on-device VPN tunnel.
 * No third-party dependency: everything the engine needs lives here.
 */
object PacketEngine {

    // ------------------------------------------------------------------ IPv4

    fun buildIpv4Header(
        src: ByteArray,
        dst: ByteArray,
        protocol: Int,
        payloadLen: Int,
        identification: Short,
        dfFlag: Boolean = true,
    ): ByteArray {
        val buf = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN)
        buf.put(0x45.toByte()) // version 4, IHL 5
        buf.put(0.toByte()) // DSCP/ECN
        buf.putShort((20 + payloadLen).toShort())
        buf.putShort(identification)
        val flagsFrag = if (dfFlag) 0x4000 else 0
        buf.putShort(flagsFrag.toShort())
        buf.put(64.toByte()) // TTL
        buf.put(protocol.toByte())
        buf.putShort(0) // checksum placeholder
        buf.put(src)
        buf.put(dst)
        val array = buf.array()
        val ck = internetChecksum(array, 0, 20)
        array[10] = ((ck shr 8) and 0xFF).toByte()
        array[11] = (ck and 0xFF).toByte()
        return array
    }

    /** Rewrites an IPv4 header's checksum in place after mutation. */
    fun fixIpv4Checksum(packet: ByteArray, ipHeaderLen: Int) {
        packet[10] = 0
        packet[11] = 0
        val ck = internetChecksum(packet, 0, ipHeaderLen)
        packet[10] = ((ck shr 8) and 0xFF).toByte()
        packet[11] = (ck and 0xFF).toByte()
    }

    fun ipv4HeaderLength(packet: ByteArray): Int = (packet[0].toInt() and 0x0F) * 4

    fun ipv4Protocol(packet: ByteArray): Int = packet[9].toInt() and 0xFF

    fun ipv4Source(packet: ByteArray): ByteArray = packet.copyOfRange(12, 16)

    fun ipv4Destination(packet: ByteArray): ByteArray = packet.copyOfRange(16, 20)

    fun ipToString(bytes: ByteArray): String =
        bytes.joinToString(".") { (it.toInt() and 0xFF).toString() }

    // ------------------------------------------------------------------ UDP

    fun buildUdpHeader(srcPort: Int, dstPort: Int, payloadLen: Int): ByteArray {
        val buf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putShort((8 + payloadLen).toShort())
        buf.putShort(0) // UDP checksum: zero is valid for IPv4, saves CPU
        return buf.array()
    }

    fun udpSourcePort(packet: ByteArray, ipHeaderLen: Int): Int {
        val off = ipHeaderLen
        return ((packet[off].toInt() and 0xFF) shl 8) or (packet[off + 1].toInt() and 0xFF)
    }

    fun udpDestPort(packet: ByteArray, ipHeaderLen: Int): Int {
        val off = ipHeaderLen + 2
        return ((packet[off].toInt() and 0xFF) shl 8) or (packet[off + 1].toInt() and 0xFF)
    }

    fun udpLength(packet: ByteArray, ipHeaderLen: Int): Int {
        val off = ipHeaderLen + 4
        return ((packet[off].toInt() and 0xFF) shl 8) or (packet[off + 1].toInt() and 0xFF)
    }

    // ------------------------------------------------------------------ TCP

    fun tcpSourcePort(packet: ByteArray, ipHeaderLen: Int): Int =
        udpSourcePort(packet, ipHeaderLen)

    fun tcpDestPort(packet: ByteArray, ipHeaderLen: Int): Int =
        udpDestPort(packet, ipHeaderLen)

    fun tcpSeqNumber(packet: ByteArray, ipHeaderLen: Int): Long {
        val off = ipHeaderLen + 4
        return ((packet[off].toInt() and 0xFF).toLong() shl 24) or
            ((packet[off + 1].toInt() and 0xFF).toLong() shl 16) or
            ((packet[off + 2].toInt() and 0xFF).toLong() shl 8) or
            (packet[off + 3].toInt() and 0xFF).toLong()
    }

    fun tcpAckNumber(packet: ByteArray, ipHeaderLen: Int): Long {
        val off = ipHeaderLen + 8
        return ((packet[off].toInt() and 0xFF).toLong() shl 24) or
            ((packet[off + 1].toInt() and 0xFF).toLong() shl 16) or
            ((packet[off + 2].toInt() and 0xFF).toLong() shl 8) or
            (packet[off + 3].toInt() and 0xFF).toLong()
    }

    fun tcpHeaderLength(packet: ByteArray, ipHeaderLen: Int): Int {
        val off = ipHeaderLen + 12
        return ((packet[off].toInt() and 0xF0) shr 4) * 4
    }

    fun tcpFlags(packet: ByteArray, ipHeaderLen: Int): Int {
        val off = ipHeaderLen + 13
        return packet[off].toInt() and 0x3F
    }

    fun buildTcpHeader(
        srcPort: Int,
        dstPort: Int,
        seq: Long,
        ack: Long,
        flags: Int,
        window: Int,
        payloadLen: Int,
    ): ByteArray {
        val buf = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putInt(seq.toInt())
        buf.putInt(ack.toInt())
        buf.put(((4 shl 4) or 5).toByte()) // data offset 5 (20 bytes), reserved
        buf.put(flags.toByte())
        buf.putShort(window.coerceAtMost(0xFFFF).toShort())
        buf.putShort(0) // checksum zero (IPv4 tolerance) - tunnel-internal only
        buf.putShort(0) // urgent pointer
        return buf.array()
    }

    // ------------------------------------------------------------------ misc

    fun internetChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (data[i].toInt() and 0xFF) shl 8
        while (sum ushr 16 != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        return (sum.toInt().inv()) and 0xFFFF
    }

    const val FLAG_FIN = 0x01
    const val FLAG_SYN = 0x02
    const val FLAG_RST = 0x04
    const val FLAG_PSH = 0x08
    const val FLAG_ACK = 0x10

    /**
     * Computes the TCP checksum (with IPv4 pseudo-header) and writes it into
     * the segment's checksum field. The kernel validates TCP checksums on
     * packets injected through the TUN, so this must be correct.
     */
    fun withTcpChecksum(src: ByteArray, dst: ByteArray, segment: ByteArray): ByteArray {
        val out = segment.copyOf()
        out[16] = 0
        out[17] = 0
        val pseudo = ByteArray(12)
        System.arraycopy(src, 0, pseudo, 0, 4)
        System.arraycopy(dst, 0, pseudo, 4, 4)
        pseudo[8] = 0
        pseudo[9] = 6 // TCP
        pseudo[10] = ((segment.size shr 8) and 0xFF).toByte()
        pseudo[11] = (segment.size and 0xFF).toByte()
        val total = ByteArray(pseudo.size + out.size)
        System.arraycopy(pseudo, 0, total, 0, pseudo.size)
        System.arraycopy(out, 0, total, pseudo.size, out.size)
        val ck = internetChecksum(total, 0, total.size)
        out[16] = ((ck shr 8) and 0xFF).toByte()
        out[17] = (ck and 0xFF).toByte()
        return out
    }

    fun shortToBytes(v: Int): ByteArray {
        val b = ByteArray(2)
        b[0] = ((v shr 8) and 0xFF).toByte()
        b[1] = (v and 0xFF).toByte()
        return b
    }
}
