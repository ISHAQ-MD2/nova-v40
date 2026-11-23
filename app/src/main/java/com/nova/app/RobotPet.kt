package com.nova.app

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieDrawable
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * Optional 3D/4D Robot Companion (Lottie-based + animated speech overlay).
 * Idles smoothly (blink / float) and speeds up / pulses when NOVA speaks.
 */
object RobotPet {
    private var view: FrameLayout? = null
    private var innerRobot: RobotView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var wm: WindowManager? = null
    private val ui = Handler(Looper.getMainLooper())
    private var tempShown = false

    fun enabled(ctx: Context) = ctx.getSharedPreferences("nova", Context.MODE_PRIVATE).getBoolean("robot", true)
    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences("nova", Context.MODE_PRIVATE).edit().putBoolean("robot", on).apply()
        if (on) show(ctx) else hide()
    }

    /** Creates a compact Lottie robot companion view for embedding directly inside MainActivity. */
    fun createInAppCompanion(ctx: Context): LottieAnimationView {
        return LottieAnimationView(ctx).apply {
            setAnimation(R.raw.nova_robot_lottie)
            repeatCount = LottieDrawable.INFINITE
            speed = 1.0f
            playAnimation()
            contentDescription = "NOVA companion robot"
        }
    }

    fun show(ctx: Context) {
        val app = ctx.applicationContext
        ui.post {
            if (view != null || !Settings.canDrawOverlays(app)) return@post
            val m = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val wrap = FrameLayout(app)
            val lottie = createInAppCompanion(app)
            val v = RobotView(app, lottie)
            wrap.addView(lottie, FrameLayout.LayoutParams(v.lottiePx, v.lottiePx, Gravity.BOTTOM or Gravity.END))
            wrap.addView(v, FrameLayout.LayoutParams(v.ww, v.hh))

            val p = WindowManager.LayoutParams(
                v.ww, v.hh, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            p.gravity = Gravity.TOP or Gravity.START
            val dm = app.resources.displayMetrics
            p.x = dm.widthPixels - v.ww
            p.y = dm.heightPixels / 2
            try {
                m.addView(wrap, p)
                view = wrap; innerRobot = v; lp = p; wm = m
                v.attach(m, p, wrap)
            } catch (_: Exception) {}
        }
    }

    fun hide() {
        ui.post {
            innerRobot?.detach()
            view?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
            view = null; innerRobot = null; lp = null
        }
    }

    fun say(ctx: Context, text: String, ms: Long = 9000L) {
        val app = ctx.applicationContext
        ui.post {
            if (view == null) {
                if (!Settings.canDrawOverlays(app)) return@post
                tempShown = !enabled(app)
                show(app)
            }
            ui.postDelayed({
                innerRobot?.say(text, ms)
                if (tempShown) ui.postDelayed({ if (tempShown) { tempShown = false; hide() } }, ms + 1500)
            }, 250)
        }
    }

    private class RobotView(val ctx: Context, private val lottie: LottieAnimationView) : View(ctx) {
        private fun dp(x: Float) = x * ctx.resources.displayMetrics.density
        val ww = dp(230f).toInt()
        val hh = dp(140f).toInt()
        val lottiePx = dp(76f).toInt()
        private val sw = ctx.resources.displayMetrics.widthPixels
        private val sh = ctx.resources.displayMetrics.heightPixels
        private var wmr: WindowManager? = null
        private var lpr: WindowManager.LayoutParams? = null
        private var hostRoot: FrameLayout? = null
        private val h = Handler(Looper.getMainLooper())
        private var bubble = ""
        private var bubbleUntil = 0L
        private var tx = 0f; private var ty = 0f
        private var nextPlan = 0L
        private var talkingUntil = 0L
        private val sp: (Boolean) -> Unit = { s ->
            talkingUntil = if (s) Long.MAX_VALUE else System.currentTimeMillis() + 300
            h.post { lottie.speed = if (s) 2.1f else 1.0f }
        }
        private var alive = true

        private val tick = object : Runnable {
            override fun run() {
                if (!alive) return
                step(); invalidate(); h.postDelayed(this, 33)
            }
        }

        fun attach(m: WindowManager, l: WindowManager.LayoutParams, root: FrameLayout) {
            wmr = m; lpr = l; hostRoot = root
            Vol.addSpeakListener(sp)
            h.post(tick)
            tx = l.x.toFloat(); ty = l.y.toFloat()
        }

        fun detach() {
            alive = false
            h.removeCallbacksAndMessages(null)
            Vol.removeSpeakListener(sp)
        }

        fun say(text: String, ms: Long) {
            bubble = text; bubbleUntil = System.currentTimeMillis() + ms
            tx = (sw / 2f - ww / 2f); ty = sh * 0.38f
            nextPlan = bubbleUntil + 2000
        }

        private fun plan(now: Long) {
            val top = dp(28f)
            tx = Random.nextFloat() * (sw - ww).coerceAtLeast(1)
            ty = top + Random.nextFloat() * (sh - hh - top * 2).coerceAtLeast(1f)
            nextPlan = now + 6000 + Random.nextLong(7000)
        }

        private fun step() {
            val now = System.currentTimeMillis()
            val l = lpr ?: return
            val root = hostRoot ?: return
            if (now > nextPlan) plan(now)
            val dx = tx - l.x; val dy = ty - l.y
            if (abs(dx) > 3 || abs(dy) > 3) {
                l.x += (dx * 0.06f).toInt().let { if (it == 0) Integer.signum(dx.toInt()) else it }
                l.y += (dy * 0.06f).toInt().let { if (it == 0) Integer.signum(dy.toInt()) else it }
            }
            val speaking = now < talkingUntil || (now < bubbleUntil && Vol.speaking)
            val pulse = if (speaking) 1.08f + 0.06f * sin(now / 120.0).toFloat() else 1.0f
            lottie.scaleX = pulse; lottie.scaleY = pulse
            try { wmr?.updateViewLayout(root, l) } catch (_: Exception) {}
        }

        override fun onDraw(c: Canvas) {
            val now = System.currentTimeMillis()
            if (now < bubbleUntil && bubble.isNotEmpty()) {
                val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = dp(12.5f); typeface = Typeface.DEFAULT_BOLD }
                val maxW = ww - dp(90f)
                val lines = ArrayList<String>(); var cur = ""
                for (w in bubble.split(" ")) {
                    val tst = if (cur.isEmpty()) w else "$cur $w"
                    if (tp.measureText(tst) > maxW) { lines.add(cur); cur = w } else cur = tst
                }
                if (cur.isNotEmpty()) lines.add(cur)
                val shown = lines.take(4)
                val bw = shown.maxOf { tp.measureText(it) } + dp(18f)
                val bh = shown.size * dp(16f) + dp(14f)
                val r = RectF(dp(4f), hh - bh - dp(8f), dp(4f) + bw, hh - dp(8f))
                val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(225, 11, 20, 40) }
                c.drawRoundRect(r, dp(14f), dp(14f), bg)
                val st = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.4f); color = Color.parseColor("#00E5FF") }
                c.drawRoundRect(r, dp(14f), dp(14f), st)
                shown.forEachIndexed { i, s -> c.drawText(s, r.left + dp(9f), r.top + dp(18f) + i * dp(16f), tp) }
            }
        }
    }
}
