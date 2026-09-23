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
    private const val KEY_ENGINE = "engine_on"
    private const val KEY_BOOST = "boost_on"
    private const val KEY_PING = "last_ping"

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
        // Boot with the widgets in sync (no-op if none placed yet).
        KayaAppWidgetProvider.refreshAll(app)
    }

    /** Persisted mirrors, read by the widget provider when the process is dead. */
    fun storedEngineOn(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENGINE, false)

    fun storedBoostOn(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_BOOST, false)

    fun storedPing(context: Context): Int? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_PING, -1).takeIf { it >= 0 }

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
