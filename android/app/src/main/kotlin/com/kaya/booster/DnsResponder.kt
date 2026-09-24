package com.kaya.booster

import java.io.ByteArrayOutputStream

/**
 * Answers DNS queries inside the tunnel using [DnsRacer] results.
 * The game sees one fast, clean DNS answer; its traffic then flows directly
 * to the winning edge — no Kaya server in the middle, ever.
 *
 * v1.1.7 fail-open contract (the "VPN must never break my internet" rule):
 *  1. [answer] NEVER blocks the tun-read thread: cached names are answered
 *     instantly; on a miss the query is registered and the resolver race
 *     runs on a background thread, which replies to the requester directly
 *     (well inside the ~5 s client timeout). A slow upstream delays one
 *     domain — never the tunnel.
 *  2. Duplicate queries for the same name (normal client retry behaviour)
 *     are de-duplicated while one race is already in flight.
 *  3. If every resolver fails, the last-known-good (stale) answer stays
 *     servable for 30 min: "old but working" beats "correctly dead". No
 *     failure is ever written into the cache.
 *  4. Only when we truly have nothing do we answer SERVFAIL — the client
 *     retries on its own schedule, and other domains keep flowing meanwhile.
 *  5. Unsupported record types (PTR, TXT, …) get NOTIMP, which terminates
 *     client retry loops cleanly instead of provoking them.
 */
object DnsResponder {

    private data class Entry(val at: Long, val ips: List<String>, val resolver: String)

    // Tun-read thread + racer threads share this: must be concurrent.
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Entry>()
    private const val TTL_MS = 60_000L

    /** How long a stale entry stays servable after all resolvers fail. */
    private const val STALE_FAIL_MS = 30 * 60_000L

    /** Names with a race currently in flight (dedupe of client retries). */
    private val pending = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Max A/AAAA answers per reply — keeps the response inside the 1280 MTU. */
    private const val MAX_ANSWERS = 32

    fun answer(srcIp: ByteArray, srcPort: Int, query: ByteArray, writer: (ByteArray) -> Unit) {
        val msg = DnsProtocol.parse(query)
        if (msg == null || msg.questions.isEmpty()) return
        val question = msg.questions.first()
        val now = System.currentTimeMillis()
        val key = "${question.type}|${question.name.lowercase()}"

        // Supported record types only — anything else gets NOTIMP so the
        // client's resolver library stops retrying instead of looping.
        if (question.type != DnsProtocol.TYPE_A && question.type != DnsProtocol.TYPE_AAAA) {
            reply(writer, srcIp, srcPort, buildResponse(msg.id, question, emptyList(), rcode = 4))
            return
        }

        val entry = cache[key]
        if (entry != null && now - entry.at < TTL_MS) {
            reply(writer, srcIp, srcPort, entryResponse(msg.id, question, entry, cacheHit = true, stale = false))
            return
        }

        // Miss (or stale hit): the FIRST query for a name spawns one
        // background race; duplicates hold off — the client's built-in retry
        // picks the answer up. The completion handler answers the original
        // requester directly, so first lookups still land inside the client
        // timeout, and the read loop never waits on the network.
        if (pending.add(key)) {
            val id = msg.id
            KayaGuard.bg("dns-resolve-${question.name}") {
                try {
                    val result = DnsRacer.resolve(question.name, question.type)
                    val fresh = if (result != null && result.addresses.isNotEmpty()) {
                        Entry(System.currentTimeMillis(), result.addresses, result.winner)
                    } else {
                        // All resolvers failed (dead Wi-Fi, captive portal,
                        // timeouts): fail-open by leaving the stale entry
                        // servable — never poison the cache with a failure.
                        null
                    }
                    if (fresh != null) cache[key] = fresh
                    val best = fresh ?: cache[key]?.takeIf {
                        System.currentTimeMillis() - it.at < STALE_FAIL_MS
                    }
                    if (best != null) {
                        KayaEventHub.emit(
                            "dns",
                            mapOf(
                                "domain" to question.name,
                                "category" to SteeringRules.categoryFor(question.name),
                                "resolver" to best.resolver,
                                "ips" to best.ips,
                                "ttl" to (TTL_MS / 1000).toInt(),
                            ),
                        )
                        reply(writer, srcIp, srcPort, entryResponse(id, question, best, cacheHit = false, stale = false))
                    } else {
                        // Nothing anywhere: SERVFAIL and let the client retry.
                        reply(writer, srcIp, srcPort, buildResponse(id, question, emptyList(), rcode = 2))
                    }
                } finally {
                    pending.remove(key)
                }
            }
        }

        // Meanwhile, serve what we have: a stale answer now is infinitely
        // better than silence for an app that's about to give up.
        if (entry != null && now - entry.at < STALE_FAIL_MS) {
            reply(writer, srcIp, srcPort, entryResponse(msg.id, question, entry, cacheHit = false, stale = true))
        }
    }

