package com.kaya.booster

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Notification-listener service powering Kaya's in-app notification center.
 * Bound only with the user's explicit "Notification access" grant; every
 * record stays in device memory ([KayaNotificationCenter]).
 */
class KayaNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        KayaNotificationCenter.onConnected(this)
        runCatching {
            for (sbn in activeNotifications ?: emptyArray()) {
                KayaNotificationCenter.ingest(this, sbn)
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.let { KayaNotificationCenter.ingest(this, it) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val key = sbn?.key ?: return
        KayaNotificationCenter.remove(key)
    }

    override fun onDestroy() {
        KayaNotificationCenter.onDisconnected()
        super.onDestroy()
    }
}
