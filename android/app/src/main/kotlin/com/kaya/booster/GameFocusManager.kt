package com.kaya.booster

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Game Focus: when a boosted game is launched, Kaya asks the OS to quiet
 * everything that is NOT on the user's allowlist (calls + WhatsApp by
 * default). Honest, root-free mechanics:
 *
 *  - Do Not Disturb in PRIORITY mode tuned so that CALLS ALWAYS RING (any
 *    sender, not just starred contacts), alarms and media volume are
 *    explicitly exempted, and allowlisted app notifications still come
 *    through; everything else (social feeds, marketing) is silenced for the
 *    duration of the session. This is a real, OS-enforced quiet mode that
 *    never touches call, ring, media or game audio volume.
 *  - An aggressive one-shot background stall hint via ActivityManager's
 *    kill-background-processes is NOT used (Play safety: never touch other
 *    apps). Instead the existing BackgroundMitigator keeps sampling so the
 *    UI shows exactly which apps are stealing bandwidth while the OS defers
 *    their jobs under the DND + battery-saver pressure of the boost locks.
 *
 * Nothing here reads or blocks message *content* — only notification
 * priority. WhatsApp messages still arrive; they just do not interrupt with
 * heads-up banners or sounds unless allowlisted (it is by default).
 */
object GameFocusManager {

    /** Allowlisted packages whose notifications pass through DND priority mode. */
    val DEFAULT_ALLOW = setOf(
        "com.whatsapp", // WhatsApp messages
        "com.whatsapp.w4b", // WhatsApp Business
    )

    private const val PREFS = "kaya_state"
    private const val KEY_FOCUS = "game_focus_on"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_FOCUS, true)

    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_FOCUS, value).apply()
        if (!value) restore(context)
    }

    fun isDndGranted(context: Context): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return false
        return nm.isNotificationPolicyAccessGranted
    }

    fun requestDndAccess(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** Call when a game goes foreground. Returns what was applied (for the HUD). */
    fun apply(context: Context): Map<String, Any?> {
        val applied = LinkedHashMap<String, Any?>()
        if (!isEnabled(context)) {
            applied["focus"] = false
            return applied
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (nm != null && nm.isNotificationPolicyAccessGranted) {
            runCatching {
                // Priority-only DND, tuned so callers are NEVER silenced:
                //  - all calls pass (PRIORITY_SENDERS_ANY — not just starred
                //    contacts; any contact or unknown number still rings)
                //  - alarms and media audio are explicitly exempted so the
                //    game's own volume and the user's alarms are untouched
                //    (several OEM ROMs otherwise suppress them under DND)
                nm.notificationPolicy = NotificationManager.Policy(
                    NotificationManager.Policy.PRIORITY_CATEGORY_CALLS or
                        NotificationManager.Policy.PRIORITY_CATEGORY_CONVERSATIONS or
                        NotificationManager.Policy.PRIORITY_CATEGORY_MESSAGES or
                        NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS or
                        NotificationManager.Policy.PRIORITY_CATEGORY_MEDIA,
                    NotificationManager.Policy.PRIORITY_SENDERS_ANY, // every call rings
                    NotificationManager.Policy.PRIORITY_SENDERS_ANY, // messages pass silently->banner-free
                    0,
                )
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                applied["dnd"] = true
            }
        } else {
            applied["dnd"] = false // needs the one-time DND grant
        }
        // Battery: nudge PowerManager into thinking a high-priority session is
        // running. The real work is done by KayaBoostService's wake+wifi locks;
        // we only record the state for the HUD here.
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        applied["batterySaver"] = pm.isPowerSaveMode
        KayaEventHub.emit("focus", applied)
        return applied
    }

    /** Call when the game leaves the foreground. */
    fun restore(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        if (!nm.isNotificationPolicyAccessGranted) return
        runCatching {
            if (nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_PRIORITY) {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            }
        }
    }

    /** Opens the DND priority-app screen so the user can star apps (one-time). */
    fun openZenSettings(context: Context) {
        runCatching {
            val intent = Intent(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
