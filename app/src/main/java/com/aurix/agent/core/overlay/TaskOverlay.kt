package com.aurix.agent.core.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.aurix.agent.core.mission.MissionEntity
import com.aurix.agent.core.mission.MissionStatus
import com.aurix.agent.core.tools.device.AppState

/**
 * Small floating card shown over OTHER apps while a task runs ("Working · 2/3 · tapping Play").
 * Needs "Display over other apps". Never touchable or focusable, so it cannot block or steal taps.
 * Hidden while AURIX itself is on screen (the in-app card is used there).
 */
object TaskOverlay {
    private val main = Handler(Looper.getMainLooper())
    private var view: LinearLayout? = null
    private var title: TextView? = null
    private var body: TextView? = null
    private var hideAt = 0L
    private val hider = Runnable { remove() }

    fun update(ctx: Context, m: MissionEntity?) {
        val app = ctx.applicationContext
        main.post {
            try {
                if (m == null || AppState.inForeground || !Settings.canDrawOverlays(app)) { remove(); return@post }
                val active = m.status.isActive()
                val recentlyDone = !active && System.currentTimeMillis() - m.updatedAt < 6_000
                if (!active && !recentlyDone) { remove(); return@post }
                ensure(app)
                val steps = if (m.totalSteps > 0) "  ·  ${minOf(m.currentStep + 1, m.totalSteps)}/${m.totalSteps}" else ""
                title?.text = when (m.status) { MissionStatus.COMPLETED -> "✓ Task completed"; MissionStatus.FAILED -> "✕ Task failed"; else -> "● Working$steps" }
                title?.setTextColor(when (m.status) { MissionStatus.COMPLETED -> Color.parseColor("#39FF6A"); MissionStatus.FAILED -> Color.parseColor("#FF6B6B"); else -> Color.parseColor("#FF4D55") })
                body?.text = (if (active) m.currentAction.ifBlank { m.objective } else m.objective).take(80)
                main.removeCallbacks(hider)
                if (!active) main.postDelayed(hider, 5_000)
            } catch (_: Exception) { remove() }
        }
    }

    private fun ensure(app: Context) {
        if (view != null) return
        val dp = app.resources.displayMetrics.density
        val t = TextView(app).apply { textSize = 12f; setTypeface(typeface, android.graphics.Typeface.BOLD) }
        val b = TextView(app).apply { textSize = 14f; setTextColor(Color.WHITE); maxLines = 1 }
        val box = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (10 * dp).toInt())
            background = GradientDrawable().apply { setColor(Color.parseColor("#F214141A")); cornerRadius = 16 * dp; setStroke((1 * dp).toInt(), Color.parseColor("#55E5262B")) }
            addView(t); addView(b)
        }
        val lp = WindowManager.LayoutParams(
            (app.resources.displayMetrics.widthPixels * 0.88f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = (48 * dp).toInt() }
        (app.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(box, lp)
        view = box; title = t; body = b
    }

    private fun remove() {
        val v = view ?: return
        try { (v.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } catch (_: Exception) { }
        view = null; title = null; body = null
    }
}
