package com.kaya.booster

import android.accessibilityservice.AccessibilityService
import android.view.InputDevice
import android.view.KeyEvent

/**
 * Routes controller key events (from the AccessibilityService interceptor)
 * to the active mapping profile, translating them into synthetic touches.
 * Also tracks which physical device events came from so mixed setups
 * (BT controller + touchscreen) behave predictably.
 */
object KayaInputBridge {

    data class Mapping(
        val keyCode: Int,
        val x: Float,
        val y: Float,
        val holdMs: Long = 60,
    )

    private var service: AccessibilityService? = null
    private var profile: List<Mapping> = emptyList()
    private var armed = false
    private val seenDevices = mutableSetOf<Int>()

    fun onAccessibilityConnected(service: AccessibilityService) {
        this.service = service
        KayaTouchMapper.attach(service)
        KayaEventHub.emit("accessibility", mapOf("connected" to true))
    }

    fun onAccessibilityDisconnected() {
        service = null
        KayaTouchMapper.attach(null)
        armed = false
        KayaEventHub.emit("accessibility", mapOf("connected" to false))
    }

    fun arm(newProfile: List<Mapping>) {
        profile = newProfile
        armed = profile.isNotEmpty()
    }

    fun disarm() {
        armed = false
        profile = emptyList()
    }

    val isArmed: Boolean get() = armed

    fun interceptKeyEvent(event: KeyEvent?): Boolean {
        if (event == null || !armed) return false
        val source = event.device
        if (source != null) {
            val isGamepad = (source.sources and InputDevice.SOURCE_GAMEPAD) != 0 ||
                (source.sources and InputDevice.SOURCE_JOYSTICK) != 0
            if (!isGamepad) return false
            seenDevices.add(source.id)
        }
        val action = when (event.action) {
            KeyEvent.ACTION_DOWN -> "down"
            KeyEvent.ACTION_UP -> "up"
            else -> return false
        }
        // Trigger the mapped touch on the initial press only.
        if (event.repeatCount > 0) return true
        val mapping = profile.firstOrNull { it.keyCode == event.keyCode } ?: return false
        KayaEventHub.emit(
            "controller",
            mapOf("keyCode" to event.keyCode, "action" to action, "x" to mapping.x, "y" to mapping.y),
        )
        KayaTouchMapper.tap(mapping.x, mapping.y)
        return true // consumed: the game never sees the controller event
    }

    fun connectedGamepads(): List<Map<String, Any?>> {
        val result = mutableListOf<Map<String, Any?>>()
        for (id in InputDevice.getDeviceIds()) {
            val dev = InputDevice.getDevice(id) ?: continue
            val isGamepad = (dev.sources and InputDevice.SOURCE_GAMEPAD) != 0 ||
                (dev.sources and InputDevice.SOURCE_JOYSTICK) != 0
            if (!isGamepad) continue
            val info = LinkedHashMap<String, Any?>()
            info["id"] = dev.id
            info["name"] = dev.name
            info["vendor"] = dev.vendorId
            info["product"] = dev.productId
            result.add(info)
        }
        return result
    }
}
