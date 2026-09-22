package com.kaya.booster

import android.app.Activity
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.Executors

/**
 * Native bridge: every platform capability Kaya's UI needs flows through the
 * "kaya/channel" MethodChannel and the "kaya/events" EventChannel.
 */
class MainActivity : FlutterActivity() {

    private val bg = Executors.newSingleThreadExecutor()
    private var channel: MethodChannel? = null
    private var pendingVpnResult: MethodChannel.Result? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
        channel?.setMethodCallHandler { call, result ->
            when (call.method) {
                "listInstalledApps" -> bg.execute { finishOnMain(result) { listInstalledApps() } }
                "launchApp" -> {
                    val pkg = call.argument<String>("package") ?: ""
                    val intent = packageManager.getLaunchIntentForPackage(pkg)
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                        finishOnMain(result) { true }
                    } else {
                        finishOnMain(result) { false }
                    }
                }
                "vpnConsent" -> {
                    val intent = VpnService.prepare(this)
                    if (intent == null) {
                        finishOnMain(result) { true }
                    } else {
                        pendingVpnResult = result
                        startActivityForResult(intent, REQUEST_VPN)
                    }
                }
                "vpnStart" -> {
                    if (VpnService.prepare(this) == null) {
                        KayaVpnService.start(this)
                        finishOnMain(result) { true }
                    } else {
                        finishOnMain(result) { false }
                    }
                }
                "vpnStop" -> {
                    KayaVpnService.stop(this)
                    finishOnMain(result) { true }
                }
                "boostStart" -> {
                    KayaBoostService.start(this)
                    finishOnMain(result) { true }
                }
                "boostStop" -> {
                    KayaBoostService.stop(this)
                    finishOnMain(result) { true }
                }
                "controllerBridgeStart" -> {
                    KayaMediaButtonService.start(this)
                    finishOnMain(result) { true }
                }
                "controllerBridgeStop" -> {
                    KayaMediaButtonService.stop(this)
                    finishOnMain(result) { true }
                }
                "isIgnoringBatteryOptimizations" ->
                    finishOnMain(result) {
                        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                        pm.isIgnoringBatteryOptimizations(packageName)
                    }
                "requestIgnoreBatteryOptimizations" -> {
                    runCatching {
                        startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                .setData(Uri.parse("package:$packageName")),
                        )
                    }
                    finishOnMain(result) { true }
                }
                "getPrivateDns" -> finishOnMain(result) {
                    val mode = Settings.Global.getString(contentResolver, PRIVATE_DNS_MODE) ?: "off"
                    val specifier = Settings.Global.getString(contentResolver, PRIVATE_DNS_SPECIFIER)
                    mapOf("mode" to mode, "specifier" to (specifier ?: ""))
                }
                "setPrivateDns" -> {
                    val hostname = call.argument<String>("hostname") ?: ""
                    finishOnMain(result) {
                        try {
                            Settings.Global.putString(contentResolver, PRIVATE_DNS_MODE, "hostname")
                            Settings.Global.putString(contentResolver, PRIVATE_DNS_SPECIFIER, hostname)
                            true
                        } catch (t: Throwable) {
                            false // needs WRITE_SECURE_SETTINGS (adb-grantable); README covers it
                        }
                    }
                }
                "hasUsageStats" -> finishOnMain(result) { hasUsageStats() }
                "grantUsageStats" -> {
                    runCatching {
                        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    }
                    finishOnMain(result) { true }
                }
                "accessibilityEnabled" -> finishOnMain(result) { isAccessibilityEnabled() }
                "openAccessibilitySettings" -> {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    finishOnMain(result) { true }
                }
                "listGamepads" -> finishOnMain(result) { KayaInputBridge.connectedGamepads() }
                "setControllerMapping" -> {
                    val raw = call.argument<List<Map<String, Any?>>>("mappings") ?: emptyList()
                    val mappings = raw.mapNotNull { m ->
                        val keyCode = (m["keyCode"] as? Number)?.toInt() ?: return@mapNotNull null
                        val x = (m["x"] as? Number)?.toFloat() ?: return@mapNotNull null
                        val y = (m["y"] as? Number)?.toFloat() ?: return@mapNotNull null
                        val hold = (m["holdMs"] as? Number)?.toLong() ?: 60L
                        KayaInputBridge.Mapping(keyCode, x, y, hold)
                    }
                    KayaInputBridge.arm(mappings)
                    if (mappings.isNotEmpty()) KayaMediaButtonService.start(this)
                    finishOnMain(result) { true }
                }
                "clearControllerMapping" -> {
                    KayaInputBridge.disarm()
                    KayaMediaButtonService.stop(this)
                    finishOnMain(result) { true }
                }
                "pingProbe" -> {
                    val host = call.argument<String>("host") ?: return@setMethodCallHandler
                    val port = call.argument<Int>("port") ?: 80
                    bg.execute {
                        val ms = TcpPinger.ping(host, port)
                        runOnUiThread { result.success(ms) }
                    }
                }
                "dnsProbe" -> {
                    val server = call.argument<String>("server") ?: return@setMethodCallHandler
                    val domain = call.argument<String>("domain") ?: "www.activision.com"
                    bg.execute {
                        val ms = probeResolver(server, domain)
                        runOnUiThread { result.success(ms) }
                    }
                }
                "getResolvers" -> finishOnMain(result) {
                    SteeringRules.RESOLVERS.map { mapOf("name" to it.name, "primary" to it.primary) }
                }
                "getGameHosts" -> finishOnMain(result) { SteeringRules.GAME_HOSTS.toList() }
                "getSteeringWinners" -> finishOnMain(result) { SteeringRules.allWinners() }
                "clearDnsCache" -> {
                    DnsResponder.clearCache()
                    SteeringRules.clearWinners()
                    finishOnMain(result) { true }
                }
                "thermalStatus" -> finishOnMain(result) {
                    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                    pm.currentThermalStatus
                }
                "toast" -> {
                    val msg = call.argument<String>("message") ?: ""
                    runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
                    result.success(true)
                }
                else -> result.notImplemented()
            }
        }

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, EVENTS).setStreamHandler(
            object : EventChannel.StreamHandler {
                override fun onListen(args: Any?, events: EventChannel.EventSink?) {
                    KayaEventHub.attach(events)
                }

                override fun onCancel(args: Any?) {
                    KayaEventHub.attach(null)
                }
            },
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_VPN) {
            pendingVpnResult?.let { r ->
                val granted = resultCode == Activity.RESULT_OK
                runOnUiThread { r.success(granted) }
            }
            pendingVpnResult = null
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    // ------------------------------------------------------------- helpers

    private inline fun finishOnMain(result: MethodChannel.Result, compute: () -> Any?) {
        val value = try {
            compute()
        } catch (t: Throwable) {
            KayaEventHub.emit("error", mapOf("where" to "channel", "message" to (t.message ?: "?")))
            null
        }
        runOnUiThread { result.success(value) }
    }

    private fun listInstalledApps(): List<Map<String, Any?>> {
        val pm = packageManager
        val out = mutableListOf<Map<String, Any?>>()
        for (appInfo in pm.getInstalledApplications(0)) {
            val pkg = appInfo.packageName
            if (pkg == null || pkg == packageName) continue
            if (pm.getLaunchIntentForPackage(pkg) == null) continue
            val isGame = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_IS_GAME) != 0 ||
                appInfo.category == android.content.pm.ApplicationInfo.CATEGORY_GAME
            val version = runCatching { pm.getPackageInfo(pkg, 0).versionName }.getOrNull()
            val label = runCatching { pm.getApplicationLabel(appInfo).toString() }.getOrDefault(pkg)
            val entry = HashMap<String, Any?>(4)
            entry["packageName"] = pkg
            entry["label"] = label
            entry["isGame"] = isGame
            entry["version"] = version ?: ""
            out.add(entry)
        }
        out.sortBy { (it["label"] as String).lowercase() }
        return out
    }

    @Suppress("DEPRECATION")
    private fun hasUsageStats(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.contains("$packageName/", ignoreCase = true)
    }

    companion object {
        const val CHANNEL = "kaya/channel"
        const val EVENTS = "kaya/events"
        const val REQUEST_VPN = 2101
        const val PRIVATE_DNS_MODE = "private_dns_mode"
        const val PRIVATE_DNS_SPECIFIER = "private_dns_specifier"
    }
}
