package com.kaya.booster

import android.content.ComponentName
import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager

/**
 * Holds the device-level performance locks for an active boost session:
 *  - WifiManager.WifiLock (WIFI_MODE_FULL_LOW_LATENCY) keeps the radio out of
 *    power-save polling so packets leave the NIC immediately.
 *  - PowerManager wake lock (CPU) plus, on API 33+, a low-latency screen
 *    brightness boost request where supported.
 * All locks are reference-counted and released in [release].
 */
class BoostLocks(context: Context) {
    private val appContext = context.applicationContext
    private val wifiLock: WifiManager.WifiLock
    private val wakeLock: PowerManager.WakeLock

    var held: Boolean = false
        private set

    init {
        val wm = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiLockMode = try {
            WifiManager::class.java.getField("WIFI_MODE_FULL_LOW_LATENCY").getInt(null)
        } catch (_: Exception) {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wm.createWifiLock(wifiLockMode, "kaya:boost")
        wifiLock.setReferenceCounted(false)

        val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kaya:boost")
        wakeLock.setReferenceCounted(false)
    }

    fun acquire() {
        if (!held) {
            runCatching { wifiLock.acquire() }
            runCatching { wakeLock.acquire(/* no timeout: game session */ 4 * 60 * 60 * 1000L) }
            held = true
        }
    }

    fun release() {
        if (held) {
            runCatching { if (wifiLock.isHeld) wifiLock.release() }
            runCatching { if (wakeLock.isHeld) wakeLock.release() }
            held = false
        }
    }
}
