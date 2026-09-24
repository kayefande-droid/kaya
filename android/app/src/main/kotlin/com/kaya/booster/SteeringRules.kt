package com.kaya.booster

import java.util.concurrent.ConcurrentHashMap

/**
 * Domain rules for the Smart Anycast DNS engine.
 *
 * Kaya ships with zero servers; "steering" means picking *which* public
 * Anycast resolver answers each lookup, and optionally pinning known game
 * matchmaker hosts to the resolver whose edge PoP answers fastest from this
 * device. For African players the practical effect is avoiding the ISP's
 * default resolver chain, which is where European hand-offs usually creep in.
 */
object SteeringRules {

    /** Matchmaker / session hosts whose resolution path we steer aggressively. */
    val GAME_HOSTS: Set<String> = setOf(
        "activision.com",
        "codm.com",
        "demonware.net",
        "tencent.com",
        "pubgmobile.com",
        "garena.com",
        "supercell.com",
        "ea.com",
        "epicgames.com",
        "roblox.com",
    )

    /** Known CDNs / asset hosts: steered, but lower priority. */
    val CDN_HOSTS: Set<String> = setOf(
        "akamai.com",
        "cloudfront.net",
        "googleusercontent.com",
        "akamaized.net",
        "steamcontent.com",
    )

    /** Suffix match: does [host] belong to [domain] or a subdomain of it? */
    fun matches(host: String, domain: String): Boolean =
        host == domain || host.endsWith(".$domain")

    fun categoryFor(host: String): String {
        val h = host.lowercase().trim().trimEnd('.')
        return when {
            GAME_HOSTS.any { matches(h, it) } -> "game"
            CDN_HOSTS.any { matches(h, it) } -> "cdn"
            else -> "other"
        }
    }

    /** Resolvers we race against, in priority order. */
    val RESOLVERS: List<Resolver> = listOf(
        Resolver("Cloudflare", "1.1.1.1", "1.0.0.1"),
        Resolver("Google", "8.8.8.8", "8.8.4.4"),
        Resolver("Quad9", "9.9.9.9", "149.112.112.112"),
    )

    data class Resolver(val name: String, val primary: String, val secondary: String)

    /** Per-domain learned winner: domain -> resolver name. */
    private val winners = ConcurrentHashMap<String, String>()

    /**
     * Handshake-pinned edge IPs per game host: host -> best IP. Chosen by
     * measuring a real TCP handshake to every candidate IP the DNS race
     * returned — because a resolver that answers fastest is not necessarily
     * the one whose edge accepts handshakes fastest (DNS speed != route speed).
     */
    private val pinnedHosts = ConcurrentHashMap<String, String>()

    fun recordWinner(domain: String, resolverName: String) {
        winners[domain] = resolverName
    }

    fun winnerFor(domain: String): String? = winners[domain]

    /** Remember [ip] as the measured-fastest edge for [host]. */
    fun pinHost(host: String, ip: String) {
        pinnedHosts[host.lowercase().trimEnd('.')] = ip
    }

    /** The pinned fastest edge for [host], if a handshake race picked one. */
    fun pinnedIpFor(host: String): String? =
        pinnedHosts[host.lowercase().trimEnd('.')]

    /** Restore pinned hosts after a process restart (session-scoped data). */
    fun restorePins(pins: Map<String, String>) {
        pins.forEach { (h, ip) -> pinnedHosts[h.lowercase().trimEnd('.')] = ip }
    }

    /** Pinned hosts for persistence. */
    fun allPins(): Map<String, String> = pinnedHosts.toMap()

    fun allWinners(): Map<String, String> = winners.toMap()

    fun clearWinners() {
        winners.clear()
        pinnedHosts.clear()
    }
}
