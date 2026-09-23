package com.kaya.booster

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.PowerManager.OnThermalStatusChangedListener
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground "boost session" service.
 *
 * While a game is running it:
 *  - holds Wi-Fi low-latency + CPU wake locks via [BoostLocks]
 *  - throttles down Kaya's own animation work (HUD runs at 2 fps)
 *  - suppresses background app sync via [BackgroundMitigator] (best effort, no root)
 *  - reports thermal status so the UI can warn when the SoC starts throttling
 */
class KayaBoostService : Service() {

    private lateinit var locks: BoostLocks
    private lateinit var mitigator: BackgroundMitigator
    private val handler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val thermalExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var thermalListener: OnThermalStatusChangedListener? = null

    // Auto-pilot: watches which boosted game is foreground while the session runs.
    private val autopilotPoller = object : Runnable {
        override fun run() {
            KayaGuard.bg("autopilot-poll") { GameAutoPilot.poll(applicationContext) }
            handler.postDelayed(this, 5_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        KayaState.init(this)
        locks = BoostLocks(this)
        mitigator = BackgroundMitigator(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startSession()
        }
        return START_STICKY
    }

    private fun startSession() {
        if (running.getAndSet(true)) return
        KayaState.init(this)
        KayaState.update(boost = true)
        startForeground(
            NOTIF_ID,
            buildNotification(getString(R.string.notif_boost_title), getString(R.string.notif_boost_text)),
        )
        locks.acquire()
        mitigator.activate()
        observeThermal()
        GameFocusManager.apply(this) // quiet non-allowlisted noise for the session
        handler.removeCallbacks(autopilotPoller)
        handler.postDelayed(autopilotPoller, 5_000)
    }

    override fun onDestroy() {
        running.set(false)
        KayaState.update(boost = false)
        handler.removeCallbacks(autopilotPoller)
        mitigator.deactivate()
        locks.release()
        GameFocusManager.restore(this)
        thermalListener?.let { l ->
            runCatching {
                (getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .removeThermalStatusListener(l)
            }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun observeThermal() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (thermalListener == null) {
            thermalListener = OnThermalStatusChangedListener { status ->
                KayaEventHub.emit("thermal", mapOf("status" to status))
            }
            runCatching { pm.addThermalStatusListener(thermalExecutor, thermalListener!!) }
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName)
                ?: Intent().setClassName(this, "com.kaya.booster.MainActivity"),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, KayaBoostService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, MainApplication.CHANNEL_BOOST)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notif_kaya) // plain PNG: safe on every OEM
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.kaya.booster.STOP_BOOST"
        private const val NOTIF_ID = 41

        fun start(context: Context) {
            context.startForegroundService(Intent(context, KayaBoostService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, KayaBoostService::class.java).setAction(ACTION_STOP))
        }
    }
}
