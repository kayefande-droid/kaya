package com.kaya.booster

import android.accessibilityservice.AccessibilityService
import android.graphics.Path
import android.os.Build

/**
 * Dispatches synthetic taps/dispatches gestures at mapped screen coordinates
 * using the accessibility API (dispatchGesture), which works on games that
 * reject plain overlay-injected MotionEvents.
 */
object KayaTouchMapper {

    private var service: AccessibilityService? = null

    fun attach(service: AccessibilityService?) {
        this.service = service
    }

    fun tap(x: Float, y: Float): Boolean {
        val svc = service ?: return false
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 0.1f, y + 0.1f)
        }
        val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 40)
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(stroke)
            .build()
        return svc.dispatchGesture(gesture, null, null)
    }

    fun hold(x: Float, y: Float, durationMs: Long): Boolean {
        val svc = service ?: return false
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 0.1f, y + 0.1f)
        }
        val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(
            path, 0, durationMs.coerceAtMost(60_000L),
        )
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(stroke)
            .build()
        return svc.dispatchGesture(gesture, null, null)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val svc = service ?: return false
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(stroke)
            .build()
        return svc.dispatchGesture(gesture, null, null)
    }

    val available: Boolean get() = service != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
}
