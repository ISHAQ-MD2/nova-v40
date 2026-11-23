package com.nova.app

import android.content.Context
import android.graphics.*
import android.text.TextUtils
import android.view.View
import androidx.core.graphics.PathParser
import kotlin.math.sin

/** Material-style line icons (24x24) - koi image/HTML nahi, seedha Canvas par. */
object Icons {
    private val data = mapOf(
        "lock" to "M18,8h-1V6c0,-2.76 -2.24,-5 -5,-5S7,3.24 7,6v2H6c-1.1,0 -2,0.9 -2,2v10c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V10c0,-1.1 -0.9,-2 -2,-2z M12,17c-1.1,0 -2,-0.9 -2,-2s0.9,-2 2,-2 2,0.9 2,2 -0.9,2 -2,2z M15.1,8H8.9V6c0,-1.71 1.39,-3.1 3.1,-3.1 1.71,0 3.1,1.39 3.1,3.1v2z",
        "download" to "M19,9h-4V3H9v6H5l7,7 7,-7z M5,18v2h14v-2H5z",
        "folder" to "M10,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8c0,-1.1 -0.9,-2 -2,-2h-8l-2,-2z",
        "translate" to "M12.87,15.07l-2.54,-2.51 0.03,-0.03c1.74,-1.94 2.98,-4.17 3.71,-6.53H17V4h-7V2H8v2H1v1.99h11.17C11.5,7.92 10.44,9.75 9,11.35 8.07,10.32 7.3,9.19 6.69,8h-2c0.73,1.63 1.73,3.17 2.98,4.56l-5.09,5.02L4,19l5,-5 3.11,3.11 0.76,-2.04z M18.5,10h-2L12,22h2l1.12,-3h4.75L21,22h2l-4.5,-12z M15.88,17l1.62,-4.33L19.12,17h-3.24z",
        "image" to "M21,19V5c0,-1.1 -0.9,-2 -2,-2H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2z M8.5,13.5l2.5,3.01L14.5,12l4.5,6H5l3.5,-4.5z",
        "plus" to "M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z",
        "bolt" to "M11,21h-1l1,-7H7.5c-0.58,0 -0.57,-0.32 -0.38,-0.66 0.19,-0.34 0.05,-0.08 0.07,-0.12C8.48,10.94 10.42,7.54 13,3h1l-1,7h3.5c0.49,0 0.56,0.33 0.47,0.51l-0.07,0.15C12.96,17.55 11,21 11,21z",
        "mic" to "M12,14c1.66,0 3,-1.34 3,-3V5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3z M17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11H5c0,3.41 2.72,6.23 6,6.72V21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z",
        "mic_off" to "M19,11h-1.7c0,0.74 -0.16,1.43 -0.43,2.05l1.23,1.23c0.56,-0.98 0.9,-2.09 0.9,-3.28z M14.98,11.17c0,-0.06 0.02,-0.11 0.02,-0.17V5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v0.18l5.98,5.99z M4.27,3L3,4.27l6.01,6.01V11c0,1.66 1.33,3 2.99,3 0.22,0 0.44,-0.03 0.65,-0.08l1.66,1.66c-0.71,0.33 -1.5,0.52 -2.31,0.52 -2.76,0 -5.3,-2.1 -5.3,-5.1H5c0,3.41 2.72,6.23 6,6.72V21h2v-3.28c0.91,-0.13 1.77,-0.45 2.54,-0.9L19.73,21 21,19.73 4.27,3z",
        "camera" to "M9,2L7.17,4H4c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V6c0,-1.1 -0.9,-2 -2,-2h-3.17L15,2H9z M12,17c-2.76,0 -5,-2.24 -5,-5s2.24,-5 5,-5 5,2.24 5,5 -2.24,5 -5,5z",
        "chat" to "M20,2H4c-1.1,0 -2,0.9 -2,2v18l4,-4h14c1.1,0 2,-0.9 2,-2V4c0,-1.1 -0.9,-2 -2,-2z",
        "music" to "M12,3v10.55c-0.59,-0.34 -1.27,-0.55 -2,-0.55 -2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4V7h4V3h-6z",
        "bell" to "M12,22c1.1,0 2,-0.9 2,-2h-4c0,1.1 0.89,2 2,2z M18,16v-5c0,-3.07 -1.64,-5.64 -4.5,-6.32V4c0,-0.83 -0.67,-1.5 -1.5,-1.5s-1.5,0.67 -1.5,1.5v0.68C7.63,5.36 6,7.92 6,11v5l-2,2v1h16v-1l-2,-2z",
        "clock" to "M11.99,2C6.47,2 2,6.48 2,12s4.47,10 9.99,10C17.52,22 22,17.52 22,12S17.52,2 11.99,2z M12,20c-4.42,0 -8,-3.58 -8,-8s3.58,-8 8,-8 8,3.58 8,8 -3.58,8 -8,8z M12.5,7H11v6l5.25,3.15 0.75,-1.23 -4.5,-2.67z",
        "cloud" to "M19.35,10.04C18.67,6.59 15.64,4 12,4 9.11,4 6.6,5.64 5.35,8.04 2.34,8.36 0,10.91 0,14c0,3.31 2.69,6 6,6h13c2.76,0 5,-2.24 5,-5 0,-2.64 -2.05,-4.78 -4.65,-4.96z",
        "sun" to "M6.76,4.84l-1.8,-1.79 -1.41,1.41 1.79,1.79 1.42,-1.41z M4,10.5H1v2h3v-2z M13,0.55h-2V3.5h2V0.55z M20.45,4.46l-1.41,-1.41 -1.79,1.79 1.41,1.41 1.79,-1.79z M17.24,18.16l1.79,1.8 1.41,-1.41 -1.8,-1.79 -1.4,1.4z M20,10.5v2h3v-2h-3z M12,5.5c-3.31,0 -6,2.69 -6,6s2.69,6 6,6 6,-2.69 6,-6 -2.69,-6 -6,-6z M11,22.45h2V19.5h-2v2.95z M3.55,18.54l1.41,1.41 1.79,-1.8 -1.41,-1.41 -1.79,1.8z",
        "vol_up" to "M3,9v6h4l5,5V4L7,9H3z M16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02z",
        "vol_off" to "M16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v2.21l2.45,2.45c0.03,-0.2 0.05,-0.41 0.05,-0.63z M4.27,3L3,4.27 7.73,9H3v6h4l5,5v-6.73l4.25,4.25c-0.67,0.52 -1.42,0.93 -2.25,1.18v2.06c1.38,-0.31 2.63,-0.95 3.69,-1.81L19.73,21 21,19.73l-9,-9L4.27,3z M12,4L9.91,6.09 12,8.18V4z",
        "moon" to "M9,2c-1.05,0 -2.05,0.16 -3,0.46 4.06,1.27 7,5.06 7,9.54 0,4.48 -2.94,8.27 -7,9.54 0.95,0.3 1.95,0.46 3,0.46 5.52,0 10,-4.48 10,-10S14.52,2 9,2z",
        "menu" to "M3,18h18v-2H3v2z M3,13h18v-2H3v2z M3,6v2h18V6H3z",
        "close" to "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z",
        "chevron" to "M10,6L8.59,7.41 13.17,12l-4.58,4.59L10,18l6,-6z",
        "person" to "M12,12c2.21,0 4,-1.79 4,-4s-1.79,-4 -4,-4 -4,1.79 -4,4 1.79,4 4,4z M12,14c-2.67,0 -8,1.34 -8,4v2h16v-2c0,-2.66 -5.33,-4 -8,-4z",
        "grid" to "M4,8h4V4H4v4z M10,20h4v-4h-4v4z M4,20h4v-4H4v4z M4,14h4v-4H4v4z M10,14h4v-4h-4v4z M16,4v4h4V4h-4z M10,8h4V4h-4v4z M16,14h4v-4h-4v4z M16,20h4v-4h-4v4z",
        "tune" to "M3,17v2h6v-2H3z M3,5v2h10V5H3z M13,21v-2h8v-2h-8v-2h-2v6h2z M7,9v2H3v2h4v2h2V9H7z M21,13v-2H11v2h10z M15,9h2V7h4V5h-4V3h-2v6z",
        "palette" to "M12,3c-4.97,0 -9,4.03 -9,9s4.03,9 9,9c0.83,0 1.5,-0.67 1.5,-1.5 0,-0.39 -0.15,-0.74 -0.39,-1.01 -0.23,-0.26 -0.38,-0.61 -0.38,-0.99 0,-0.83 0.67,-1.5 1.5,-1.5H16c2.76,0 5,-2.24 5,-5 0,-4.42 -4.03,-8 -9,-8z M6.5,12c-0.83,0 -1.5,-0.67 -1.5,-1.5S5.67,9 6.5,9 8,9.67 8,10.5 7.33,12 6.5,12z M9.5,8C8.67,8 8,7.33 8,6.5S8.67,5 9.5,5s1.5,0.67 1.5,1.5S10.33,8 9.5,8z M14.5,8c-0.83,0 -1.5,-0.67 -1.5,-1.5S13.67,5 14.5,5s1.5,0.67 1.5,1.5S15.33,8 14.5,8z M17.5,12c-0.83,0 -1.5,-0.67 -1.5,-1.5S16.67,9 17.5,9s1.5,0.67 1.5,1.5 -0.67,1.5 -1.5,1.5z"
    )
    private val cache = HashMap<String, Path>()
    fun path(key: String): Path = cache.getOrPut(key) {
        (try { PathParser.createPathFromPathData(data[key] ?: data["grid"]!!) } catch (_: Throwable) { Path() }).apply { fillType = Path.FillType.EVEN_ODD }
    }
    fun draw(c: Canvas, key: String, cx: Float, cy: Float, size: Float, paint: Paint) {
        c.save(); val s = size / 24f
        c.translate(cx - size / 2, cy - size / 2); c.scale(s, s)
        c.drawPath(path(key), paint); c.restore()
    }
}

