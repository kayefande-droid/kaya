package com.kaya.booster

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Fail-open: createNotificationChannel round-trips to system_server
        // over binder, and a wedged/low-storage system can throw there. A
        // crash in Application.onCreate kills every launch of the app, so
        // channels are best-effort only — promoteToForeground() in the
        // services already tolerates a missing channel (OEMs throw there
        // too) and Android falls back to the default channel behavior.
        runCatching {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_BOOST,
                    getString(R.string.notif_channel_boost),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_VPN,
                    getString(R.string.notif_channel_vpn),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    companion object {
        const val CHANNEL_BOOST = "kaya_boost"
        const val CHANNEL_VPN = "kaya_vpn"
    }
}
