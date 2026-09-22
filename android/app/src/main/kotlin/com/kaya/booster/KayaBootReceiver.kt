package com.kaya.booster

import android.content.Intent

/** Boot receiver: nothing to restore (services are user-started), kept for completeness. */
class KayaBootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: android.content.Context, intent: Intent) {
        // Intentionally empty: Kaya never auto-starts tunnels or boosts after boot.
    }
}
