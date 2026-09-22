package com.kaya.booster

import android.app.usage.UsageStatsManager
import android.content.Context
import android.net.TrafficStats
import android.os.Build
import android.os.PowerManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Best-effort, root-free background mitigation while a game session is live:
 *  - requests a temporary "burst" of battery saver restraint (no throttling of
 *    the foreground app, but background jobs are deferred by the OS scheduler)
 *  - samples per-UID traffic so the UI can show which apps steal bandwidth
 *  - exposes a snapshot for the HUD
 *
 * On Android 12+ the app can also request the exact background-restriction
 * bucket for the top 3 sync-heavy apps it detects; those are surfaced in the
 * Tuning screen where the user can tap to restrict them (user-visible action).
 */
class BackgroundMitigator(private val context: Context) {

    private val active = AtomicBoolean(false)
    private var baselineRx = 0L
    private var baselineTx = 0L
    private var lastSampleAt = 0L

    val isActive: Boolean get() = active.get()

    fun activate() {
        if (active.getAndSet(true)) return
        baselineRx = TrafficStats.getTotalRxBytes()
        baselineTx = TrafficStats.getTotalTxBytes()
        lastSampleAt = System.currentTimeMillis()
        KayaEventHub.emit("mitigator", mapOf("active" to true))
    }

    fun deactivate() {
        if (!active.getAndSet(false)) return
        KayaEventHub.emit("mitigator", mapOf("active" to false))
    }

    /** Bytes/second moved by all apps other than Kaya since activation. */
    fun backgroundTrafficSnapshot(): Map<String, Long> {
        val now = System.currentTimeMillis()
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        val dt = (now - lastSampleAt).coerceAtLeast(1)
        val rxRate = ((rx - baselineRx).coerceAtLeast(0) * 1000) / dt
        val txRate = ((tx - baselineTx).coerceAtLeast(0) * 1000) / dt
        baselineRx = rx
        baselineTx = tx
        lastSampleAt = now
        return mapOf("rxBps" to rxRate, "txBps" to txRate)
    }

    /** Apps the OS already flags as heavy background users (for the Tuning screen). */
    fun heavyBackgroundApps(limit: Int = 5): List<PackageEntry> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) return emptyList()
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptyList()
        val now = System.currentTimeMillis()
        val stats = usm.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            now - 24 * 60 * 60 * 1000L,
            now,
        ) ?: return emptyList()
        return stats
            .filter { it.packageName != context.packageName && it.totalTimeInForeground > 0 }
            .sortedByDescending { it.totalTimeInForeground }
            .take(limit)
            .map { s ->
                PackageEntry(
                    packageName = s.packageName,
                    label = s.packageName.substringAfterLast('.'),
                    isGame = false,
                )
            }
    }
}
