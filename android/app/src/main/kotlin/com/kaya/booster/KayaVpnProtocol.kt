package com.kaya.booster

import android.content.Context
import android.net.VpnService

/** Consent + status helpers for the on-device DNS engine. */
object VpnGate {

    /** Returns true when the user has already granted VPN consent. */
    fun hasConsent(context: Context): Boolean = VpnService.prepare(context) == null

    /** True if another VPN is currently active and would block Kaya's engine. */
    fun isOtherVpnActive(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            ?: return false
        return runCatching {
            val method = cm.javaClass.getMethod("isAlwaysOnVpnPackageLockedForUser", Int::class.javaPrimitiveType)
            (method.invoke(cm, 0) as? String) != null
        }.getOrDefault(false)
    }
}
