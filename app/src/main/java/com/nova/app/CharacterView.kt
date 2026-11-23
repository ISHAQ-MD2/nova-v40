package com.nova.app

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Full-screen clean wallpaper + live Jarvis HUD + ambient particles.
 * No face or character cutout is drawn.
 */
class CharacterView(c: Context) : View(c) {
    var speaking = false
    var girl = true
    var onSwitch: (() -> Unit)? = null
    var fxMode = -1
        set(v) { field = v; rebuild() }

    /** Live telemetry readouts (filled by MainActivity) */
    var hudLeft: List<String> = emptyList()
    var hudRight: List<String> = emptyList()
    var hudOn = true
    var hudBat = 0
    var fast = false

    /** Android live wallpaper mode hides UI scrims & readout labels */
    var wallpaperMode = false

    // Clock: 0 off, 1 stacked, 2 outline, 3 wide. pos: 0 left, 1 center, 2 right
    var clockStyle = 1
    var clockPos = 1
    var clockWhite = false
    var clockSize = 100
    var forceNoClock = false
    private var layers: List<Layer>? = null

    private var theme: Theme = Themes.current(c)
    private var wall: Bitmap? = null
    private var wallBlur: Bitmap? = null
    private var bgBlurLevel = 0
    private var bottomUi = 0f
    private val d = c.resources.displayMetrics.density
    private val bm = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    private var t = 0f
    private var lastNs = 0L
    private var shown = true

    private class Pt {
        var x = 0f; var y = 0f; var vx = 0f; var vy = 0f; var s = 1f; var rot = 0f; var vr = 0f
        var ph = 0f; var kind = 0; var front = false; var col = 0; var lay: Layer? = null; var ch = 0
    }

    private val pts = ArrayList<Pt>()
    private val leaf = Path().apply { moveTo(0f, -1f); quadTo(0.95f, -0.15f, 0f, 1f); quadTo(-0.95f, -0.15f, 0f, -1f); close() }
    private val wr = RectF()

    init { setTheme(theme) }

    fun setTheme(th: Theme) {
        theme = th
        wall = Themes.wallpaper(context, th)
        rebuildBlur(); rebuild(); invalidate()
    }

    fun setBlur(bg: Int, @Suppress("UNUSED_PARAMETER") unused: Int = 0) {
        bgBlurLevel = bg.coerceIn(0, 100)
        rebuildBlur(); invalidate()
    }

    fun setInsets(@Suppress("UNUSED_PARAMETER") top: Int, bottom: Int) {
        bottomUi = bottom.toFloat(); invalidate()
    }

    fun emote(@Suppress("UNUSED_PARAMETER") name: String) {}
    fun emoteText(@Suppress("UNUSED_PARAMETER") t: String, @Suppress("UNUSED_PARAMETER") fromUser: Boolean = false) {}
    fun status(@Suppress("UNUSED_PARAMETER") text: String) {}

    private fun soft(b: Bitmap, level: Int): Bitmap {
        val k = 1f + level * 0.12f
        return Bitmap.createScaledBitmap(b, maxOf(6, (b.width / k).toInt()), maxOf(6, (b.height / k).toInt()), true)
    }

