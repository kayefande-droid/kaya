package com.kaya.booster

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * In-app updater against GitHub Releases (no third-party deps, no servers of
 * ours — it literally reads the repo's public releases API).
 *
 * Flow: [check] reports current vs latest + the APK asset URL → [install]
 * downloads to cache/updates/, verifies the SHA-256 against the release's
 * SHA256SUMS.txt, and only then hands the APK to the system installer via
 * FileProvider. A mismatched, truncated or tampered file is deleted and never
 * offered for install — the update fails closed. Play-protect-safe: the user
 * always sees the standard "install this update?" confirmation, signed with
 * Kaya's key.
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

    /**
     * Downloads [url], verifies its SHA-256 against the matching entry in the
     * release's SHA256SUMS.txt, and only then hands it to the system installer.
     * Returns false when the checksum cannot be verified (fail closed: the
     * downloaded file is deleted, nothing is installed). Off-main-thread.
     */
    fun install(context: Context, url: String): Boolean {
        return KayaGuard.guard("update-install") {
            // Pre-flight 1: without "install unknown apps" for Kaya, the system
            // installer would bounce the handoff with a bare "App not installed"
            // — surface the real reason instead.
            val canInstall = if (Build.VERSION.SDK_INT >= 26) {
                context.packageManager.canRequestPackageInstalls()
            } else true
            if (!canInstall) {
                KayaEventHub.emit("update", mapOf("phase" to "failed", "reason" to "missing install permission"))
                return@guard false
            }

            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val file = File(dir, "kaya-update.apk")

            // Same-release manifest: releases/latest/download/<name> → SHA256SUMS.txt.
            val sumsUrl = url.substringBeforeLast('/') + "/SHA256SUMS.txt"

            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = 30_000
            try {
                val total = conn.contentLengthLong
                val digest = MessageDigest.getInstance("SHA-256")
                conn.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(32 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            digest.update(buf, 0, n)
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

            // --- verify before anything touches the package installer ---
            val actual = digest(file).lowercase()
            val expected = expectedSha(context, sumsUrl, url.substringAfterLast('/'))
            val verified = expected != null && expected.equals(actual, ignoreCase = true)
            if (!verified) {
                file.delete()
                KayaEventHub.emit(
                    "update",
                    mapOf(
                        "phase" to "failed",
                        "reason" to if (expected == null) "checksum unavailable" else "checksum mismatch",
                    ),
                )
                return@guard false
            }
            KayaEventHub.emit("update", mapOf("phase" to "verified"))

            // Pre-flight 2: never hand the installer an APK that would be a
            // downgrade (that is rejected with the same cryptic "App not
            // installed"). Read the versionCode straight out of the downloaded
            // APK and compare with the installed one.
            val downloadedCode = apkVersionCode(context, file)
            val installedCode = installedVersionCode(context)
            if (downloadedCode != null && downloadedCode < installedCode) {
                file.delete()
                KayaEventHub.emit(
                    "update",
                    mapOf(
                        "phase" to "failed",
                        "reason" to "downloaded build ($downloadedCode) is older than installed ($installedCode)",
                    ),
                )
                return@guard false
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
            true
        } ?: false
    }

    /** versionCode parsed from a downloaded APK's manifest; null if unreadable. */
    private fun apkVersionCode(context: Context, file: File): Long? = runCatching {
        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        if (info == null) {
            null
        } else if (Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }.getOrNull()

    fun installedVersionCode(context: Context): Long = try {
        val pi: PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    } catch (_: Throwable) {
        0L
    }

    /** SHA-256 of [file], streamed so an 18–48 MB APK never sits in memory. */
    private fun digest(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(32 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Expected SHA-256 for [assetName] parsed from a `sha256 *name*` manifest
     * (the sha256sum format this repo publishes). Null if unreachable/absent —
     * callers treat that as "cannot verify", never as "verify ok".
     */
    private fun expectedSha(context: Context, sumsUrl: String, assetName: String): String? {
        return KayaGuard.guard("update-checksum") {
            val conn = URL(sumsUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            try {
                if (conn.responseCode != 200) return@guard null
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                text.lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.endsWith(assetName, ignoreCase = true) }
                    ?.substringBefore(' ')
                    ?.takeIf { it.length == 64 && it.all { c -> c.isDigit() || c in 'a'..'f' || c in 'A'..'F' } }
            } finally {
                conn.disconnect()
            }
        }
    }
}
