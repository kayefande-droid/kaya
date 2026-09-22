package com.kaya.booster

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Key-event interceptor used by the controller-mapping feature.
 * When a mapping profile is armed, controller buttons are consumed here and
 * translated into on-screen taps at mapped coordinates by [KayaTouchMapper].
 * Games that hide controllers from their input pipeline never see the events.
 */
class KayaAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        KayaInputBridge.onAccessibilityConnected(this)
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        KayaInputBridge.onAccessibilityDisconnected()
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Window content is never inspected; we only listen for key events.
    }

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        return KayaInputBridge.interceptKeyEvent(event)
    }
}