    /** Build the wire answer for a cached/stale/fresh entry (hot path). */
    private fun entryResponse(
        id: Int,
        question: DnsProtocol.Question,
        entry: Entry,
        cacheHit: Boolean,
        stale: Boolean,
    ): ByteArray {
        val pin = SteeringRules.pinnedIpFor(question.name)
        val ips0 = entry.ips
        // Put the handshake-pinned fastest edge first when we have one. This
        // only reorders the answer the resolver actually gave us — Kaya never
        // invents addresses — but games typically try the first A record, so
        // first place is where the measured-fastest edge belongs.
        val ips = if (pin != null && ips0.size > 1 && pin in ips0) {
            listOf(pin) + ips0.filter { it != pin }
        } else {
            ips0
        }
        DnsFeed.record(
            domain = question.name,
            resolver = if (stale) "${entry.resolver}*" else entry.resolver,
            ip = ips.firstOrNull(),
            pinned = pin != null && ips.firstOrNull() == pin,
            cached = cacheHit || stale,
        )
        return buildResponse(id, question, ips, rcode = 0)
    }

    private fun reply(writer: (ByteArray) -> Unit, srcIp: ByteArray, srcPort: Int, response: ByteArray) {
        // Wrap: UDP(53 -> clientPort) + IPv4(virtual router -> client)
        val udp = PacketEngine.buildUdpHeader(53, srcPort, response.size)
        val routerIp = byteArrayOf(198.toByte(), 18.toByte(), 0, 1)
        val ip = PacketEngine.buildIpv4Header(
            src = routerIp,
            dst = srcIp,
            protocol = 17,
            payloadLen = udp.size + response.size,
            identification = 0,
        )
        val out = ByteArray(ip.size + udp.size + response.size)
        System.arraycopy(ip, 0, out, 0, ip.size)
        System.arraycopy(udp, 0, out, ip.size, udp.size)
        System.arraycopy(response, 0, out, ip.size + udp.size, response.size)
        runCatching { writer(out) }
    }

    /** Minimal DNS response: echoed question + A/AAAA answers (or error code). */
    private fun buildResponse(
        id: Int,
        question: DnsProtocol.Question,
        ips: List<String>,
        rcode: Int,
    ): ByteArray {
        val answers = ips.take(MAX_ANSWERS)
        val out = ByteArrayOutputStream()
        val header = java.nio.ByteBuffer.allocate(12)
        header.putShort(id.toShort())
        // 0x8180 = response + recursion-available; low bits carry the rcode.
        header.putShort((0x8180 or (rcode and 0xF)).toShort())
        header.putShort(1) // QDCOUNT
        header.putShort(answers.size.toShort()) // ANCOUNT
        header.putShort(0) // NSCOUNT
        header.putShort(0) // ARCOUNT
        out.write(header.array())

        for (label in question.name.trimEnd('.').split('.')) {
            if (label.isEmpty()) continue
            out.write(label.length)
            out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(0)
        out.write(PacketEngine.shortToBytes(question.type))
        out.write(PacketEngine.shortToBytes(DnsProtocol.CLASS_IN))

        for (ip in answers) {
            out.write(0xC0)
            out.write(0x0C) // pointer to question name
            out.write(PacketEngine.shortToBytes(question.type))
            out.write(PacketEngine.shortToBytes(DnsProtocol.CLASS_IN))
            out.write(byteArrayOf(0, 0, 0, 60)) // TTL 60s
            val data = when (question.type) {
                DnsProtocol.TYPE_A -> {
                    val parts = ip.split('.')
                    if (parts.size == 4) byteArrayOf(
                        parts[0].toInt().toByte(), parts[1].toInt().toByte(),
                        parts[2].toInt().toByte(), parts[3].toInt().toByte(),
                    ) else ByteArray(4)
                }
                else -> {
                    // expand IPv6 compact form
                    val b = ByteArray(16)
                    try {
                        val addr = java.net.InetAddress.getByName(ip).address
                        System.arraycopy(addr, 0, b, 0, addr.size.coerceAtMost(16))
                    } catch (_: Throwable) {
                    }
                    b
                }
            }
            out.write(PacketEngine.shortToBytes(data.size))
            out.write(data)
        }
        return out.toByteArray()
    }

    fun clearCache() = cache.clear()
}
