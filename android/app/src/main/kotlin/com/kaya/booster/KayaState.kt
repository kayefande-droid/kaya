package com.kaya.booster

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * Process-wide truth for engine/boost state, mirrored into a tiny prefs file
 * so the tile and the home-screen widget survive process restarts.
 *
 * Every state change funnels through [update]:
 *  - persists the flags (crash-safe: after a process kill the widget shows the
 *    stored truth, not a stale "on" claim),
 *  - refreshes the home-screen widgets,
 *  - notifies the app UI through the KayaEventHub ("session" events).
 */
object KayaState {

    private const val PREFS = "kaya_state"

    /** Shared prefs handle for simple flags (tutorial seen, etc.). */
    fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private const val KEY_ENGINE = "engine_on"
    private const val KEY_BOOST = "boost_on"
    private const val KEY_PING = "last_ping"
    private const val KEY_BUBBLE_SIDE = "bubble_side"
    private const val KEY_EDGE_PINS = "edge_pins"

    private val mainHandler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null

    @Volatile var engineOn = false
        private set

    @Volatile var boostOn = false
        private set

    /** Last honest TCP handshake sample (ms) or null; surfaced on the widget. */
    @Volatile var lastPing: Int? = null
        private set

    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        engineOn = prefs.getBoolean(KEY_ENGINE, false)
        boostOn = prefs.getBoolean(KEY_BOOST, false)
        lastPing = prefs.getInt(KEY_PING, -1).takeIf { it >= 0 }
        // Handshake-pinned game edges survive process restarts: re-learning
        // them costs a second of probing at the next lookup, and keeping the
        // old winners makes the first match after a restart land on the same
        // fast edge instead of re-rolling.
        SteeringRules.restorePins(parsePins(prefs.getString(KEY_EDGE_PINS, null)))
        // Boot with the widgets in sync (no-op if none placed yet).
        KayaAppWidgetProvider.refreshAll(app)
    }

    /** Persist the current handshake pins ("host|ip;host|ip"). */
    fun persistPins() {
        val ctx = appContext ?: return
        val encoded = SteeringRules.allPins().entries.joinToString(";") { "${it.key}|${it.value}" }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_EDGE_PINS, encoded)
            .apply()
    }

    private fun parsePins(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val out = mutableMapOf<String, String>()
        for (pair in raw.split(';')) {
            val parts = pair.split('|')
            if (parts.size == 2 && parts[0].contains('.') && parts[1].contains('.')) {
                out[parts[0]] = parts[1]
            }
        }
        return out
    }

    /** Persisted mirrors, read by the widget provider when the process is dead. */
    fun storedEngineOn(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENGINE, false)

    fun storedBoostOn(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_BOOST, false)

    fun storedPing(context: Context): Int? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_PING, -1).takeIf { it >= 0 }

    /**
     * Dock side of the floating live monitor: "left" (default) or "right".
     * Survives process restarts so the bubble re-docks where the user chose.
     */
    fun storedBubbleSide(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_BUBBLE_SIDE, "left") ?: "left"

    fun setBubbleSide(context: Context, side: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BUBBLE_SIDE, if (side == "right") "right" else "left")
            .apply()
    }

    /**
     * Force a widget/session re-render without changing any value — used
     * after operations that don't produce a new sample (e.g. a route refresh
     * probe that timed out) so the UI never shows a stuck pending state.
     */
    fun touch() {
        KayaEventHub.emit(
            "session",
            mapOf("engine" to engineOn, "boost" to boostOn, "lastPing" to lastPing),
        )
        mainHandler.post {
            appContext?.let { KayaAppWidgetProvider.refreshAll(it) }
        }
    }

    /**
     * Record a state change (any subset of engine/boost/ping). Safe to call
     * from any thread; the widget refresh is posted to the main looper.
     */
    fun update(engine: Boolean? = null, boost: Boolean? = null, ping: Int? = null) {
        // A no-op ping sample (same value as before) shouldn't re-render widgets.
        if (engine == null && boost == null && ping != null && ping == lastPing) return
        if (engine != null || boost != null || ping != null) {
            engine?.let { engineOn = it }
            boost?.let { boostOn = it }
            ping?.let { lastPing = it }
            appContext?.let { ctx ->
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_ENGINE, engineOn)
                    .putBoolean(KEY_BOOST, boostOn)
                    .putInt(KEY_PING, lastPing ?: -1)
                    .apply()
            }
        }
        // Mirror the session into the running Flutter UI (if the app is up).
        KayaEventHub.emit(
            "session",
            mapOf("engine" to engineOn, "boost" to boostOn, "lastPing" to lastPing),
        )
        // Refresh widgets on the main thread (RemoteViews requirement).
        mainHandler.post {
            appContext?.let { KayaAppWidgetProvider.refreshAll(it) }
        }
    }
}
