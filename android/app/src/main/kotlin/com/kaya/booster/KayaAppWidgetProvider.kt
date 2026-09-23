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
 * Home-screen widget: session state at a glance, one tap to arm or disarm.
 *
 * - Tap anywhere → toggles the full session (engine + boost locks), same
 *   semantics as the Home screen's arm/disarm.
 * - One pending-intent layout, so the toggling UI renders instantly while the
 *   services settle; the real state renders right after.
 * - State comes from [KayaState] (prefs-backed), so it stays honest across
 *   process restarts, boot, and OTA.
 *
 * Honest expectation: the ping line is Kaya's own TCP handshake sample taken
 * at arm time — the widget runs no background poller (battery first).
 */
class KayaAppWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent) // keeps APPWIDGET_UPDATE working
        if (intent.action == ACTION_TOGGLE) {
            val wantOn = intent.getBooleanExtra(EXTRA_WANT_ON, !KayaState.storedEngineOn(context))
            // Optimistic UI immediately (the whole point of the delayed layout)…
            render(context, requestedOn = wantOn)
            // …then flip the real session. VPN consent is dialog-driven, so
            // onboarding flows through the app; here we switch what we can.
            val vpnPrepared = runCatching {
                android.net.VpnService.prepare(context) == null
            }.getOrDefault(false)
            if (wantOn) {
                if (vpnPrepared) KayaVpnService.start(context)
                KayaBoostService.start(context)
                KayaState.update(boost = true)
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
    }

    companion object {
        const val ACTION_TOGGLE = "com.kaya.booster.WIDGET_TOGGLE"
        const val EXTRA_WANT_ON = "want_on"
        private const val WIDGET_SETTLE_MS = 900L

        /** Renders the true state on every placed widget. */
        fun refreshAll(context: Context) = render(context)

        /** Renders the requested toggle UI on every placed widget, then settles. */
        fun render(context: Context, requestedOn: Boolean? = null) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, KayaAppWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val busy = requestedOn != null
            val effective: Boolean = requestedOn ?: KayaState.storedEngineOn(context)
            for (id in ids) {
                manager.updateAppWidget(id, buildViews(context, isBusy = busy, isOn = effective))
            }
            if (requestedOn != null) {
                Handler(Looper.getMainLooper()).postDelayed(
                    { render(context) },
                    WIDGET_SETTLE_MS,
                )
            }
        }

        private fun buildViews(context: Context, isBusy: Boolean, isOn: Boolean): RemoteViews {
            val engineOn = if (isBusy) isOn else KayaState.storedEngineOn(context)
            val boostOn = if (isBusy) isOn else KayaState.storedBoostOn(context)
            val effectiveOn = engineOn || boostOn

            val views = RemoteViews(context.packageName, R.layout.kaya_widget)

            // --- headline ---------------------------------------------------
            val (titleRes, subRes) = when {
                effectiveOn -> R.string.widget_title_armed to R.string.widget_sub_engaged
                boostOn -> R.string.widget_title_boost to R.string.widget_sub_boost_only
                else -> R.string.widget_title_standby to R.string.widget_sub_off
            }
            views.setTextViewText(R.id.widget_title, context.getString(titleRes))
            views.setTextViewText(R.id.widget_subtitle, context.getString(subRes))

            // --- lane chip (whole bar is one pending-intent layout) ---------
            views.setInt(
                R.id.widget_chip,
                "setBackgroundResource",
                if (effectiveOn) R.drawable.kaya_widget_chip_on else R.drawable.kaya_widget_chip_off,
            )

            // --- status dot -------------------------------------------------
            val dotRes = when {
                isBusy -> R.drawable.kaya_widget_dot_busy
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
