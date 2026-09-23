package com.kaya.booster

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build

/**
 * Engine auto-pilot: when a game the user marked "Boosted" goes foreground,
 * Kaya arms itself automatically — engine (if VPN consent is already
 * granted), boost locks, Game Focus and the floating live monitor. When the
 * player leaves the game and stays away past the grace period, it stands
 * down (DND restored, bubble removed; locks stop when the services do).
 *
 * Detection is event-based usage-stats polling (no POLLING every app, no
 * root, no sensitive data beyond "which app is foreground" — the same signal
 * the Usage Access permission already discloses and the user grants once).
 */
object GameAutoPilot {

    private const val PREFS = "kaya_state"
    private const val KEY_AUTOPILOT = "autopilot_on"
    private const val KEY_BOOSTED = "boosted_packages"
    private const val GRACE_MS = 90_000L // keep the session 90s after leaving

    @Volatile private var currentGame: String? = null
    private var exitAt: Long = 0

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTOPILOT, true)

    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTOPILOT, value).apply()
    }

    fun boostedPackages(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_BOOSTED, emptySet()) ?: emptySet()

    fun setBoosted(context: Context, pkg: String, boosted: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = boostedPackages(context).toMutableSet()
        if (boosted) set.add(pkg) else set.remove(pkg)
        prefs.edit().putStringSet(KEY_BOOSTED, set).apply()
    }

    /** Name of the game auto-pilot currently holds a session for (or null). */
    fun activeGame(): String? = currentGame

    /**
     * Poll one usage-event batch and react. Called periodically by
     * [KayaBoostService] while it runs — cheap (a few event reads) and exact.
     */
    fun poll(context: Context) {
        val watched = boostedPackages(context)
        val fg = foregroundGame(context, watched)
        val now = System.currentTimeMillis()

        if (fg != null) {
            if (fg != currentGame) {
                currentGame = fg
                exitAt = 0
                arm(context, fg)
            } else {
                exitAt = 0 // still in the game
            }
        } else if (currentGame != null) {
            if (exitAt == 0L) exitAt = now
            if (now - exitAt > GRACE_MS) {
                standDown(context, currentGame ?: "")
                currentGame = null
                exitAt = 0
            }
        }
    }

    private fun foregroundGame(context: Context, watched: Set<String>): String? {
        if (watched.isEmpty()) return null
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null
        val now = System.currentTimeMillis()
        val events = try {
            usm.queryEvents(now - 15_000, now)
        } catch (_: Throwable) {
            return null
        }
        var fg: String? = null
        val event = UsageEvents.Event()
        // ACTIVITY_RESUMED (API 29+) is the renamed MOVE_TO_FOREGROUND (value 1);
        // pre-29 devices only ever report the old constant.
        val fgType = if (Build.VERSION.SDK_INT >= 29) {
            UsageEvents.Event.ACTIVITY_RESUMED
        } else {
            1 // MOVE_TO_FOREGROUND
        }
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == fgType) {
                val pkg = event.packageName ?: continue
                if (pkg in watched) fg = pkg
            }
        }
        return fg
    }

    internal fun arm(context: Context, pkg: String) {
        KayaGuard.bg("autopilot-arm-$pkg") {
            // Engine first (only if the one-time consent is already granted —
            // we never surprise the user with a system dialog mid-launch).
            val prepared = try {
                android.net.VpnService.prepare(context) == null
            } catch (_: Throwable) {
                false
            }
            if (prepared && !KayaState.engineOn) {
                KayaVpnService.start(context)
                KayaState.update(engine = true)
            }
            if (!KayaState.boostOn) {
                KayaBoostService.start(context)
                KayaState.update(boost = true)
            }
            GameFocusManager.apply(context)
            KayaLiveBubble.show(context, pkg)
            KayaEventHub.emit("autopilot", mapOf("game" to pkg, "armed" to true))
        }
    }

    private fun standDown(context: Context, pkg: String) {
        KayaGuard.bg("autopilot-standdown-$pkg") {
            KayaLiveBubble.hide(context)
            GameFocusManager.restore(context)
            KayaEventHub.emit("autopilot", mapOf("game" to pkg, "armed" to false))
            // Engine/boost stay on until the user disarms them — leaving a
            // match (lobby, queue again) should not yank the fast lane.
        }
    }
}
