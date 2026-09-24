package com.kaya.booster

import java.io.ByteArrayOutputStream

/**
 * Answers DNS queries inside the tunnel using [DnsRacer] results.
 * The game sees one fast, clean DNS answer; its traffic then flows directly
 * to the winning edge — no Kaya server in the middle, ever.
 */
object DnsResponder {

    private data class Entry(val at: Long, val ips: List<String>, val resolver: String)

    // Tun-read thread + racer threads share this: must be concurrent.
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Entry>()
    private const val TTL_MS = 60_000L

    fun answer(srcIp: ByteArray, srcPort: Int, query: ByteArray, writer: (ByteArray) -> Unit) {
        val msg = DnsProtocol.parse(query)
        if (msg == null || msg.questions.isEmpty()) return
        val question = msg.questions.first()

        val category = SteeringRules.categoryFor(question.name)
        val key = "${question.type}|${question.name.lowercase()}"

        val cached = cache[key]
        val entry: Entry? = if (cached != null && System.currentTimeMillis() - cached.at < TTL_MS) {
            cached
        } else {
            val result = DnsRacer.resolve(question.name, question.type)
            if (result != null && result.addresses.isNotEmpty()) {
                val e = Entry(System.currentTimeMillis(), result.addresses, result.winner)
                cache[key] = e
                e
            } else {
                null
            }
        }

        val ips0 = entry?.ips ?: emptyList()
        val resolver = entry?.resolver ?: "unresolved"
        // Put the handshake-pinned fastest edge first when we have one. This
        // only reorders the answer the resolver actually gave us — Kaya never
        // invents addresses — but games typically try the first A record, so
        // first place is where the measured-fastest edge belongs.
        val pin = SteeringRules.pinnedIpFor(question.name)
        val ips = if (pin != null && ips0.size > 1 && pin in ips0) {
            listOf(pin) + ips0.filter { it != pin }
        } else {
            ips0
        }
        val response = buildResponse(msg.id, question, ips, negative = ips.isEmpty())

        KayaEventHub.emit(
            "dns",
            mapOf(
                "domain" to question.name,
                "category" to category,
                "resolver" to resolver,
                "ips" to ips,
                "ttl" to (TTL_MS / 1000).toInt(),
            ),
        )

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
        writer(out)
    }

    /** Minimal DNS response: echoed question + A/AAAA answers (or NXDOMAIN). */
    private fun buildResponse(
        id: Int,
        question: DnsProtocol.Question,
        ips: List<String>,
        negative: Boolean,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val header = java.nio.ByteBuffer.allocate(12)
        header.putShort(id.toShort())
        header.putShort(if (negative) 0x8183.toShort() else 0x8180.toShort())
        header.putShort(1) // QDCOUNT
        header.putShort(if (ips.isEmpty()) 0 else ips.size.toShort()) // ANCOUNT
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

        for (ip in ips) {
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
