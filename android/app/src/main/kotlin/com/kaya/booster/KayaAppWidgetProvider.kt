package com.kaya.booster

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews

/**
 * Home-screen widget: session state at a glance, one tap to arm or disarm,
 * one tap to refresh the steered route.
 *
 * - Tap the lane chip → toggles the full session (engine + boost locks), same
 *   semantics as the Home screen's arm/disarm.
 * - Tap REFRESH ROUTE → clears the learned resolver winners + DNS cache, then
 *   takes one fresh honest TCP handshake sample (the widget runs no poller).
 * - State comes from [KayaState] (prefs-backed), so it stays honest across
 *   process restarts, boot, and OTA. When auto-pilot is holding a session for
 *   a boosted game, the subtitle names that game.
 *
 * Every tap is a real PendingIntent broadcast to this provider — verified on
 * emulator (previous build rendered but had no pending intents wired, so taps
 * did nothing).
 */
class KayaAppWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent) // keeps APPWIDGET_UPDATE working
        when (intent.action) {
            ACTION_TOGGLE -> handleToggle(context, intent)
            ACTION_REFRESH -> handleRefresh(context)
        }
    }

    private fun handleToggle(context: Context, intent: Intent) {
        val wantOn = intent.getBooleanExtra(EXTRA_WANT_ON, !KayaState.storedEngineOn(context))
        // Optimistic UI immediately (the whole point of the delayed layout)…
        render(context, requestedOn = wantOn)
        // …then flip the real session. VPN consent is dialog-driven, so
        // onboarding flows through the app; here we switch what we can.
        val vpnPrepared = runCatching {
            android.net.VpnService.prepare(context) == null
        }.getOrDefault(false)
        if (wantOn) {
            if (vpnPrepared && !KayaState.storedEngineOn(context)) {
                if (KayaVpnService.start(context)) KayaState.update(engine = true)
            }
            if (!KayaState.storedBoostOn(context) && KayaBoostService.start(context)) {
                KayaState.update(boost = true)
            }
            if (!vpnPrepared) {
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.widget_toast_open_app),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
                runCatching {
                    context.startActivity(
                        Intent(context, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        } else {
            KayaVpnService.stop(context)
            KayaBoostService.stop(context)
            KayaState.update(engine = false, boost = false)
        }
    }

    private fun handleRefresh(context: Context) {
        // Optimistic: pending state while the probes run (never on the main
        // thread — this receiver runs on it).
        render(context, refreshPending = true)
        KayaGuard.bg("widget-refresh") {
            // Forget the learned winners so the next lookups race again and
            // re-learn the fastest anycast edge from right here, right now.
            SteeringRules.clearWinners()
            DnsResponder.clearCache()
            val ms = TcpPinger.ping("cloudflare.com", 443)
            if (ms != null) KayaState.update(ping = ms) else KayaState.touch()
            android.widget.Toast.makeText(
                context,
                context.getString(R.string.widget_refresh_toast),
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
        // Settle to the true state even if the probe returned nothing.
        Handler(Looper.getMainLooper()).postDelayed({ render(context) }, 1_600)
    }

    companion object {
        const val ACTION_TOGGLE = "com.kaya.booster.WIDGET_TOGGLE"
        const val ACTION_REFRESH = "com.kaya.booster.WIDGET_REFRESH"
        const val EXTRA_WANT_ON = "want_on"
        private const val WIDGET_SETTLE_MS = 900L

        private fun pi(context: Context, action: String, requestCode: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                requestCode,
                Intent(context, KayaAppWidgetProvider::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        /** Renders the true state on every placed widget. */
        fun refreshAll(context: Context) = render(context)

        /** Renders the requested toggle UI on every placed widget, then settles. */
        fun render(context: Context, requestedOn: Boolean? = null, refreshPending: Boolean = false) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, KayaAppWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val busy = requestedOn != null
            val effective: Boolean = requestedOn ?: KayaState.storedEngineOn(context)
            for (id in ids) {
                manager.updateAppWidget(id, buildViews(context, isBusy = busy, isOn = effective, refreshPending = refreshPending))
            }
            if (requestedOn != null) {
                Handler(Looper.getMainLooper()).postDelayed(
                    { render(context) },
                    WIDGET_SETTLE_MS,
                )
            }
        }

        private fun buildViews(context: Context, isBusy: Boolean, isOn: Boolean, refreshPending: Boolean): RemoteViews {
            val engineOn = if (isBusy || refreshPending) isOn else KayaState.storedEngineOn(context)
            val boostOn = if (isBusy) isOn else KayaState.storedBoostOn(context)
            val effectiveOn = engineOn || boostOn

            val views = RemoteViews(context.packageName, R.layout.kaya_widget)

            // --- headline ---------------------------------------------------
            // Auto-pilot session label: when the watcher is holding the lane
            // for a boosted game, say so (and name it) instead of plain "Armed".
            val activeGame = GameAutoPilot.activeGame()
            val (titleRes, subRes, subArg) = when {
                activeGame != null -> Triple(
                    R.string.widget_title_armed,
                    R.string.widget_sub_autopilot,
                    runCatching {
                        context.packageManager
                            .getApplicationLabel(context.packageManager.getApplicationInfo(activeGame, 0))
                            .toString()
                    }.getOrDefault(activeGame),
                )
                effectiveOn -> Triple(R.string.widget_title_armed, R.string.widget_sub_engaged, null)
                boostOn -> Triple(R.string.widget_title_boost, R.string.widget_sub_boost_only, null)
                else -> Triple(R.string.widget_title_standby, R.string.widget_sub_off, null)
            }
            views.setTextViewText(R.id.widget_title, context.getString(titleRes))
            views.setTextViewText(
                R.id.widget_subtitle,
                subArg?.let { context.getString(subRes, it) } ?: context.getString(subRes),
            )

            // --- lane chip (whole bar is one pending-intent layout) ---------
            views.setInt(
                R.id.widget_chip,
                "setBackgroundResource",
                if (effectiveOn) R.drawable.kaya_widget_chip_on else R.drawable.kaya_widget_chip_off,
            )
            views.setOnClickPendingIntent(R.id.widget_chip, pi(context, ACTION_TOGGLE, 10))

            // --- refresh row -------------------------------------------------
            views.setOnClickPendingIntent(R.id.widget_refresh, pi(context, ACTION_REFRESH, 11))
            views.setTextColor(
                R.id.widget_refresh,
                if (refreshPending) 0xFF00E56A.toInt() else 0xFF9BA3A0.toInt(),
            )

            // --- status dot -------------------------------------------------
            val dotRes = when {
                isBusy || refreshPending -> R.drawable.kaya_widget_dot_busy
                engineOn -> R.drawable.kaya_widget_dot_on
                else -> R.drawable.kaya_widget_dot_idle
            }
            views.setInt(R.id.widget_dot, "setBackgroundResource", dotRes)
            views.setTextViewText(R.id.widget_chip_label, context.getString(R.string.widget_chip_label))

            // --- ping line --------------------------------------------------
            val ping = KayaState.storedPing(context)
            views.setTextViewText(
                R.id.widget_ping,
                when {
                    engineOn && ping != null -> context.getString(R.string.widget_ping_value, ping)
                    engineOn -> context.getString(R.string.widget_ping_pending)
                    else -> context.getString(R.string.widget_ping_idle)
                },
            )
            return views
        }
    }
}