    private fun rebuildBlur() {
        wallBlur = if (bgBlurLevel > 0) wall?.let { soft(it, bgBlurLevel) } else null
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh); rebuild()
    }

    override fun onWindowVisibilityChanged(v: Int) {
        super.onWindowVisibilityChanged(v); shown = v == VISIBLE; if (shown) invalidate()
    }

    fun setAnim(l: List<Layer>?) { layers = l; rebuild() }

    fun applyPrefs() {
        val p = context.getSharedPreferences("nova", Context.MODE_PRIVATE)
        hudOn = if (wallpaperMode) p.getBoolean("wp_hud", true) else p.getBoolean("hud_on", true)
        clockStyle = p.getInt("clock_style", 1); clockPos = p.getInt("clock_pos", 1)
        clockWhite = p.getBoolean("clock_white", false); clockSize = p.getInt("clock_size", 100)
        fast = p.getBoolean("fast_mode", false)
        layers = Anim.current(context)
        fxMode = p.getInt("fx", -1)
        setBlur(p.getInt("bgblur", 30), 0)
    }

    private fun rebuild() {
        pts.clear()
        if (width == 0 || height == 0) return
        val ls = layers
        if (ls != null) {
            for (l in ls) repeat((l.count.coerceIn(0, 120) * (if (fast) 0.5f else 1f)).toInt()) { pts.add(spawn(l.kind, true, l)) }
            return
        }
        val fx = if (fxMode >= 0) fxMode else theme.fx
        if (fx == 3) return
        val mix = when (fx) {
            0 -> intArrayOf(0, 16, 2, 8, 3, 5)
            1 -> intArrayOf(1, 16, 2, 6, 3, 5)
            else -> intArrayOf(2, 22, 0, 5, 3, 6)
        }
        var i = 0
        while (i < mix.size) {
            repeat((mix[i + 1] * (if (fast) 0.5f else 1f)).toInt()) { pts.add(spawn(mix[i], true, null)) }
            i += 2
        }
    }

    private fun spawn(kind: Int, initial: Boolean, lay: Layer?): Pt {
        val q = Pt(); val W = width.toFloat(); val H = height.toFloat()
        val sp = (lay?.speed ?: 100) / 100f; val sz = (lay?.size ?: 100) / 100f; val al = (lay?.alpha ?: 100) / 100f
        val tint = if (lay != null && lay.color != 0) lay.color else theme.accent
        q.kind = kind; q.lay = lay; q.ph = Random.nextFloat() * 6.28f; q.front = Random.nextInt(100) < 35
        q.x = Random.nextFloat() * W; q.y = if (initial) Random.nextFloat() * H else -20f * d
        when (kind) {
            0 -> { q.s = (4f + Random.nextFloat() * 4f) * d * sz; q.vy = (28f + Random.nextFloat() * 40f) * d * sp; q.vx = (8f + Random.nextFloat() * 18f) * d * sp; q.vr = (Random.nextFloat() - 0.5f) * 150f; q.col = tint.withAlpha((190 * al).toInt()) }
            1 -> {
                q.s = (6f + Random.nextFloat() * 5f) * d * sz; q.vy = (30f + Random.nextFloat() * 45f) * d * sp; q.vx = (10f + Random.nextFloat() * 20f) * d * sp; q.vr = (Random.nextFloat() - 0.5f) * 170f
                q.col = if (lay != null && lay.color != 0) tint.withAlpha((210 * al).toInt()) else intArrayOf(Color.argb((210 * al).toInt(), 95, 191, 106), Color.argb((210 * al).toInt(), 139, 211, 107), Color.argb((210 * al).toInt(), 63, 165, 91))[Random.nextInt(3)]
            }
            2 -> { q.s = (3f + Random.nextFloat() * 5f) * d * sz; q.vy = -(4f + Random.nextFloat() * 16f) * d * sp; q.vx = (Random.nextFloat() - 0.5f) * 10f * d; q.col = Color.WHITE.withAlpha((255 * al).toInt()); if (!initial) q.y = H + 20f * d }
            3 -> { q.s = (8f + Random.nextFloat() * 18f) * d * sz; q.vy = -(3f + Random.nextFloat() * 9f) * d * sp; q.vx = (Random.nextFloat() - 0.5f) * 6f * d; q.col = tint.withAlpha((30 * al * 2).toInt().coerceAtMost(255)); if (!initial) q.y = H + 30f * d }
            4 -> { q.s = (1.6f + Random.nextFloat() * 2.4f) * d * sz; q.vy = (25f + Random.nextFloat() * 40f) * d * sp; q.vx = (Random.nextFloat() - 0.3f) * 14f * d; q.col = Color.WHITE.withAlpha((215 * al).toInt()) }
            5 -> { q.s = (12f + Random.nextFloat() * 14f) * d * sz; q.vy = (520f + Random.nextFloat() * 300f) * d * sp; q.vx = 60f * d * sp; q.col = tint.withAlpha((120 * al).toInt()) }
            6 -> { q.s = (1.2f + Random.nextFloat() * 2.2f) * d * sz; q.vy = 0f; q.vx = 0f; q.y = Random.nextFloat() * H; q.col = Color.WHITE.withAlpha((255 * al).toInt()) }
            7 -> {
                q.s = (1.6f + Random.nextFloat() * 2.6f) * d * sz; q.vy = -(35f + Random.nextFloat() * 60f) * d * sp; q.vx = (Random.nextFloat() - 0.5f) * 20f * d
                q.col = if (lay != null && lay.color != 0) tint.withAlpha((230 * al).toInt()) else Color.argb((230 * al).toInt(), 255, 150 + Random.nextInt(60), 40); if (!initial) q.y = H + 10f * d
            }
            8 -> { q.s = (6f + Random.nextFloat() * 12f) * d * sz; q.vy = -(14f + Random.nextFloat() * 28f) * d * sp; q.vx = (Random.nextFloat() - 0.5f) * 8f * d; q.col = tint.withAlpha((140 * al).toInt()); if (!initial) q.y = H + 20f * d }
            else -> { q.s = 11f * d * sz; q.vy = (60f + Random.nextFloat() * 90f) * d * sp; q.vx = 0f; q.ch = Random.nextInt(16); q.col = tint.withAlpha((170 * al).toInt()); q.x = (Random.nextInt(20) / 20f) * W + 6f * d }
        }
        return q
    }

    private fun step(dt: Float) {
        val W = width.toFloat(); val H = height.toFloat()
        for (i in pts.indices) {
            val q = pts[i]
            if (q.kind == 6) continue
            val wob = if (q.kind == 5 || q.kind == 9) 0f else sin(t * 1.1f + q.ph) * 14f * d
            q.x += (q.vx + wob) * dt; q.y += q.vy * dt; q.rot += q.vr * dt
            if (q.kind == 9 && (t * 6f).toInt() % 3 == 0) q.ch = (q.ch + 1) and 15
            if (q.y > H + 30 * d || q.y < -40 * d || q.x > W + 40 * d || q.x < -40 * d) pts[i] = spawn(q.kind, false, q.lay)
        }
    }

    private fun drawPts(c: Canvas, front: Boolean) {
        for (q in pts) {
            if (q.front != front) continue
            when (q.kind) {
                0 -> { c.save(); c.translate(q.x, q.y); c.rotate(q.rot); p.style = Paint.Style.FILL; p.color = q.col; c.drawOval(RectF(-q.s * 0.55f, -q.s, q.s * 0.55f, q.s), p); c.restore() }
                1 -> { c.save(); c.translate(q.x, q.y); c.rotate(q.rot); c.scale(q.s * 0.7f, q.s); p.style = Paint.Style.FILL; p.color = q.col; c.drawPath(leaf, p); c.restore() }
                2 -> {
                    val a = (90 + 150 * (sin(t * 2.2f + q.ph) * 0.5f + 0.5f)).toInt()
                    p.style = Paint.Style.STROKE; p.strokeWidth = 1.3f * d; p.color = Color.WHITE.withAlpha(a)
                    c.drawLine(q.x - q.s, q.y, q.x + q.s, q.y, p); c.drawLine(q.x, q.y - q.s, q.x, q.y + q.s, p)
                    p.style = Paint.Style.FILL; p.color = theme.accent.withAlpha(a / 2); c.drawCircle(q.x, q.y, q.s * 0.35f, p)
                }
                3, 4 -> { p.style = Paint.Style.FILL; p.color = q.col; c.drawCircle(q.x, q.y, q.s, p) }
                5 -> { p.style = Paint.Style.STROKE; p.strokeWidth = 1.3f * d; p.color = q.col; c.drawLine(q.x, q.y, q.x - q.vx * 0.03f, q.y - q.s, p) }
                6 -> { val a = (80 + 175 * (sin(t * 1.8f + q.ph) * 0.5f + 0.5f)).toInt(); p.style = Paint.Style.FILL; p.color = q.col.withAlpha(a); c.drawCircle(q.x, q.y, q.s, p) }
                7 -> {
                    val k = ((height - q.y) / height).coerceIn(0f, 1f)
                    p.style = Paint.Style.FILL; p.color = q.col.withAlpha(((q.col ushr 24) * (1f - k * 0.8f)).toInt()); c.drawCircle(q.x, q.y, q.s, p)
                    p.color = q.col.withAlpha(((q.col ushr 24) * 0.25f).toInt()); c.drawCircle(q.x, q.y, q.s * 2.6f, p)
                }
                8 -> {
                    p.style = Paint.Style.STROKE; p.strokeWidth = 1.2f * d; p.color = q.col; c.drawCircle(q.x + sin(t + q.ph) * 4f * d, q.y, q.s, p)
                    p.color = q.col.withAlpha(((q.col ushr 24) * 0.5f).toInt()); c.drawCircle(q.x + sin(t + q.ph) * 4f * d - q.s * 0.3f, q.y - q.s * 0.3f, q.s * 0.2f, p)
                }
                else -> { tp.textAlign = Paint.Align.CENTER; tp.textSize = q.s; tp.color = q.col; c.drawText("0123456789ABCDEF".get(q.ch and 15).toString(), q.x, q.y, tp) }
            }
        }
    }

    private fun drawHud(c: Canvas, W: Float, H: Float, cx: Float, cy: Float) {
        val a = theme.accent
        p.style = Paint.Style.STROKE
        val base = W * 0.5f
        val pulse = if (speaking) 1.04f + 0.04f * sin(t * 10f) else 1f
        p.strokeWidth = 1.2f * d; p.color = a.withAlpha(70); c.drawCircle(cx, cy, base * 0.74f * pulse, p)
        p.strokeWidth = 2.2f * d; p.color = a.withAlpha(120)
        val ring2 = RectF(cx - base * 0.92f, cy - base * 0.92f, cx + base * 0.92f, cy + base * 0.92f)
        for (k in 0 until 4) c.drawArc(ring2, t * 38f + k * 90f, 52f, false, p)
        p.strokeWidth = 1.4f * d; p.color = a.withAlpha(95)
        val ring3 = RectF(cx - base * 1.1f, cy - base * 1.1f, cx + base * 1.1f, cy + base * 1.1f)
        for (k in 0 until 6) c.drawArc(ring3, -t * 24f + k * 60f, 28f, false, p)

        p.strokeWidth = 1f * d; p.color = a.withAlpha(80)
        val rin = base * 1.02f; val rout = base * 1.06f
        for (i in 0 until 72) {
            val ang = (i * 5f + t * 6f) * (PI / 180f).toFloat()
            val ln = if (i % 6 == 0) rout + 5 * d else rout
            c.drawLine(cx + cos(ang) * rin, cy + sin(ang) * rin, cx + cos(ang) * ln, cy + sin(ang) * ln, p)
        }

        c.save(); c.rotate(t * 70f, cx, cy)
        p.style = Paint.Style.FILL
        p.shader = SweepGradient(cx, cy, intArrayOf(0x00000000, a.withAlpha(0), a.withAlpha(70)), floatArrayOf(0f, 0.8f, 1f))
        c.drawCircle(cx, cy, base * 0.92f, p); p.shader = null
        c.restore()

        p.style = Paint.Style.FILL
        for (k in 0 until 3) {
            val ang = t * (0.9f + k * 0.4f) + k * 2.1f; val r = base * (0.74f + k * 0.18f)
            p.color = a.withAlpha(230); c.drawCircle(cx + cos(ang) * r, cy + sin(ang) * r, 3.2f * d, p)
            p.color = a.withAlpha(60); c.drawCircle(cx + cos(ang) * r, cy + sin(ang) * r, 8f * d, p)
        }

        tp.textSize = 10f * d; tp.textAlign = Paint.Align.CENTER
        val cols = if (fast) floatArrayOf() else floatArrayOf(14f * d, W - 14f * d, 34f * d, W - 34f * d)
        for ((ci, x) in cols.withIndex()) {
            val off = (t * (30f + ci * 11f) * d) % (H * 0.7f)
            for (j in 0 until 14) {
                val y = (off + j * 15f * d) % (H * 0.7f) + H * 0.08f
                val ch = "0123456789ABCDEF".get((j * 7 + ci * 3 + (t * 3f).toInt() * (j % 3 + 1)) and 15)
                tp.color = a.withAlpha((20 + (14 - j) * 6).coerceAtMost(130)); c.drawText(ch.toString(), x, y, tp)
            }
        }

        val sy = ((t * 0.16f) % 1f) * H
        if (!fast) {
            p.shader = LinearGradient(0f, sy - 40f * d, 0f, sy + 4f * d, a.withAlpha(0), a.withAlpha(70), Shader.TileMode.CLAMP)
            c.drawRect(0f, sy - 40f * d, W, sy + 4f * d, p); p.shader = null
        }

        p.style = Paint.Style.STROKE; p.strokeWidth = 2f * d; p.color = a.withAlpha(190)
        val m = 16f * d; val L = 22f * d; val topY = H * 0.115f
        c.drawLine(m, topY, m + L, topY, p); c.drawLine(m, topY, m, topY + L, p)
        c.drawLine(W - m, topY, W - m - L, topY, p); c.drawLine(W - m, topY, W - m, topY + L, p)
        if (wallpaperMode) { p.style = Paint.Style.FILL; return }

        tp.textSize = 10.5f * d; tp.color = a.withAlpha(230)
        tp.textAlign = Paint.Align.LEFT
        for ((i, s) in hudLeft.withIndex()) c.drawText(s, m + 6f * d, topY + L + (14 + i * 13) * d, tp)
        tp.textAlign = Paint.Align.RIGHT
        for ((i, s) in hudRight.withIndex()) c.drawText(s, W - m - 6f * d, topY + L + (14 + i * 13) * d, tp)
        drawGauges(c, W, topY + L + 3 * 13 * d + 50f * d)
        p.style = Paint.Style.FILL
    }

    private fun drawGauges(c: Canvas, W: Float, cy: Float) {
        val a = theme.accent; val r = 30f * d
        val cal = java.util.Calendar.getInstance()
        val sec = cal.get(java.util.Calendar.SECOND) + cal.get(java.util.Calendar.MILLISECOND) / 1000f
        val cxL = 16f * d + r + 6f * d; val cxR = W - cxL
        for (side in 0..1) {
            val cx = if (side == 0) cxL else cxR
            val rect = RectF(cx - r, cy - r, cx + r, cy + r)
            p.style = Paint.Style.FILL; p.color = Color.argb(120, 0, 0, 0); c.drawCircle(cx, cy, r, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 4f * d; p.color = a.withAlpha(55); c.drawCircle(cx, cy, r, p)
            p.strokeCap = Paint.Cap.ROUND; p.color = a.withAlpha(235)
            val sweep = if (side == 0) sec / 60f * 360f else hudBat.coerceIn(0, 100) / 100f * 360f
            c.drawArc(rect, -90f, sweep.coerceAtLeast(4f), false, p)
            p.strokeCap = Paint.Cap.BUTT
            tp.textAlign = Paint.Align.CENTER; tp.color = Color.WHITE
            if (side == 0) {
                tp.textSize = 12f * d; c.drawText(String.format("%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE)), cx, cy + 2f * d, tp)
                tp.textSize = 8.5f * d; tp.color = a.withAlpha(230)
                c.drawText(java.text.SimpleDateFormat("dd MMM", java.util.Locale.US).format(cal.time).uppercase(), cx, cy + 14f * d, tp)
            } else {
                tp.textSize = 13f * d; c.drawText("$hudBat%", cx, cy + 2f * d, tp)
                tp.textSize = 8.5f * d; tp.color = a.withAlpha(230); c.drawText("POWER", cx, cy + 14f * d, tp)
            }
        }
        p.style = Paint.Style.FILL
    }

    private fun drawClock(c: Canvas, W: Float, H: Float) {
        if (clockStyle == 0 || forceNoClock) return
        val cal = java.util.Calendar.getInstance()
        val hh = String.format("%02d", cal.get(java.util.Calendar.HOUR_OF_DAY)); val mm = String.format("%02d", cal.get(java.util.Calendar.MINUTE))
        val col = if (clockWhite) Color.WHITE else theme.accent
        val f = clockSize / 100f
        val tf = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        val dateTxt = java.text.SimpleDateFormat("EEEE, dd MMMM", java.util.Locale.US).format(cal.time).uppercase()
        val x: Float; val al: Paint.Align
        when (clockPos) { 0 -> { x = 14f * d; al = Paint.Align.LEFT }; 2 -> { x = W - 14f * d; al = Paint.Align.RIGHT }; else -> { x = W / 2f; al = Paint.Align.CENTER } }
        tp.typeface = tf; tp.textAlign = al
        val big: Float; val y: Float
        if (clockStyle == 3) {
            big = minOf(W * 0.30f, H * 0.12f) * f
            y = H * 0.15f + big
            drawBig(c, "$hh:$mm", x, y, big, col, false, tp)
            tp.textSize = 13f * d; tp.color = col.withAlpha(220); c.drawText(dateTxt, x, y - big - 6f * d, tp)
        } else {
            big = minOf(W * 0.62f, H * 0.19f) * f
            y = H * 0.13f + big * 0.86f
            tp.textSize = 13f * d; tp.color = col.withAlpha(220); c.drawText(dateTxt, x, y - big * 0.86f - 6f * d, tp)
            drawBig(c, hh, x, y, big, col, clockStyle == 2, tp)
            drawBig(c, mm, x, y + big * 0.92f, big, col, clockStyle == 2, tp)
        }
        tp.typeface = Typeface.MONOSPACE
    }

    private fun drawBig(c: Canvas, text: String, x: Float, y: Float, size: Float, col: Int, outline: Boolean, paint: Paint) {
        paint.textSize = size
        if (outline) { paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.6f * d; paint.color = col.withAlpha(235); c.drawText(text, x, y, paint) }
        else { paint.style = Paint.Style.FILL; paint.color = col.withAlpha(235); c.drawText(text, x, y, paint) }
        paint.style = Paint.Style.FILL
    }

    override fun onDraw(c: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 0.033f else ((now - lastNs) / 1e9f).coerceAtMost(0.1f)
        lastNs = now; t += dt
        val W = width.toFloat(); val H = height.toFloat()
        c.drawColor(theme.bg)
        val w = wall
        val bw = (w?.width ?: 700).toFloat(); val bh = (w?.height ?: 1599).toFloat()
        val sc = maxOf(W / bw, H / bh)
        val dw = bw * sc; val dh = bh * sc
        val dx = (W - dw) / 2f; val dy = 0f
        wr.set(dx, dy, dx + dw, dy + dh)

        val bgSrc = wallBlur ?: w
        if (bgSrc != null) c.drawBitmap(bgSrc, null, wr, bm)
        if (bgBlurLevel > 0) {
            p.style = Paint.Style.FILL; p.color = Color.argb((bgBlurLevel * 0.9f).toInt().coerceAtMost(95), 0, 0, 0)
            c.drawRect(wr, p)
        }

        p.style = Paint.Style.FILL
        if (!wallpaperMode) {
            p.shader = LinearGradient(0f, 0f, 0f, H * 0.2f, Color.argb(150, 0, 0, 0), 0, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, W, H * 0.2f, p); p.shader = null
        }

        step(dt)
        if (hudOn) drawHud(c, W, H, W / 2f, dy + dh * 0.30f)
        drawClock(c, W, H)
        drawPts(c, false)

        if (!wallpaperMode) {
            val ui = if (bottomUi > 0f) bottomUi else H * 0.34f
            val s0 = H - ui - H * 0.10f; val s1 = H - ui * 0.55f
            p.style = Paint.Style.FILL
            p.shader = LinearGradient(0f, s0, 0f, s1, theme.bg.withAlpha(0), theme.bg.withAlpha(236), Shader.TileMode.CLAMP)
            c.drawRect(0f, s0, W, H, p); p.shader = null
            p.color = theme.bg.withAlpha(236); c.drawRect(0f, s1, W, H, p)
        }

        drawPts(c, true)
        if (shown) postInvalidateDelayed(if (fast) 55L else 33L)
    }
}
