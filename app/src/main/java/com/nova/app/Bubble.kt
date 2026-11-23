package com.nova.app

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.sin

/** Floating NOVA button (WhatsApp wale green bubble jaisa). Tap = NOVA ko bulao / sula do. Lamba dabao = "screen dekho". Kheench kar kahin bhi rakho. */
class Bubble(private val ctx: Context, private val onTap: () -> Unit, private val onLong: () -> Unit,
             private val items: List<Pair<String, () -> Unit>> = emptyList()) {
    private fun dp(x: Int) = (x * ctx.resources.displayMetrics.density).toInt()
    private var menu: View? = null
    private val mh = Handler(Looper.getMainLooper())
    private fun closeMenu() { menu?.let { try { wm.removeView(it) } catch (_: Exception) {} }; menu = null; mh.removeCallbacksAndMessages(null) }
    private fun toggleMenu(px: Int, py: Int) {
        if (menu != null) { closeMenu(); return }
        val dm = ctx.resources.displayMetrics
        val box = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#F01A2B4D"), Color.parseColor("#F00A1226"))).apply { cornerRadius = dp(22).toFloat(); setStroke(dp(1), Color.parseColor("#00E5FF")) }; setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        items.forEach { (label, act) ->
            box.addView(android.widget.TextView(ctx).apply {
                text = label; textSize = 15f; setTextColor(Color.WHITE); setPadding(dp(16), dp(11), dp(16), dp(11))
                setOnClickListener { closeMenu(); act() }
            })
        }
        val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = (px - dp(190)).coerceIn(dp(8), (dm.widthPixels - dp(240)).coerceAtLeast(dp(8)))
        lp.y = (py + size).coerceAtMost((dm.heightPixels - dp(400)).coerceAtLeast(dp(40)))
        try { wm.addView(box, lp); menu = box } catch (_: Exception) {}
        mh.postDelayed({ closeMenu() }, 7000)
    }

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: BView? = null
    private val size = (58 * ctx.resources.displayMetrics.density).toInt()

    fun show() {
        if (view != null || !Settings.canDrawOverlays(ctx)) return
        val lp = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.START
        val dm = ctx.resources.displayMetrics
        val pr = ctx.getSharedPreferences("nova", Context.MODE_PRIVATE)
        lp.x = pr.getInt("bx", dm.widthPixels - size); lp.y = pr.getInt("by", dm.heightPixels / 3)
        val v = BView(lp)
        try { wm.addView(v, lp); view = v } catch (_: Exception) {}
    }

    fun hide() { closeMenu(); view?.let { try { wm.removeView(it) } catch (_: Exception) {} }; view = null }
    fun setActive(a: Boolean) { view?.active = a; view?.postInvalidate() }

    private inner class BView(val lp: WindowManager.LayoutParams) : View(ctx) {
        @Volatile var active = false
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val h = Handler(Looper.getMainLooper())
        private var downX = 0f; private var downY = 0f; private var sx = 0; private var sy = 0
        private var moved = false; private var long = false
        private val longRun = Runnable { long = true; onLong() }

        override fun onDraw(c: Canvas) {
            val t = System.currentTimeMillis() / 1000f
            val r = width / 2f
            val pulse = if (active) 1f + 0.07f * sin(t * 6f) else 1f + 0.02f * sin(t * 2f)
            val c1 = if (active) "#FF4FB8" else "#00E5FF"; val c2 = if (active) "#7B1FA2" else "#0B3C8C"
            // glow
            p.style = Paint.Style.FILL; p.shader = RadialGradient(r, r, r, intArrayOf(Color.parseColor(c1) and 0x00FFFFFF or 0x66000000, 0x00000000), null, Shader.TileMode.CLAMP)
            c.drawCircle(r, r, r, p); p.shader = null
            // body
            p.shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), Color.parseColor(c1), Color.parseColor(c2), Shader.TileMode.CLAMP)
            c.drawCircle(r, r, r * 0.74f * pulse, p); p.shader = null
            p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.07f; p.color = Color.WHITE; p.alpha = 200
            c.drawCircle(r, r, r * 0.74f * pulse, p); p.alpha = 255
            // sound-wave arcs when active
            if (active) { p.strokeWidth = r * 0.05f; p.color = Color.parseColor(c1); p.alpha = 160
                c.drawArc(RectF(r * 0.08f, r * 0.08f, r * 1.92f, r * 1.92f), (t * 120f) % 360f, 70f, false, p); p.alpha = 255 }
            p.style = Paint.Style.FILL
            p.color = Color.WHITE; p.textSize = r * 0.95f; p.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD); p.textAlign = Paint.Align.CENTER
            c.drawText("N", r, r + p.textSize * 0.35f, p)
            postInvalidateOnAnimation()
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; sx = lp.x; sy = lp.y; moved = false; long = false; h.postDelayed(longRun, 700) }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.rawX - downX) > 12 || abs(e.rawY - downY) > 12) { moved = true; h.removeCallbacks(longRun) }
                    if (moved) { lp.x = sx + (e.rawX - downX).toInt(); lp.y = sy + (e.rawY - downY).toInt(); try { wm.updateViewLayout(this, lp) } catch (_: Exception) {} }
                }
                MotionEvent.ACTION_UP -> {
                    h.removeCallbacks(longRun)
                    if (moved) ctx.getSharedPreferences("nova", Context.MODE_PRIVATE).edit().putInt("bx", lp.x).putInt("by", lp.y).apply()
                    else if (!long) { if (items.isNotEmpty()) toggleMenu(lp.x, lp.y) else onTap() }
                }
                MotionEvent.ACTION_CANCEL -> h.removeCallbacks(longRun)
            }
            return true
        }
    }
}
