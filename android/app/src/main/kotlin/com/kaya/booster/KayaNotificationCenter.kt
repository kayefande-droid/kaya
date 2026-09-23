package com.kaya.booster

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification

/**
 * On-device notification center: mirrors active notifications into a small
 * ring buffer with read/unread state, surfaced in the app. Content never
 * leaves the device. The listener service feeds it; the Tuning screen shows
 * the list and lets the user dismiss entries (real dismissal via
 * cancelNotification, which requires the notification-listener grant).
 */
object KayaNotificationCenter {

    data class Entry(
        val key: String,
        val pkg: String,
        val appLabel: String,
        val title: String,
        val text: String,
        val postedAt: Long,
        val ongoing: Boolean,
    ) {
        var read: Boolean = false
    }

    private const val MAX_ENTRIES = 60
    private const val MAX_READ_KEYS = 200

    private val entries = LinkedHashMap<String, Entry>() // insertion-ordered
    private val readKeys = LinkedHashSet<String>()
    private var labelCache = HashMap<String, String>()
    private var listener: KayaNotificationListener? = null

    fun onConnected(service: KayaNotificationListener) {
        listener = service
        KayaEventHub.emit("notif", mapOf("connected" to true))
    }

    fun onDisconnected() {
        listener = null
        KayaEventHub.emit("notif", mapOf("connected" to false))
    }

    @Synchronized
    fun ingest(context: Context, sbn: StatusBarNotification) {
        if (sbn.packageName == context.packageName) return
        val notif = sbn.notification ?: return
        if (notif.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = notif.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val entry = Entry(
            key = sbn.key ?: return,
            pkg = sbn.packageName,
            appLabel = labelFor(context, sbn.packageName),
            title = title,
            text = text,
            postedAt = sbn.postTime,
            ongoing = sbn.isOngoing,
        )
        entry.read = entry.key in readKeys
        entries.remove(entry.key) // re-insert at newest position
        entries[entry.key] = entry
        while (entries.size > MAX_ENTRIES) {
            entries.remove(entries.keys.first())
        }
        KayaEventHub.emit(
            "notif",
            mapOf(
                "kind" to "posted",
                "key" to entry.key,
                "pkg" to entry.pkg,
                "appLabel" to entry.appLabel,
                "title" to entry.title,
                "text" to entry.text,
                "postedAt" to entry.postedAt,
                "ongoing" to entry.ongoing,
                "read" to entry.read,
            ),
        )
    }

    @Synchronized
    fun remove(key: String) {
        entries.remove(key)
        KayaEventHub.emit("notif", mapOf("kind" to "removed", "key" to key))
    }

    @Synchronized
    fun list(): List<Map<String, Any?>> = entries.values.reversed().map {
        mapOf(
            "key" to it.key,
            "pkg" to it.pkg,
            "appLabel" to it.appLabel,
            "title" to it.title,
            "text" to it.text,
            "postedAt" to it.postedAt,
            "ongoing" to it.ongoing,
            "read" to it.read,
        )
    }

    @Synchronized
    fun unreadCount(): Int = entries.values.count { !it.read }

    @Synchronized
    fun markRead(key: String) {
        entries[key]?.read = true
        readKeys.add(key)
        while (readKeys.size > MAX_READ_KEYS) readKeys.remove(readKeys.first())
    }

    @Synchronized
    fun markAllRead() {
        val now = System.currentTimeMillis()
        for (e in entries.values) {
            e.read = true
            readKeys.add(e.key)
        }
        while (readKeys.size > MAX_READ_KEYS) readKeys.remove(readKeys.first())
    }

    /** Real dismissal of a notification (needs the listener grant). */
    fun clear(context: Context?, key: String) {
        listener?.let { runCatching { it.cancelNotification(key) } }
        remove(key)
    }

    fun labelFor(context: Context, pkg: String): String {
        labelCache[pkg]?.let { return it }
        val label = runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg.substringAfterLast('.'))
        if (labelCache.size > 300) labelCache.clear()
        labelCache[pkg] = label
        return label
    }
}
