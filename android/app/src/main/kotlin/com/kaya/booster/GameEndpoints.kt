package com.kaya.booster

/**
 * Real game-endpoint latency probes for the Network screen's game benchmark.
 * Every number is a genuine TCP handshake measured from this device, right
 * now, to the game's public auth/session endpoints. When the fast lane is
 * armed, these handshakes flow through the steered path — so the benchmark
 * shows the actual latency the game experiences, before and after arming.
 *
 * Note the honest limitation: this measures Kaya's route to the game's
 * public edge, not Activision's internal server selection; the in-game
 * ping depends on which datacenter the matchmaker assigns. Kaya's steering
 * measurably improves the DNS/edge handoffs; physics does the rest.
 */
object GameEndpoints {

    data class Endpoint(val game: String, val label: String, val host: String, val port: Int)

    /** Public, well-known endpoints per title (HTTPS / auth / session edges). */
    val ENDPOINTS: List<Endpoint> = listOf(
        Endpoint("CODM", "Matchmaker (Demonware)", "www.activision.com", 443),
        Endpoint("CODM", "Activision auth", "profile.activision.com", 443),
        Endpoint("CODM", "Store edge", "store.activision.com", 443),
        Endpoint("PUBG M", "Login edge", "www.pubgmobile.com", 443),
        Endpoint("Free Fire", "Login edge", "ff.garena.com", 443),
        Endpoint("Fortnite", "Auth edge", "www.epicgames.com", 443),
        Endpoint("Clash", "Supercell edge", "supercell.com", 443),
        Endpoint("Roblox", "Auth edge", "www.roblox.com", 443),
        Endpoint("General", "Anycast reference", "cloudflare.com", 443),
    )

    /** Median of [rounds] real handshakes (drop the unlucky first cold round). */
    fun probe(host: String, port: Int, rounds: Int = 3): Int? {
        val samples = mutableListOf<Int>()
        for (i in 0 until rounds) {
            val ms = TcpPinger.ping(host, port) ?: continue
            if (i > 0 || rounds == 1) samples.add(ms) // round 0 warms DNS+TLS route
        }
        if (samples.isEmpty()) return null
        return samples.sorted()[samples.size / 2]
    }

    /** Race all endpoints in parallel; returns label -> median ms (null = fail). */
    fun benchmarkAll(rounds: Int = 3): List<Map<String, Any?>> {
        val out = java.util.concurrent.ConcurrentHashMap<String, Map<String, Any?>>()
        val threads = ENDPOINTS.map { ep ->
            Thread({
                val ms = probe(ep.host, ep.port, rounds)
                out["${ep.game} · ${ep.label}"] = mapOf(
                    "game" to ep.game,
                    "label" to ep.label,
                    "host" to ep.host,
                    "ms" to ms,
                )
            }, "kaya-ep-${ep.game}")
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(6_000) }
        return ENDPOINTS.mapNotNull { ep -> out["${ep.game} · ${ep.label}"] }
    }
}
