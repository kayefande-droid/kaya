package com.kaya.booster

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Builds and parses DNS wire-format messages (RFC 1035) for the on-device
 * proxy. Supports A/AAAA queries, CNAME chains in answers, and EDNS(0).
 */
object DnsProtocol {

    const val TYPE_A = 1
    const val TYPE_AAAA = 28
    const val TYPE_CNAME = 5
    const val CLASS_IN = 1

    data class Question(val name: String, val type: Int)

    data class ResourceRecord(
        val name: String,
        val type: Int,
        val ttl: Int,
        val data: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean = other is ResourceRecord &&
            other.name == name && other.type == type && other.data.contentEquals(data)
        override fun hashCode(): Int = name.hashCode() * 31 + type
    }

    data class Message(
        val id: Int,
        val questions: List<Question>,
        val answers: List<ResourceRecord>,
        val rcode: Int,
    )

    fun buildQuery(id: Int, name: String, type: Int, recursionDesired: Boolean = true): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val header = java.nio.ByteBuffer.allocate(12)
        header.putShort(id.toShort())
        header.putShort(if (recursionDesired) 0x0100 else 0x0000)
        header.putShort(1) // QDCOUNT
        header.putShort(0)
        header.putShort(0)
        header.putShort(0)
        out.write(header.array())
        for (label in name.trimEnd('.').split('.')) {
            out.write(label.length)
            out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(0)
        out.write(shortToBytes(type))
        out.write(shortToBytes(CLASS_IN))
        return out.toByteArray()
    }

    fun parse(data: ByteArray): Message? {
        if (data.size < 12) return null
        val id = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        val flags = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        val qd = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
        val an = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
        var off = 12
        val questions = mutableListOf<Question>()
        repeat(qd) {
            val (name, next) = readName(data, off)
            off = next + 4 // qtype + qclass
            questions.add(Question(name, ((data[next].toInt() and 0xFF) shl 8) or (data[next + 1].toInt() and 0xFF)))
        }
        val answers = mutableListOf<ResourceRecord>()
        repeat(an) {
            val (name, afterName) = readName(data, off)
            off = afterName
            if (off + 10 > data.size) return Message(id, questions, answers, flags and 0xF)
            val type = ((data[off].toInt() and 0xFF) shl 8) or (data[off + 1].toInt() and 0xFF)
            val ttl = ((data[off + 6].toInt() and 0xFF) shl 24) or
                ((data[off + 7].toInt() and 0xFF) shl 16) or
                ((data[off + 8].toInt() and 0xFF) shl 8) or
                (data[off + 9].toInt() and 0xFF)
            val rdlen = ((data[off + 10].toInt() and 0xFF) shl 8) or (data[off + 11].toInt() and 0xFF)
            off += 12
            if (off + rdlen > data.size) return Message(id, questions, answers, flags and 0xF)
            answers.add(ResourceRecord(name, type, ttl, data.copyOfRange(off, off + rdlen)))
            off += rdlen
        }
        return Message(id, questions, answers, flags and 0xF)
    }

    /** Returns (name, offset-after-name) handling compression pointers. */
    private fun readName(data: ByteArray, start: Int): Pair<String, Int> {
        val labels = mutableListOf<String>()
        var off = start
        var jumped = false
        var next = -1
        var guard = 0
        while (guard++ < 64) {
            val len = data[off].toInt() and 0xFF
            if (len == 0) {
                if (!jumped) next = off + 1
                break
            }
            if (len and 0xC0 == 0xC0) {
                val ptr = ((len and 0x3F) shl 8) or (data[off + 1].toInt() and 0xFF)
                if (!jumped) next = off + 2
                off = ptr
                jumped = true
                continue
            }
            labels.add(String(data, off + 1, len, Charsets.US_ASCII))
            off += 1 + len
        }
        return Pair(labels.joinToString("."), if (next < 0) off + 1 else next)
    }

    private fun shortToBytes(v: Int): ByteArray {
        val b = ByteArray(2)
        b[0] = ((v shr 8) and 0xFF).toByte()
        b[1] = (v and 0xFF).toByte()
        return b
    }
}

/**
 * Racing DNS client: fires the same query at all resolvers in parallel and
 * keeps the first valid answer (classic anycast racing, minus the network).
 * Winners are recorded in [SteeringRules] so later lookups go straight to the
 * fastest resolver for that domain.
 */
object DnsRacer {

    data class RaceResult(
        val domain: String,
        val winner: String,
        val addresses: List<String>,
        val ttl: Int,
        val winnerMs: Int,
    )

