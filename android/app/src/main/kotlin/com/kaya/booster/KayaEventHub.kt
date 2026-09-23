package com.kaya.booster

import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.EventChannel

/** Simple bridge to push native events (HUD samples, errors, state) into Flutter. */
object KayaEventHub {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var sink: EventChannel.EventSink? = null

    fun attach(sink: EventChannel.EventSink?) {
        this.sink = sink
    }

    fun emit(type: String, payload: Map<String, Any?>) {
        val event = mapOf("type" to type, "data" to payload)
        val deliver = { s: EventChannel.EventSink ->
            // A dead Flutter engine (app swiped away) throws here; swallow it —
            // emitting an event must never be able to kill a service thread.
            runCatching { s.success(event) }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            sink?.let(deliver)
        } else {
            mainHandler.post {
                sink?.let(deliver)
            }
        }
    }
}
