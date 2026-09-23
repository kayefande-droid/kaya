package com.kaya.booster

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * Foreground service that keeps the controller-mapping pipeline alive while a
 * game with no native controller support is in the foreground. The actual
 * input interception happens in [KayaAccessibilityService]; this service only
 * keeps the process at foreground priority so Android does not kill the
 * mapping mid-match.
 */
class KayaMediaButtonService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            else -> {
                val open = PendingIntent.getActivity(
                    this, 0,
                    packageManager.getLaunchIntentForPackage(packageName)
                        ?: Intent().setClassName(this, "com.kaya.booster.MainActivity"),
                    PendingIntent.FLAG_IMMUTABLE,
                )
                val stop = PendingIntent.getService(
                    this, 2,
                    Intent(this, KayaMediaButtonService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE,
                )
                val notif: Notification = Notification.Builder(this, MainApplication.CHANNEL_BOOST)
                    .setContentTitle("Kaya Controller Bridge")
                    .setContentText("Controller mapping is active. Tap to stop.")
                    .setSmallIcon(R.drawable.ic_notif_kaya)
                    .setOngoing(true)
                    .setContentIntent(open)
                    .addAction(Notification.Action.Builder(null, "Stop", stop).build())
                    .build()
                try {
                    if (Build.VERSION.SDK_INT >= 29) {
                        startForeground(NOTIF_ID, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                    } else {
                        startForeground(NOTIF_ID, notif)
                    }
                } catch (t: Throwable) {
                    KayaGuard.append(this, "bridge-fg", t.message ?: "?")
                    runCatching { startForeground(NOTIF_ID, notif) }
                        .onFailure { stopSelf() }
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.kaya.booster.STOP_CONTROLLER"
        private const val NOTIF_ID = 43

        fun start(context: android.content.Context): Boolean = runCatching {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(Intent(context, KayaMediaButtonService::class.java))
            } else {
                context.startService(Intent(context, KayaMediaButtonService::class.java))
            }
            true
        }.getOrDefault(false)

        fun stop(context: android.content.Context) {
            runCatching {
                context.startService(
                    Intent(context, KayaMediaButtonService::class.java).setAction(ACTION_STOP),
                )
            }
        }
    }
}