    private val inflight = AtomicInteger(0)

    fun resolve(domain: String, type: Int = DnsProtocol.TYPE_A): RaceResult? {
        val learned = SteeringRules.winnerFor(domain)
        if (learned != null) {
            val resolver = SteeringRules.RESOLVERS.firstOrNull { it.name == learned }
            if (resolver != null) {
                val r = queryOne(domain, type, resolver.primary, 1200)
                if (r != null) return RaceResult(domain, resolver.name, r.first, r.second, -1)
            }
        }
        // Race all resolvers in parallel, take the first success.
        val results = ConcurrentHashMap<String, Pair<List<String>, Int>>()
        val threads = SteeringRules.RESOLVERS.map { resolver ->
            Thread({
                val r = queryOne(domain, type, resolver.primary, 1500)
                if (r != null) results[resolver.name] = r
            }, "kaya-dns-${resolver.name}")
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(1600) }
        val winnerEntry = results.entries.minByOrNull { it.value.second }
        return winnerEntry?.let { (name, pair) ->
            SteeringRules.recordWinner(domain, name)
            RaceResult(domain, name, pair.first, pair.second, pair.second)
        }
    }

    private fun queryOne(domain: String, type: Int, server: String, timeoutMs: Int): Pair<List<String>, Int>? {
        val id = (0 until 65536).random()
        val query = DnsProtocol.buildQuery(id, domain, type)
        val socket = java.net.DatagramSocket(null)
        KayaVpnServiceHolder.vpn?.protectDatagram(socket)
        return try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(0)) // wildcard: real source IP on send
            socket.soTimeout = timeoutMs
            val started = System.nanoTime()
            socket.send(DatagramPacket(query, query.size, InetAddress.getByName(server), 53))
            val buf = ByteArray(4096)
            val pkt = DatagramPacket(buf, buf.size)
            socket.receive(pkt)
            val elapsed = ((System.nanoTime() - started) / 1_000_000).toInt()
            val msg = DnsProtocol.parse(buf.copyOf(pkt.length)) ?: return null
            if (msg.id != id || msg.rcode != 0) return null
            val ips = msg.answers.filter { it.type == type }
                .map { rr ->
                    when (type) {
                        DnsProtocol.TYPE_A -> PacketEngine.ipToString(rr.data)
                        else -> ipv6ToString(rr.data)
                    }
                }
            if (ips.isEmpty()) null else Pair(ips, elapsed)
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun ipv6ToString(b: ByteArray): String {
        val sb = StringBuilder()
        for (i in b.indices step 2) {
            if (i > 0) sb.append(':')
            sb.append(String.format("%x", ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)))
        }
        return sb.toString()
    }

    val inflightCount: Int get() = inflight.get()
}

/** One-shot TCP handshake latency probe used by the ping benchmark. */
/** Direct probe of a single resolver, used by the benchmark UI. */
fun probeResolver(server: String, domain: String = "www.activision.com", timeoutMs: Int = 1200): Int? {
    return try {
        val query = DnsProtocol.buildQuery((0 until 65536).random(), domain, DnsProtocol.TYPE_A)
        val socket = java.net.DatagramSocket(null)
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(0))
        socket.soTimeout = timeoutMs
        KayaVpnServiceHolder.vpn?.protectDatagram(socket)
        val started = System.nanoTime()
        socket.send(DatagramPacket(query, query.size, InetAddress.getByName(server), 53))
        val buf = ByteArray(4096)
        val pkt = DatagramPacket(buf, buf.size)
        socket.receive(pkt)
        ((System.nanoTime() - started) / 1_000_000).toInt()
    } catch (_: Throwable) {
        null
    }
}

object TcpPinger {
    fun ping(host: String, port: Int, timeoutMs: Int = 1500): Int? {
        val addr = try {
            InetAddress.getByName(host)
        } catch (_: Throwable) {
            return null
        }
        val socket = Socket()
        // When the fast lane is live, Kaya's own probes must NOT be routed
        // into the tunnel — otherwise the UI benchmark would measure the
        // loopback and (on some OEM stacks) dead-lock and return nothing,
        // leaving the LIVE LATENCY panel empty exactly while the lane runs.
        KayaVpnServiceHolder.vpn?.protectStream(socket)
        return try {
            val started = System.nanoTime()
            socket.connect(InetSocketAddress(addr, port), timeoutMs)
            ((System.nanoTime() - started) / 1_000_000).toInt()
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { socket.close() }
        }
    }
}
