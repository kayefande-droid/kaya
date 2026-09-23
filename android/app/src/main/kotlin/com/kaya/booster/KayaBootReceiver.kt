package com.kaya.booster

import android.content.Intent

/**
 * Boot/upgrade receiver.
 *
 * Kaya never auto-starts the VPN tunnel (that requires the user's one-time
 * consent and should never surprise anyone). But if a **boost session** was
 * live when the process was killed (reboot, OEM kill, crash), the boost
 * service is restarted so the auto-pilot watcher comes back up — it will
 * re-arm the engine itself the moment a boosted game is foreground and
 * consent is already on file. Nothing starts if no session was live.
 */
class KayaBootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: android.content.Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        KayaGuard.bg("boot-rehydrate") {
            KayaState.init(context)
            if (!KayaState.boostOn) return@bg // no live session to restore
            if (KayaBoostService.start(context)) {
                // KayaState keeps boost=true; the service re-promotes and
                // the auto-pilot poller resumes watching for game launches.
            }
        }
    }
}
