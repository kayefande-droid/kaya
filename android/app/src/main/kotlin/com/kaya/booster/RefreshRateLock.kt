package com.kaya.booster

import android.content.Context
import android.provider.Settings
import kotlin.math.max

/**
 * Peak refresh-rate lock for boost sessions.
 *
 * Modern panels downshift (60/90/120 Hz) to save power, and some OEMs drop
 * the refresh rate exactly when the phone warms mid-match — visibly hurting
 * input latency even when fps is uncapped. While a boost session runs, Kaya
 * raises the system's *peak* refresh-rate ceiling so the panel is allowed to
 * hold its fastest mode for the whole match, restoring the previous value on
 * release.
 *
 * Honest scope, two mechanisms:
 *  1. Settings.System "peak_refresh_rate" — the real lever. Needs the one-time
 *     "Modify system settings" grant (Settings.System.canWrite). Works on
 *     Pixels and several OEMs; where the OEM ignores it, nothing changes.
 *  2. Kaya's own window requests the panel's top mode while the app is
 *     foreground (HUD smoothness). It cannot affect the game's window —
 *     Android simply doesn't allow one app to set another's window mode.
 *
 * Everything is runCatching-guarded: unsupported devices get a clean no-op,
 * never an error path. Kaya never claims fps it can't measure.
 */
object RefreshRateLock {

    private const val KEY_PEAK = "peak_refresh_rate"
    private const val KEY_MIN = "min_refresh_rate"

    @Volatile private var applied = false
    @Volatile private var previousPeak: Float? = null

    /** True when the real lever (system setting write) is available. */
    fun canApply(context: Context): Boolean =
        runCatching { Settings.System.canWrite(context) }.getOrDefault(false)

    fun apply(context: Context) {
        if (applied || !canApply(context)) return
        runCatching {
            val resolver = context.contentResolver
            val supported = peakSupportedMode(context) ?: return
            val current = Settings.System.getFloat(resolver, KEY_PEAK, supported)
            previousPeak = current
            // Raise the ceiling to the panel's top mode; floor untouched
            // (battery behaviour outside sessions stays as the OEM set it).
            Settings.System.putFloat(resolver, KEY_PEAK, max(current, supported))
            applied = true
            KayaEventHub.emit("boost", mapOf("refreshLock" to true, "peak" to supported))
        }.onFailure {
            KayaGuard.append(context, "refresh-lock", it.message ?: "apply failed")
        }
    }

    fun release(context: Context) {
        if (!applied) return
        applied = false
        runCatching {
            val prev = previousPeak
            if (prev != null) {
                Settings.System.putFloat(context.contentResolver, KEY_PEAK, prev)
            }
            previousPeak = null
            KayaEventHub.emit("boost", mapOf("refreshLock" to false))
        }
    }

    /**
     * Highest refresh rate this device's default display reports. No public
     * WindowManager access from a Service, so read the vendor-known setting
     * names when present, else fall back to a conservative 120 Hz request —
     * the OS clamps to what the panel actually supports.
     */
    private fun peakSupportedMode(context: Context): Float? {
        return runCatching {
            // Some OEMs expose their cap here; it is the most truthful number
            // available without an Activity window.
            val v = Settings.System.getFloat(context.contentResolver, "user_refresh_rate", -1f)
            if (v > 0f) v else 120f
        }.getOrDefault(120f)
    }
}