fun Int.withAlpha(a: Int): Int = (this and 0x00FFFFFF) or ((a.coerceIn(0, 255)) shl 24)

/** Neon glow wala gol button (reference design jaisa). */
class GlowCircle(ctx: Context) : View(ctx) {
    var icon = "mic"; set(v) { field = v; invalidate() }
    var accent = Color.parseColor("#FF8FC7"); set(v) { field = v; invalidate() }
    var tint: Int? = null; set(v) { field = v; invalidate() }
    var active = false; set(v) { field = v; invalidate() }
    var iconScale = 0.44f
    private val d = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var shown = true
    override fun onWindowVisibilityChanged(v: Int) { super.onWindowVisibilityChanged(v); shown = v == VISIBLE; if (shown) invalidate() }
    override fun onDraw(c: Canvas) {
        val tt = (System.currentTimeMillis() % 100000L) / 1000f
        val breath = (sin(tt * 2.2f) * 0.5f + 0.5f)
        val w = width.toFloat(); val h = height.toFloat(); val cx = w / 2; val cy = h / 2
        val r = minOf(w, h) / 2 - 7 * d
        val col = tint ?: accent
        p.style = Paint.Style.FILL; p.color = Color.argb(200, 8, 5, 12); c.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.STROKE
        for ((i, a) in intArrayOf(26, 44, 70).withIndex()) { p.strokeWidth = (6 - i * 2) * d; p.color = col.withAlpha((a * (0.65f + 0.7f * breath)).toInt()); c.drawCircle(cx, cy, r + (3 - i) * d, p) }
        p.strokeWidth = 1.7f * d; p.color = col; c.drawCircle(cx, cy, r, p)
        // ghoomti roshni (hamesha chalti rehti hai)
        rect.set(cx - r - 4 * d, cy - r - 4 * d, cx + r + 4 * d, cy + r + 4 * d)
        p.strokeCap = Paint.Cap.ROUND; p.strokeWidth = 2.6f * d; p.color = col.withAlpha(235)
        val spin = if (active) 360f else 150f
        c.drawArc(rect, tt * spin % 360f, 55f, false, p)
        p.strokeWidth = 1.6f * d; p.color = col.withAlpha(140); c.drawArc(rect, (-tt * spin * 0.6f) % 360f + 180f, 30f, false, p)
        p.strokeCap = Paint.Cap.BUTT
        if (active) {
            val pulse = (sin(System.currentTimeMillis() / 220.0) * 0.5 + 0.5).toFloat()
            p.strokeWidth = 2f * d; p.color = col.withAlpha((60 + 140 * pulse).toInt()); c.drawCircle(cx, cy, r + (7 + 6 * pulse) * d, p)
        }
        p.style = Paint.Style.FILL; p.color = col
        val sz = minOf(w, h) * iconScale * (1f + (if (active) 0.06f * breath else 0.02f * breath))
        Icons.draw(c, icon, cx, cy, sz, p)
        if (shown) postInvalidateDelayed(40)
    }
}

