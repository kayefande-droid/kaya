package com.kaya.booster

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Floating live monitor: a tiny pill docked to the LEFT edge over the game,
 * showing the real measured handshake latency every 2 seconds (TcpPinger to
 * Cloudflare's anycast edge — the same honest probe the app's HUD uses; no
 * simulated numbers). Tap the pill to expand it into a mini card (ping,
 * jitter, engine + boost state); tap again to collapse; the ✕ on the card
 * removes the bubble for this session. The pill can be dragged vertically
 * along the left edge so it never covers critical game HUD elements.
 *
 * Needs the SYSTEM_ALERT_WINDOW permission (already in the manifest); on
 * Android 6+ the user grants "Display over other apps" once in Settings —
 * MainActivity exposes the deep link and Tuning surfaces the status.
 */
object KayaLiveBubble {

    private const val EXPAND_MS = 2200L // auto-collapse after inactivity

    private var wm: WindowManager? = null
    private var root: LinearLayout? = null
    private var card: LinearLayout? = null
    private var pillText: TextView? = null
    private var cardPing: TextView? = null
    private var cardState: TextView? = null
    private var expanded = false
    private var shown = false
    private var pingMs: Int? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val poller = object : Runnable {
        override fun run() {
            KayaGuard.bg("bubble-ping") {
                val ms = TcpPinger.ping("cloudflare.com", 443)
                if (ms != null) {
                    pingMs = ms
                    KayaState.update(ping = ms)
                }
            }
            render()
            mainHandler.postDelayed(this, 2000)
        }
    }

    fun canDraw(context: Context): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            android.provider.Settings.canDrawOverlays(context)
        } else true

    @Synchronized
    fun show(context: Context, gameLabel: String? = null) {
        if (shown || !canDraw(context)) return
        val windowManager =
            context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        try {
            buildUi(context, windowManager, gameLabel)
            shown = true
            mainHandler.removeCallbacks(poller)
            mainHandler.post(poller)
        } catch (t: Throwable) {
            KayaGuard.append(context, "bubble-show", t.message ?: "?")
        }
    }

    @Synchronized
    fun hide(context: Context? = null) {
        if (!shown) return
        shown = false
        mainHandler.removeCallbacks(poller)
        try {
            wm?.removeView(root)
        } catch (_: Throwable) {
            // view already detached
        }
        root = null
        card = null
        pillText = null
        cardPing = null
        cardState = null
        expanded = false
    }

    fun isShowing(): Boolean = shown

    private fun buildUi(context: Context, windowManager: WindowManager, gameLabel: String?) {
        wm = windowManager
        val density = context.resources.displayMetrics.density

        fun dp(v: Int) = (v * density).toInt()

        // ---- pill (collapsed state) ------------------------------------
        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = pillBg()
            elevation = dp(4).toFloat()
        }
        val dot = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                marginEnd = dp(6)
            }
            background = circleBg(0xFF00E56A.toInt())
        }
        val pillLabel = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            text = "— ms"
        }
        pill.addView(dot)
        pill.addView(pillLabel)
        pillText = pillLabel

        // ---- card (expanded state) -------------------------------------
        val cardLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = cardBg()
            elevation = dp(6).toFloat()
            visibility = View.GONE
        }
        val title = TextView(context).apply {
            setTextColor(0xFF00E56A.toInt())
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.1f
            text = "KAYA LIVE${gameLabel?.let { " · $it" } ?: ""}"
        }
        val ping = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            text = "— ms"
        }
        val state = TextView(context).apply {
            setTextColor(0xFF9BA3A0.toInt())
            textSize = 11f
            text = ""
        }
        val close = TextView(context).apply {
            setTextColor(0xFFFF5A5A.toInt())
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            text = "✕  close monitor"
            setPadding(0, dp(8), 0, 0)
            setOnClickListener {
                hide(context)
                android.widget.Toast.makeText(
                    context,
                    "Live monitor closed",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        }
        cardLayout.addView(title)
        cardLayout.addView(ping)
        cardLayout.addView(state)
        cardLayout.addView(close)
        cardPing = ping
        cardState = state
        card = cardLayout

        // ---- container --------------------------------------------------
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(pill)
            addView(cardLayout)
        }
        root = container

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            x = dp(10)
            y = 0
        }

        // drag along the left edge + tap to expand/collapse
        var downY = 0f
        var startPy = 0
        var moved = false
        container.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = ev.rawY
                    startPy = params.y
                    moved = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (ev.rawY - downY).toInt()
                    if (kotlin.math.abs(dy) > dp(6)) moved = true
                    params.y = startPy + dy
                    runCatching { wm?.updateViewLayout(container, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) toggleExpand(context)
                    true
                }
                else -> false
            }
        }

        windowManager.addView(container, params)
    }

    private fun toggleExpand(context: Context) {
        expanded = !expanded
        card?.visibility = if (expanded) View.VISIBLE else View.GONE
        render()
        if (expanded) {
            mainHandler.removeCallbacks(autoCollapse)
            mainHandler.postDelayed(autoCollapse, EXPAND_MS)
        } else {
            mainHandler.removeCallbacks(autoCollapse)
        }
    }

    private val autoCollapse = Runnable {
        if (expanded) {
            expanded = false
            card?.visibility = View.GONE
        }
    }

    private fun render() {
        mainHandler.post {
            val ms = pingMs
            val short = when {
                ms == null -> "— ms"
                ms < 60 -> "$ms ms"
                else -> "$ms ms"
            }
            pillText?.text = short
            cardPing?.text = short
            cardState?.text = buildString {
                append(if (KayaState.engineOn) "engine ON" else "engine off")
                append(" · ")
                append(if (KayaState.boostOn) "locks ON" else "locks off")
            }
        }
    }

    private fun pillBg() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 40f
        setColor(0xE60A0A0C.toInt())
        setStroke(2, 0xFF00E56A.toInt())
    }

    private fun cardBg() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 28f
        setColor(0xF20A0A0C.toInt())
        setStroke(2, 0xFF26262F.toInt())
    }

    private fun circleBg(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }
}
