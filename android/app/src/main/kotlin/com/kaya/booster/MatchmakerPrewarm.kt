package com.kaya.booster

/**
 * Matchmaker pre-warm: the moment the engine arms, quietly resolve every
 * known game matchmaker domain in the background. Two wins for the player:
 *  - the first matchmaking lookup after a game launch is answered from cache
 *    (no race latency in front of "Find match"), and
 *  - the handshake-pinned fastest edge is measured and persisted BEFORE the
 *    game needs it, so the first connection already rides the best edge.
 *
 * Everything is real: the DnsRacer results land in the same cache the engine
 * serves from, and the edge pins come from measured TCP handshakes (persisted
 * via KayaState, like all pins). Nothing is fabricated; if a domain fails to
 * resolve (no network), it simply isn't cached and is raced normally later.
 *
 * Staggered on one background thread so the pre-warm never competes with
 * in-flight game traffic — each lookup is sequential, low-rate, and the
 * DnsResponder de-dupes overlapping client queries anyway.
 */
object MatchmakerPrewarm {

    @Volatile private var lastRunAt = 0L

    /** Domains worth having hot before a game touches them. */
    private val hosts = buildList {
        // Matchmaker/session hosts, deepest label first so caches serve the
        // exact names games actually query.
        add("www.activision.com")
        add("activision.com")
        add("codm.activision.com")
        add("demonware.net")
        add("pubgmobile.com")
        add("garena.com")
        add("supercell.com")
        add("ea.com")
        add("epicgames.com")
        add("roblox.com")
        // CDN hosts the first post-match patch check usually hits.
        add("akamaized.net")
        add("cloudfront.net")
    }

    private const val MIN_INTERVAL_MS = 10 * 60_000L

    fun warm() {
        val now = System.currentTimeMillis()
        if (now - lastRunAt < MIN_INTERVAL_MS) return // arm/disarm flapping guard
        lastRunAt = now
        KayaGuard.bg("prewarm") {
            for (host in hosts) {
                if (KayaVpnServiceHolder.vpn == null) break // engine disarmed mid-warm
                val cached = DnsResponder.cachedIps(host)
                if (cached != null) continue // already hot
                val result = DnsRacer.resolve(host, DnsProtocol.TYPE_A)
                if (result != null && result.addresses.isNotEmpty()) {
                    DnsResponder.putCached(host, result.addresses, result.winner)
                }
                runCatching { Thread.sleep(400) } // stay polite on the wire
            }
        }
    }
}