/** Glass info card: icon + chhota label + bari value (Weather / Time / Music...). */
class InfoCard(ctx: Context) : View(ctx) {
    var icon = "cloud"; var label = ""; var value = ""
    var accent = Color.parseColor("#FF8FC7")
    private val d = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path(); private val pos = FloatArray(2); private val rr = RectF(); private val tr = RectF()
    private var shown = true
    fun set(i: String, l: String, v: String) { icon = i; label = l; value = v; invalidate() }
    override fun onWindowVisibilityChanged(v: Int) { super.onWindowVisibilityChanged(v); shown = v == VISIBLE; if (shown) invalidate() }
    override fun onDraw(c: Canvas) {
        val tt = (System.currentTimeMillis() % 100000L) / 1000f
        val breath = (sin(tt * 1.8f + id) * 0.5f + 0.5f)
        val w = width.toFloat(); val h = height.toFloat(); val pad = 7 * d
        rr.set(pad, pad, w - pad, h - pad)
        p.style = Paint.Style.FILL; p.color = Color.argb(205, 8, 5, 12); c.drawRoundRect(rr, 20 * d, 20 * d, p)
        p.style = Paint.Style.STROKE
        for ((i, a) in intArrayOf(24, 42, 66).withIndex()) {
            p.strokeWidth = (6 - i * 2) * d; p.color = accent.withAlpha((a * (0.7f + 0.6f * breath)).toInt())
            tr.set(rr.left - (3 - i) * d, rr.top - (3 - i) * d, rr.right + (3 - i) * d, rr.bottom + (3 - i) * d); c.drawRoundRect(tr, 22 * d, 22 * d, p)
        }
        p.strokeWidth = 1.5f * d; p.color = accent; c.drawRoundRect(rr, 20 * d, 20 * d, p)
        // kinare par daudti chamak
        path.reset(); path.addRoundRect(rr, 20 * d, 20 * d, Path.Direction.CW)
        val pm = PathMeasure(path, false); val len = pm.length
        p.style = Paint.Style.FILL
        for (k in 0 until 7) {
            val f = (((tt * 0.22f) - k * 0.012f) % 1f + 1f) % 1f
            pm.getPosTan(f * len, pos, null)
            p.color = Color.WHITE.withAlpha(235 - k * 32); c.drawCircle(pos[0], pos[1], (3.2f - k * 0.3f) * d, p)
        }
        p.color = accent
        Icons.draw(c, icon, w / 2, rr.top + 22 * d + (breath - 0.5f) * 1.5f * d, 20 * d, p)
        tp.textAlign = Paint.Align.CENTER
        tp.textSize = 11 * d; tp.color = Color.argb(215, 255, 255, 255); tp.typeface = Typeface.DEFAULT
        c.drawText(TextUtils.ellipsize(label, tp, w - 2 * pad - 10 * d, TextUtils.TruncateAt.END).toString(), w / 2, rr.top + 48 * d, tp)
        tp.textSize = 16 * d; tp.color = Color.WHITE; tp.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        c.drawText(TextUtils.ellipsize(value, tp, w - 2 * pad - 8 * d, TextUtils.TruncateAt.END).toString(), w / 2, rr.top + 70 * d, tp)
        if (shown) postInvalidateDelayed(50)
    }
}

/** Chhota icon (drawer rows ke liye). */
class IconView(ctx: Context) : View(ctx) {
    var icon = "grid"; set(v) { field = v; invalidate() }
    var tint = Color.WHITE; set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) { p.style = Paint.Style.FILL; p.color = tint; Icons.draw(c, icon, width / 2f, height / 2f, minOf(width, height).toFloat(), p) }
}
