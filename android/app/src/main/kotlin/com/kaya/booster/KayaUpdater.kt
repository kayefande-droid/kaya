package com.kaya.booster

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app updater against GitHub Releases (no third-party deps, no servers of
 * ours — it literally reads the repo's public releases API).
 *
 * Flow: [check] reports current vs latest + the APK asset URL → [install]
 * downloads to cache/updates/ with progress events → hands the APK to the
 * system installer via FileProvider. Play-protect-safe: the user always sees
 * the standard "install this update?" confirmation, signed with Kaya's key.
 */
object KayaUpdater {

    private const val RELEASES_API =
        "https://api.github.com/repos/kayefande-droid/kaya/releases/latest"
    private const val CONNECT_TIMEOUT = 8_000
    private const val READ_TIMEOUT = 12_000

    data class Check(
        val latestVersion: String,
        val currentVersion: String,
        val updateAvailable: Boolean,
        val apkUrl: String?,
        val notes: String?,
    ) {
        fun toMap(): Map<String, Any?> = mapOf(
            "latestVersion" to latestVersion,
            "currentVersion" to currentVersion,
            "updateAvailable" to updateAvailable,
            "apkUrl" to apkUrl,
            "notes" to notes,
        )
    }

    fun installedVersion(context: Context): String = try {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        pi.versionName ?: "?"
    } catch (_: Throwable) {
        "?"
    }

    /** Blocks; call off the main thread. Returns null on any network problem. */
    fun check(context: Context): Check? {
        val current = installedVersion(context)
        val latest = KayaGuard.guard("update-check") {
            val conn = URL(RELEASES_API).openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            try {
                if (conn.responseCode != 200) return@guard null
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = org.json.JSONObject(body)
                val tag = json.optString("tag_name").removePrefix("v")
                var apkUrl: String? = null
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        val name = a.optString("name")
                        // Prefer arm64, fall back to universal.
                        if (name.equals("Kaya-arm64.apk", true) || name.equals("Kaya.apk", true)) {
                            apkUrl = a.optString("browser_download_url")
                            if (name.equals("Kaya-arm64.apk", true)) break
                        }
                    }
                }
                Check(
                    latestVersion = tag,
                    currentVersion = current,
                    updateAvailable = isNewer(current, tag),
                    apkUrl = apkUrl,
                    notes = json.optString("body").take(600).ifEmpty { null },
                )
            } finally {
                conn.disconnect()
            }
        }
        return latest
    }

    /** True when [latest] is a different, higher dotted version than [current]. */
    fun isNewer(current: String, latest: String): Boolean {
        fun parts(v: String) = v.removePrefix("v").split('.', '-', '+').mapNotNull {
            it.toIntOrNull()
        }
        val c = parts(current)
        val l = parts(latest)
        for (i in 0 until maxOf(c.size, l.size)) {
            val ci = c.getOrElse(i) { 0 }
            val li = l.getOrElse(i) { 0 }
            if (li != ci) return li > ci
        }
        return false
    }

    /** Downloads [url] and hands it to the system installer. Off-main-thread. */
    fun install(context: Context, url: String) {
        KayaGuard.bg("update-install") {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val file = File(dir, "kaya-update.apk")
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = 30_000
            try {
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(32 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                KayaEventHub.emit(
                                    "update",
                                    mapOf("phase" to "progress", "pct" to ((done * 100) / total).toInt()),
                                )
                            }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            KayaEventHub.emit("update", mapOf("phase" to "installing"))
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
