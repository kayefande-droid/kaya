package com.kaya.booster

/**
 * Tiny process-local ring of the last real DNS lookups the engine answered.
 * Fed by [DnsResponder] on every genuine upstream resolution (and on cache
 * hits), read by the live bubble's "DNS feed" peek — so what the user sees
 * over their game is exactly what the engine did, timestamps included.
 * No simulation: entries only appear when a real query was really answered.
 */
object DnsFeed {

    data class Entry(
        val at: Long,
        val domain: String,
        val resolver: String,
        val ip: String?,
        val pinned: Boolean,
        val cached: Boolean,
    )

    private const val CAP = 12
    private val ring = ArrayDeque<Entry>(CAP)
    private var statsHits = 0
    private var statsMisses = 0

    @Synchronized
    fun record(domain: String, resolver: String, ip: String?, pinned: Boolean, cached: Boolean) {
        if (domain.isBlank()) return
        ring.addFirst(
            Entry(
                at = System.currentTimeMillis(),
                domain = domain,
                resolver = resolver,
                ip = ip,
                pinned = pinned,
                cached = cached,
            ),
        )
        while (ring.size > CAP) ring.removeLast()
        if (cached) statsHits++ else statsMisses++
    }

    /** Newest-first snapshot for UI rendering. */
    @Synchronized
    fun recent(): List<Entry> = ring.toList()

    @Synchronized
    fun hits(): Int = statsHits

    @Synchronized
    fun misses(): Int = statsMisses
}
