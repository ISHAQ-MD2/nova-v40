package com.nova.app

import android.content.Context
import android.graphics.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

/**
 * v30 HACKER LIVE WALLPAPER (sirf drawing). Data WallData / WallBus se aata hai - sab asli, kuch nakli nahi.
 * Widgets: status, clock, battery, storage, RAM, net, CPU, sensors, reminders, namaz+mausam, voice text + waveform,
 * nayi files, clipboard, Termux, wifi, calendar, NOVA stats, live event feed, last action.
 * Touch par "bit-worms" (machhli jaisa) ungli ki taraf aate hain.
 *
 * NAYA WIDGET ADD KARNA HO: sirf is file mein (1) KEYS mein ek line, (2) topList/bottomList mein ek Wd(...) line, (3) ek draw function.
 */
class HackerWall(private val ctx: Context) {
    var w = 0; var h = 0
    var panX = 0f
    private var u = 2f
    private val prefs = ctx.getSharedPreferences("nova", Context.MODE_PRIVATE)
    private var accent = Color.parseColor("#00FF9C")
    private var bg: Bitmap? = null
    private var overlay: Bitmap? = null
    private var themeId = ""

    private val mono: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    private val monoB: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = mono }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()
    private val rf = RectF()
    private val rnd = Random(42)
    private val fmtDate = SimpleDateFormat("EEE, dd MMM yyyy", Locale.US)
    private val fmtHms = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val fmtHm = SimpleDateFormat("HH:mm", Locale.US)
    private var now = 0L

    // ---------- rain ----------
    private class Col(var y: Float, var sp: Float, var len: Int)
    private var cols = ArrayList<Col>()
    private var pool = "01"
    private var poolAt = 0L

    // ---------- touch worms ----------
    private class Worm(var x: Float, var y: Float, var vx: Float, var vy: Float, val seg: Int, val ph: Float) {
        val tx = FloatArray(seg); val ty = FloatArray(seg)
        init { for (i in 0 until seg) { tx[i] = x; ty[i] = y } }
    }
    private val worms = ArrayList<Worm>()
    @Volatile private var touching = false
    @Volatile private var tX = 0f
    @Volatile private var tY = 0f
    @Volatile private var attract = 0f
    private class Ripple(val x: Float, val y: Float, val t0: Long)
    private val ripples = ArrayList<Ripple>()
    private class Bit(val x: Float, val y: Float, val vx: Float, val vy: Float, val t0: Long, val ch: String)
    private val bits = ArrayList<Bit>()
    private var lastFrame = 0L

    // ---------- waveform ----------
    private val waveN = FloatArray(48); private val waveU = FloatArray(48); private var wavePos = 0; private var waveTick = 0

    // ---------- text cache ----------
    private val wrapCache = HashMap<String, List<String>>()

    /** key = widget ka naam, hu = height (u units), cols = ek line mein kitne widgets, pri = kam jagah ho to bada number pehle hat'ta hai */
    private class Wd(val key: String, val hu: Float, val cols: Int, val pri: Int, val draw: (Canvas, RectF) -> Unit)

    private val topList: List<Wd> by lazy { listOf(
        Wd("status", 16f, 1, 0) { c, r -> wStatus(c, r) },
        Wd("clock", 66f, 2, 0) { c, r -> wClock(c, r) }, Wd("battery", 66f, 2, 0) { c, r -> wBattery(c, r) },
        Wd("storage", 54f, 3, 0) { c, r -> wStorage(c, r) }, Wd("ram", 54f, 3, 0) { c, r -> wRam(c, r) }, Wd("net", 54f, 3, 0) { c, r -> wNet(c, r) },
        Wd("cpu", 42f, 2, 1) { c, r -> wCpu(c, r) }, Wd("sensors", 42f, 2, 1) { c, r -> wSensors(c, r) }
    ) }
    private val bottomList: List<Wd> by lazy { listOf(
        Wd("browser", 124f, 1, 3) { c, r -> wBrowser(c, r) },
        Wd("remind", 66f, 2, 0) { c, r -> wRemind(c, r) }, Wd("prayer", 66f, 2, 0) { c, r -> wSky(c, r) },
        Wd("voice", 74f, 1, 0) { c, r -> wVoice(c, r) },
        Wd("feed", 58f, 1, 3) { c, r -> wFeed(c, r) },
        Wd("files", 58f, 2, 1) { c, r -> wFiles(c, r) }, Wd("clip", 58f, 2, 1) { c, r -> wClip(c, r) },
        Wd("termux", 50f, 1, 1) { c, r -> wTermux(c, r) },
        Wd("wifi", 42f, 2, 4) { c, r -> wWifi(c, r) }, Wd("event", 42f, 2, 4) { c, r -> wEvent(c, r) },
        Wd("nova", 24f, 1, 5) { c, r -> wNova(c, r) },
        Wd("action", 18f, 1, 2) { c, r -> wAction(c, r) }
    ) }

    companion object {
        val KEYS = listOf("status" to "Status line (net, IP, NOVA)", "clock" to "Clock + date + uptime", "battery" to "Battery + temperature",
            "storage" to "Storage used / free", "ram" to "RAM + graph", "net" to "Net speed + graph", "cpu" to "CPU cores", "sensors" to "Sensors (tilt, light)",
            "remind" to "Reminders (asli)", "prayer" to "Namaz + mausam", "voice" to "Voice text + waveform", "feed" to "Live event feed (hacker log)",
            "files" to "Nayi files (naam + size)", "clip" to "Clipboard", "termux" to "Termux command", "wifi" to "WiFi signal + data total",
            "event" to "Agla calendar event", "nova" to "NOVA stats (tools, msgs, server)", "action" to "Last action",
            "rain" to "Matrix rain (asli data ke akshar)", "fish" to "Touch worms (ungli par aate hain)",
            "browser" to "NOVA Browser (jo wo abhi kar rahi hai live)")
        fun on(ctx: Context, key: String) = ctx.getSharedPreferences("nova", Context.MODE_PRIVATE).getBoolean("wpw_$key", true)
        /** 0=normal grid, 1=center bada, 2=upar-left corner, 3=upar-right corner, 4=bilkul nahi. */
        fun clockStyle(ctx: Context) = ctx.getSharedPreferences("nova", Context.MODE_PRIVATE).getInt("wp_clockstyle", 0).coerceIn(0, 4)
    }

    private fun enabled(k: String) = prefs.getBoolean("wpw_$k", true)
    private fun clockStyle() = prefs.getInt("wp_clockstyle", 0).coerceIn(0, 4)
    private val clockFonts by lazy { arrayOf(monoB, Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD), Typeface.create(Typeface.SERIF, Typeface.BOLD)) }

    fun reloadStyle() {
        val th = Themes.current(ctx); accent = th.accent
        if (th.id != themeId || bg == null) {
            themeId = th.id
            val src = try { Themes.wallpaper(ctx, th) } catch (_: Throwable) { null }
            bg = if (src != null && w > 0) coverScale(src, w, h) else null
        }
    }

    private fun coverScale(src: Bitmap, W: Int, H: Int): Bitmap {
        val s = max(W / src.width.toFloat(), H / src.height.toFloat())
        val bw = (src.width * s).toInt().coerceAtLeast(1); val bh = (src.height * s).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, bw, bh, true)
        val x = ((bw - W) / 2).coerceAtLeast(0); val y = ((bh - H) / 2).coerceAtLeast(0)
        return Bitmap.createBitmap(scaled, x, y, min(W, bw - x), min(H, bh - y))
    }

    /** Scanlines + kinaron ka andhera ek baar banta hai (har frame sirf ek drawBitmap). */
    private fun buildOverlay() {
        if (w <= 0 || h <= 0) return
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint()
        p.shader = RadialGradient(w / 2f, h / 2f, max(w, h) * 0.75f, intArrayOf(Color.argb(0, 0, 0, 0), Color.argb(0, 0, 0, 0), Color.argb(170, 0, 0, 0)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null; p.color = Color.argb(26, 0, 0, 0); p.strokeWidth = 1f
        var y = 0f
        while (y < h) { c.drawLine(0f, y, w.toFloat(), y, p); y += 3f }
        overlay = b
    }

    fun resize(W: Int, H: Int) {
        w = W; h = H; u = W / 360f
        bg = null; reloadStyle(); buildOverlay()
        cols.clear()
        val n = (W / (13f * u)).toInt().coerceAtLeast(6)
        for (i in 0 until n) cols.add(Col(rnd.nextFloat() * H, 40f * u + rnd.nextFloat() * 90f * u, 6 + rnd.nextInt(12)))
        worms.clear()
        for (i in 0 until 14) worms.add(Worm(rnd.nextFloat() * W, rnd.nextFloat() * H, 0f, 0f, 7, rnd.nextFloat() * 6.28f))
    }

    // ---------- touch ----------
    fun touch(x: Float, y: Float, down: Boolean) {
        if (down && !touching) { ripples.add(Ripple(x, y, System.currentTimeMillis())); burst(x, y) }
        touching = down; tX = x; tY = y
        if (down) attract = 1f
    }

    /** Launcher sirf "tap" command bhejta ho to bhi worms us jagah aate hain. */
    fun tap(x: Float, y: Float) { ripples.add(Ripple(x, y, System.currentTimeMillis())); burst(x, y); tX = x; tY = y; attract = 1f; touching = false }

    private fun burst(x: Float, y: Float) {
        val t = System.currentTimeMillis()
        for (i in 0 until 12) {
            val a = rnd.nextFloat() * 6.283f; val s = (60f + rnd.nextFloat() * 140f) * u
            bits.add(Bit(x, y, cos(a) * s, sin(a) * s, t, if (rnd.nextBoolean()) "1" else "0"))
        }
        while (bits.size > 80) bits.removeAt(0)
    }

    // ---------- helpers ----------
    private fun col(a: Int, c: Int = accent) = Color.argb(a.coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))
    private fun txt(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, align: Paint.Align = Paint.Align.LEFT, bold: Boolean = false) {
        tp.textSize = size * u; tp.color = color; tp.textAlign = align; tp.typeface = if (bold) monoB else mono
        c.drawText(s, x, y, tp)
    }
    private fun fit(s: String, size: Float, maxW: Float): String {
        tp.textSize = size * u; tp.typeface = mono
        if (tp.measureText(s) <= maxW) return s
        var e = s.length
        while (e > 1 && tp.measureText(s, 0, e) + tp.measureText("..") > maxW) e--
        return s.substring(0, e) + ".."
    }
    private fun wrap(s: String, size: Float, maxW: Float, maxLines: Int): List<String> {
        val key = "$size|${maxW.toInt()}|$maxLines|$s"
        wrapCache[key]?.let { return it }
        tp.textSize = size * u; tp.typeface = mono
        val out = ArrayList<String>(); var cur = ""
        for (word in s.replace('\n', ' ').split(' ')) {
            if (word.isEmpty()) continue
            val t = if (cur.isEmpty()) word else "$cur $word"
            if (tp.measureText(t) <= maxW) cur = t
            else {
                if (cur.isNotEmpty()) { out.add(cur); cur = "" }
                var rest = word
                while (tp.measureText(rest) > maxW && rest.length > 1) {
                    var e = rest.length; while (e > 1 && tp.measureText(rest, 0, e) > maxW) e--
                    out.add(rest.substring(0, e)); rest = rest.substring(e)
                }
                cur = rest
            }
            if (out.size >= maxLines) break
        }
        if (cur.isNotEmpty() && out.size < maxLines) out.add(cur)
        while (out.size > maxLines) out.removeAt(out.size - 1)
        if (wrapCache.size > 60) wrapCache.clear()
        wrapCache[key] = out
        return out
    }
    private fun panel(c: Canvas, r: RectF, title: String, hot: Boolean = false) {
        fill.color = Color.argb(if (hot) 150 else 105, 2, 8, 6); c.drawRoundRect(r, 5 * u, 5 * u, fill)
        line.strokeWidth = 1f * u; line.color = col(if (hot) 230 else 120); c.drawRoundRect(r, 5 * u, 5 * u, line)
        line.strokeWidth = 2f * u; line.color = col(255)
        val l = 9 * u
        c.drawLine(r.left, r.top + l, r.left, r.top, line); c.drawLine(r.left, r.top, r.left + l, r.top, line)
        c.drawLine(r.right - l, r.bottom, r.right, r.bottom, line); c.drawLine(r.right, r.bottom, r.right, r.bottom - l, line)
        txt(c, title, r.left + 7 * u, r.top + 11 * u, 8f, col(230), bold = true)
    }
    private fun bar(c: Canvas, x: Float, y: Float, bw: Float, bh: Float, frac: Float, color: Int) {
        fill.color = Color.argb(70, 255, 255, 255); rf.set(x, y, x + bw, y + bh); c.drawRoundRect(rf, bh / 2, bh / 2, fill)
        fill.color = color; rf.set(x, y, x + max(bh, bw * frac.coerceIn(0f, 1f)), y + bh); c.drawRoundRect(rf, bh / 2, bh / 2, fill)
    }
    private fun spark(c: Canvas, x: Float, y: Float, sw: Float, sh: Float, data: FloatArray, start: Int, color: Int, maxV: Float) {
        path.reset(); val n = data.size
        for (i in 0 until n) {
            val v = data[(start + i) % n]; val px = x + sw * i / (n - 1); val py = y + sh - sh * (v / maxV).coerceIn(0f, 1f)
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        line.strokeWidth = 1.2f * u; line.color = color; c.drawPath(path, line)
    }
    private fun fmtDur(ms: Long): String {
        val s = ms / 1000; val d = s / 86400; val hh = (s % 86400) / 3600; val m = (s % 3600) / 60
        return if (d > 0) "${d}d ${hh}h" else if (hh > 0) "${hh}h ${m}m" else "${m}m ${s % 60}s"
    }
    private fun fmtSpeed(b: Long): String = if (b < 1024) "$b B/s" else if (b < 1024 * 1024) String.format(Locale.US, "%.0f KB/s", b / 1024.0) else String.format(Locale.US, "%.1f MB/s", b / 1048576.0)

    // ---------- frame ----------
    fun draw(c: Canvas) {
        if (w <= 0) return
        now = System.currentTimeMillis()
        BrowserMirror.wanted = enabled("browser")      // browser ki tasveer sirf tab banti hai jab wallpaper dikh raha ho
        val dt = if (lastFrame == 0L) 0.033f else ((now - lastFrame) / 1000f).coerceIn(0.005f, 0.06f)
        lastFrame = now
        c.drawColor(Color.BLACK)
        bg?.let { c.drawBitmap(it, 0f, 0f, null) }
        fill.color = Color.argb(120, 0, 0, 0); c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), fill)
        if (enabled("rain")) drawRain(c, dt)
        layout(c)
        val cs = clockStyle()
        if (cs in 1..3 && enabled("clock")) drawClockStyle(c, cs)
        if (enabled("fish")) drawWorms(c, dt)
        drawFx(c)
        drawAlert(c)
        overlay?.let { c.drawBitmap(it, 0f, 0f, null) }
    }

    /** Ek hi tarah ke (cols) lagaatar widgets ek line mein. */
    private fun groups(en: List<Wd>): ArrayList<ArrayList<Wd>> {
        val out = ArrayList<ArrayList<Wd>>(); var i = 0
        while (i < en.size) {
            val g = en[i]; var n = 1
            while (n < g.cols && i + n < en.size && en[i + n].cols == g.cols) n++
            out.add(ArrayList(en.subList(i, i + n))); i += n
        }
        return out
    }

    private fun layout(c: Canvas) {
        val mx = 10 * u; val gap = 6 * u
        val cs = clockStyle()
        val top = groups(topList.filter { enabled(it.key) && !(it.key == "clock" && cs != 0) })
        var yTop = 30 * u
        for (g in top) yTop += g[0].hu * u + gap
        val bot = groups(bottomList.filter { enabled(it.key) && (it.key != "browser" || NovaBrowser.host != NovaBrowser.Host.NONE) })
        val avail = h - 64 * u - yTop
        // jagah kam ho to sabse kam zaroori line pehle hat jaye
        var total = 0f
        for (g in bot) total += g[0].hu * u + gap
        while (total > avail && bot.isNotEmpty()) {
            var worst = 0; var wp = -1
            for ((i, g) in bot.withIndex()) { val p = g.maxOf { it.pri }; if (p >= wp) { wp = p; worst = i } }
            total -= bot[worst][0].hu * u + gap
            bot.removeAt(worst)
        }
        flow(c, top, mx, 30 * u, gap)
        flow(c, bot, mx, h - 64 * u - total, gap)
    }

    private fun flow(c: Canvas, gs: List<List<Wd>>, mx: Float, y0: Float, gap: Float) {
        var y = y0
        val full = w - 2 * mx
        for (g in gs) {
            val n = g.size
            val cw = (full - gap * (n - 1)) / n
            for (k in 0 until n) {
                val wd = g[k]
                val r = RectF(mx + k * (cw + gap), y, mx + k * (cw + gap) + cw, y + wd.hu * u)
                try { wd.draw(c, r) } catch (_: Throwable) {}
            }
            y += g[0].hu * u + gap
        }
    }

    // ---------- rain ----------
    private fun refreshPool() {
        if (now - poolAt < 2500) return
        poolAt = now
        val sb = StringBuilder("0123456789ABCDEF01")
        val s = WallData.snap
        sb.append(s?.ip ?: "").append(WallBus.userLine.takeLast(40)).append(WallBus.novaLine.takeLast(40))
        WallBus.files.take(3).forEach { sb.append(it.name.take(20)) }
        pool = sb.toString().filter { !it.isWhitespace() }.ifEmpty { "01" }
    }

    private fun drawRain(c: Canvas, dt: Float) {
        if (cols.isEmpty()) return
        refreshPool()
        val cw = w / cols.size.toFloat(); val fs = 11f
        tp.textSize = fs * u; tp.typeface = mono; tp.textAlign = Paint.Align.CENTER
        for ((i, cl) in cols.withIndex()) {
            cl.y += cl.sp * dt
            if (cl.y - cl.len * fs * u > h) { cl.y = -rnd.nextFloat() * 200f * u; cl.sp = 40f * u + rnd.nextFloat() * 90f * u; cl.len = 6 + rnd.nextInt(12) }
            val x = i * cw + cw / 2 + panX
            for (k in 0 until cl.len) {
                val a = (150 * (1f - k / cl.len.toFloat())).toInt()
                if (a < 8) continue
                tp.color = if (k == 0) col(210, Color.WHITE) else col(a)
                val ch = pool[((cl.y / (fs * u)).toInt() * 7 + k * 13 + i * 31).mod(pool.length)]
                c.drawText(ch.toString(), x, cl.y - k * fs * u, tp)
            }
        }
        tp.textAlign = Paint.Align.LEFT
    }

    // ---------- worms ----------
    private fun drawWorms(c: Canvas, dt: Float) {
        if (!touching) attract = max(0f, attract - dt * 0.35f)
        val maxV = 280f * u
        for (wm in worms) {
            val dx = tX - wm.x; val dy = tY - wm.y; val d = sqrt(dx * dx + dy * dy) + 0.001f
            var ax: Float; var ay: Float
            if (attract > 0.02f) {
                val orbit = 34f * u
                val f = 900f * u * attract
                if (d > orbit) { ax = dx / d * f; ay = dy / d * f } else { ax = -dy / d * f * 0.7f - dx / d * f * 0.2f; ay = dx / d * f * 0.7f - dy / d * f * 0.2f }
            } else {
                val a = now / 1000f * 0.8f + wm.ph
                ax = cos(a) * 120f * u; ay = sin(a * 1.3f) * 120f * u
            }
            wm.vx = (wm.vx + ax * dt) * 0.985f; wm.vy = (wm.vy + ay * dt) * 0.985f
            val sp = sqrt(wm.vx * wm.vx + wm.vy * wm.vy)
            if (sp > maxV) { wm.vx *= maxV / sp; wm.vy *= maxV / sp }
            wm.x += wm.vx * dt; wm.y += wm.vy * dt
            if (wm.x < 0) { wm.x = 0f; wm.vx = abs(wm.vx) }
            if (wm.x > w) { wm.x = w.toFloat(); wm.vx = -abs(wm.vx) }
            if (wm.y < 0) { wm.y = 0f; wm.vy = abs(wm.vy) }
            if (wm.y > h) { wm.y = h.toFloat(); wm.vy = -abs(wm.vy) }
            // body: har segment pichle ko follow karta hai
            var px = wm.x; var py = wm.y
            for (i in 0 until wm.seg) {
                val ddx = px - wm.tx[i]; val ddy = py - wm.ty[i]; val dd = sqrt(ddx * ddx + ddy * ddy)
                val keep = 5.5f * u
                if (dd > keep) { wm.tx[i] += ddx / dd * (dd - keep); wm.ty[i] += ddy / dd * (dd - keep) }
                px = wm.tx[i]; py = wm.ty[i]
            }
            for (i in wm.seg - 1 downTo 0) {
                val r = (1.2f + (wm.seg - i) * 0.28f) * u
                fill.color = col(60 + 190 * (wm.seg - i) / wm.seg)
                c.drawCircle(wm.tx[i], wm.ty[i], r, fill)
            }
            // head: tez diamond (machhli ka sar), chalne ki disha mein
            val ang = atan2(wm.vy, wm.vx); val hs = 4.2f * u
            path.reset()
            path.moveTo(wm.x + cos(ang) * hs * 1.6f, wm.y + sin(ang) * hs * 1.6f)
            path.lineTo(wm.x + cos(ang + 2.4f) * hs, wm.y + sin(ang + 2.4f) * hs)
            path.lineTo(wm.x + cos(ang + 3.14f) * hs * 0.5f, wm.y + sin(ang + 3.14f) * hs * 0.5f)
            path.lineTo(wm.x + cos(ang - 2.4f) * hs, wm.y + sin(ang - 2.4f) * hs)
            path.close()
            fill.color = col(255, Color.WHITE); c.drawPath(path, fill)
        }
    }

    private fun drawFx(c: Canvas) {
        val it = ripples.iterator()
        while (it.hasNext()) {
            val r = it.next(); val age = (now - r.t0) / 1000f
            if (age > 1.4f) { it.remove(); continue }
            val a = (255 * (1f - age / 1.4f)).toInt()
            line.strokeWidth = 1.5f * u
            for (k in 0 until 3) { val rr = (age * 120f - k * 18f) * u; if (rr > 0) { line.color = col(a / (k + 1)); c.drawCircle(r.x, r.y, rr, line) } }
            line.color = col(a); c.drawLine(r.x - 14 * u, r.y, r.x + 14 * u, r.y, line); c.drawLine(r.x, r.y - 14 * u, r.x, r.y + 14 * u, line)
            txt(c, "X:${r.x.toInt()} Y:${r.y.toInt()}", r.x + 10 * u, r.y - 10 * u, 8f, col(a, Color.WHITE))
        }
        val bi = bits.iterator()
        while (bi.hasNext()) {
            val b = bi.next(); val age = (now - b.t0) / 1000f
            if (age > 1.2f) { bi.remove(); continue }
            val x = b.x + b.vx * age; val y = b.y + b.vy * age
            txt(c, b.ch, x, y, 10f, col((255 * (1f - age / 1.2f)).toInt()))
        }
    }

    private fun drawAlert(c: Canvas) {
        if (now < WallBus.alertUntil && WallBus.alertText.isNotEmpty()) {
            val pulse = (sin(now / 160.0) * 0.5 + 0.5).toFloat()
            val r = RectF(14 * u, h * 0.40f, w - 14 * u, h * 0.40f + 92 * u)
            fill.color = Color.argb(190, 20, 0, 0); c.drawRoundRect(r, 8 * u, 8 * u, fill)
            line.strokeWidth = 2.5f * u; line.color = Color.argb((120 + 135 * pulse).toInt(), 255, 60, 60); c.drawRoundRect(r, 8 * u, 8 * u, line)
            txt(c, "!! REMINDER !!", w / 2f, r.top + 20 * u, 12f, Color.argb(255, 255, 90, 90), Paint.Align.CENTER, true)
            val ls = wrap(WallBus.alertText, 13f, r.width() - 20 * u, 3)
            for ((i, l) in ls.withIndex()) txt(c, l, w / 2f, r.top + 42 * u + i * 17 * u, 13f, Color.WHITE, Paint.Align.CENTER)
        }
        if (now < WallBus.flashUntil && WallBus.flashFile.isNotEmpty()) {
            val a = (255 * min(1f, (WallBus.flashUntil - now) / 1500f)).toInt()
            txt(c, "NEW FILE > " + fit(WallBus.flashFile, 11f, w - 60 * u), w / 2f, h * 0.37f, 11f, col(a, Color.WHITE), Paint.Align.CENTER, true)
        }
    }

    // ================= widgets =================
    private fun wStatus(c: Canvas, r: RectF) {
        val s = WallData.snap
        val online = s != null && s.net != "NO NET"
        fill.color = if (online) col(255) else Color.RED; c.drawCircle(r.left + 6 * u, r.centerY(), 3 * u, fill)
        val live = LiveService.liveClient
        val nova = when { Vol.speaking -> "NOVA SPEAKING"; live?.active == true -> "NOVA LISTENING"; LiveService.isRunning() -> "NOVA STANDBY"; else -> "NOVA OFF" }
        txt(c, "${s?.net ?: "..."}  ${s?.ip ?: "-"}", r.left + 14 * u, r.centerY() + 3 * u, 9f, col(230))
        txt(c, nova, r.right - 4 * u, r.centerY() + 3 * u, 9f, if (Vol.speaking) Color.WHITE else col(230), Paint.Align.RIGHT, true)
    }

    private fun wClock(c: Canvas, r: RectF) {
        panel(c, r, "// TIME")
        val d = Date(now)
        txt(c, fmtHms.format(d), r.left + 8 * u, r.top + 41 * u, 30f, Color.WHITE, bold = true)
        val cal = Calendar.getInstance()
        val dayFrac = (cal.get(Calendar.HOUR_OF_DAY) * 3600 + cal.get(Calendar.MINUTE) * 60 + cal.get(Calendar.SECOND)) / 86400f
        txt(c, fmtDate.format(d), r.left + 8 * u, r.top + 54 * u, 8.5f, col(230))
        bar(c, r.left + 8 * u, r.bottom - 7 * u, r.width() - 16 * u, 3 * u, dayFrac, col(230))
        txt(c, "UP " + fmtDur(WallData.snap?.uptimeMs ?: 0L), r.right - 8 * u, r.top + 11 * u, 8f, col(200), Paint.Align.RIGHT)
    }

    private fun wBattery(c: Canvas, r: RectF) {
        panel(c, r, "// POWER")
        val s = WallData.snap ?: return
        val cx = r.left + 30 * u; val cy = r.centerY() + 4 * u; val rad = 20 * u
        line.strokeWidth = 4f * u; line.color = Color.argb(70, 255, 255, 255); c.drawCircle(cx, cy, rad, line)
        line.color = if (s.batPct <= 15 && !s.charging) Color.RED else col(255)
        rf.set(cx - rad, cy - rad, cx + rad, cy + rad); c.drawArc(rf, -90f, 360f * s.batPct / 100f, false, line)
        txt(c, "${s.batPct}", cx, cy + 4 * u, 12f, Color.WHITE, Paint.Align.CENTER, true)
        val x = r.left + 62 * u
        txt(c, if (s.charging) "CHARGING" else "ON BATTERY", x, r.top + 28 * u, 8.5f, col(240), bold = true)
        txt(c, String.format(Locale.US, "TEMP %.1f C", s.batTemp), x, r.top + 40 * u, 8.5f, Color.WHITE)
        txt(c, String.format(Locale.US, "VOLT %.2f V", s.batMv / 1000f), x, r.top + 52 * u, 8.5f, Color.WHITE)
    }

    private fun wStorage(c: Canvas, r: RectF) {
        panel(c, r, "// STORAGE")
        val s = WallData.snap ?: return
        val used = s.storTotal - s.storFree; val f = if (s.storTotal > 0) used.toFloat() / s.storTotal else 0f
        txt(c, "${(f * 100).toInt()}%", r.left + 7 * u, r.top + 32 * u, 17f, Color.WHITE, bold = true)
        bar(c, r.left + 7 * u, r.top + 37 * u, r.width() - 14 * u, 3.5f * u, f, if (f > 0.9f) Color.RED else col(255))
        txt(c, fit("USED " + WallData.fmtBytes(used), 7.5f, r.width() - 10 * u), r.left + 7 * u, r.top + 47 * u, 7.5f, col(220))
        txt(c, fit("FREE " + WallData.fmtBytes(s.storFree), 7.5f, r.width() - 10 * u), r.left + 7 * u, r.top + 53 * u - 0.5f * u, 7.5f, Color.WHITE)
    }

    private fun wRam(c: Canvas, r: RectF) {
        panel(c, r, "// RAM")
        val s = WallData.snap ?: return
        val used = s.ramTotal - s.ramFree; val f = if (s.ramTotal > 0) used.toFloat() / s.ramTotal else 0f
        txt(c, "${(f * 100).toInt()}%", r.left + 7 * u, r.top + 32 * u, 17f, Color.WHITE, bold = true)
        spark(c, r.left + 7 * u, r.top + 35 * u, r.width() - 14 * u, 10 * u, WallData.ramHist, WallData.histPos, col(255), 1f)
        txt(c, fit(WallData.fmtBytes(used) + "/" + WallData.fmtBytes(s.ramTotal), 7.5f, r.width() - 10 * u), r.left + 7 * u, r.bottom - 4 * u, 7.5f, col(220))
    }

    private fun wNet(c: Canvas, r: RectF) {
        panel(c, r, "// NET")
        val s = WallData.snap ?: return
        txt(c, fit("v " + fmtSpeed(s.rx), 8f, r.width() - 10 * u), r.left + 7 * u, r.top + 24 * u, 8f, Color.WHITE, bold = true)
        txt(c, fit("^ " + fmtSpeed(s.tx), 8f, r.width() - 10 * u), r.left + 7 * u, r.top + 34 * u, 8f, col(200))
        val mx = max(50f, max(WallData.rxHist.maxOrNull() ?: 0f, WallData.txHist.maxOrNull() ?: 0f))
        spark(c, r.left + 7 * u, r.top + 37 * u, r.width() - 14 * u, 12 * u, WallData.rxHist, WallData.histPos, Color.WHITE, mx)
        spark(c, r.left + 7 * u, r.top + 37 * u, r.width() - 14 * u, 12 * u, WallData.txHist, WallData.histPos, col(255), mx)
    }

    private fun wCpu(c: Canvas, r: RectF) {
        panel(c, r, "// CPU")
        val s = WallData.snap ?: return
        if (s.freq.isEmpty()) { txt(c, "${s.cores} CORES", r.left + 8 * u, r.top + 30 * u, 11f, Color.WHITE, bold = true); txt(c, "freq: phone ne band rakha", r.left + 8 * u, r.top + 39 * u, 7.5f, col(180)); return }
        val n = s.freq.size; val bw = (r.width() - 16 * u) / n
        val maxF = max(2000, s.freq.maxOrNull() ?: 2000)
        for (i in 0 until n) {
            val f = s.freq[i] / maxF.toFloat()
            fill.color = Color.argb(60, 255, 255, 255); c.drawRect(r.left + 8 * u + i * bw + 1 * u, r.top + 16 * u, r.left + 8 * u + (i + 1) * bw - 1 * u, r.bottom - 6 * u, fill)
            fill.color = col(255)
            val top = r.bottom - 6 * u - (r.height() - 22 * u) * f.coerceIn(0.03f, 1f)
            c.drawRect(r.left + 8 * u + i * bw + 1 * u, top, r.left + 8 * u + (i + 1) * bw - 1 * u, r.bottom - 6 * u, fill)
        }
        txt(c, "${s.freq.maxOrNull()} MHz", r.right - 6 * u, r.top + 11 * u, 7.5f, Color.WHITE, Paint.Align.RIGHT)
    }

    private fun wSensors(c: Canvas, r: RectF) {
        panel(c, r, "// SENSORS")
        txt(c, String.format(Locale.US, "TILT X%+.1f Y%+.1f", WallData.ax, WallData.ay), r.left + 8 * u, r.top + 26 * u, 8f, Color.WHITE)
        txt(c, String.format(Locale.US, "Z %+.1f  g=%.1f", WallData.az, sqrt(WallData.ax * WallData.ax + WallData.ay * WallData.ay + WallData.az * WallData.az)), r.left + 8 * u, r.top + 36 * u, 8f, col(220))
        txt(c, if (WallData.lux >= 0) String.format(Locale.US, "LIGHT %.0f lx", WallData.lux) else "LIGHT --", r.left + 8 * u, r.top + 45 * u, 8f, col(220))
        val bx = r.right - 18 * u; val by = r.centerY() + 6 * u
        line.strokeWidth = 1f * u; line.color = col(120); c.drawCircle(bx, by, 11 * u, line)
        fill.color = col(255); c.drawCircle(bx - WallData.ax * 1.1f * u, by + WallData.ay * 1.1f * u, 3 * u, fill)
    }

    private fun wRemind(c: Canvas, r: RectF) {
        val s = WallData.snap
        val list = s?.reminders ?: emptyList()
        panel(c, r, "// REMINDERS (${list.size})", hot = now < WallBus.alertUntil)
        if (list.isEmpty()) { txt(c, "koi reminder nahi", r.left + 8 * u, r.top + 32 * u, 8.5f, col(170)); return }
        for ((i, p) in list.take(4).withIndex()) {
            val y = r.top + 24 * u + i * 10.5f * u
            val left = p.first - now
            val eta = if (left <= 0) "now" else if (left < 3600_000L) "${left / 60000}m" else fmtHm.format(Date(p.first))
            txt(c, eta, r.left + 8 * u, y, 8f, Color.WHITE, bold = true)
            txt(c, fit(p.second, 8f, r.width() - 46 * u), r.left + 38 * u, y, 8f, col(235))
        }
    }

    private fun wSky(c: Canvas, r: RectF) {
        panel(c, r, "// NAMAZ + MAUSAM")
        val s = WallData.snap
        val pr = s?.prayer ?: ""
        if (pr.contains("|")) {
            val nm = pr.substringBefore("|"); val at = pr.substringAfter("|").toLongOrNull() ?: 0L
            txt(c, nm.uppercase(), r.left + 8 * u, r.top + 31 * u, 13f, Color.WHITE, bold = true)
            txt(c, "in " + fmtDur((at - now).coerceAtLeast(0)), r.left + 8 * u, r.top + 42 * u, 8.5f, col(235))
        }
        txt(c, fit(if (s?.weather.isNullOrEmpty()) "mausam: --" else s!!.weather, 8.5f, r.width() - 14 * u), r.left + 8 * u, r.top + 56 * u, 8.5f, Color.WHITE)
    }

    private fun wVoice(c: Canvas, r: RectF) {
        val fresh = now - max(WallBus.userAt, WallBus.novaAt) < 8000
        panel(c, r, "// VOICE LOG", hot = Vol.speaking || fresh)
        val tw = r.width() - 70 * u
        fun typed(s: String, at: Long): String { val n = ((now - at) / 22).toInt().coerceAtLeast(0); return if (n >= s.length) s else s.substring(0, n) }
        val u1 = wrap(typed(WallBus.userLine, WallBus.userAt).ifEmpty { "..." }, 8.5f, tw - 22 * u, 2)
        val n1 = wrap(typed(WallBus.novaLine, WallBus.novaAt).ifEmpty { "..." }, 8.5f, tw - 22 * u, 2)
        txt(c, "YOU", r.left + 8 * u, r.top + 24 * u, 8f, Color.WHITE, bold = true)
        for ((i, l) in u1.withIndex()) txt(c, l, r.left + 32 * u, r.top + 24 * u + i * 10 * u, 8.5f, Color.WHITE)
        txt(c, "NOVA", r.left + 8 * u, r.top + 50 * u, 8f, col(255), bold = true)
        for ((i, l) in n1.withIndex()) txt(c, l, r.left + 32 * u, r.top + 50 * u + i * 10 * u, 8.5f, col(240))
        // waveform: asli awaaz ka level (NOVA upar, aap neeche)
        waveTick++
        if (waveTick % 2 == 0) { waveN[wavePos] = Vol.level; waveU[wavePos] = Vol.micLevel; wavePos = (wavePos + 1) % waveN.size }
        val x0 = r.right - 62 * u; val mid = r.centerY() + 6 * u; val bw = 56 * u / waveN.size
        for (i in 0 until waveN.size) {
            val k = (wavePos + i) % waveN.size
            val a = (waveN[k] * 26f * u).coerceIn(0.6f * u, 24f * u); val b = (waveU[k] * 26f * u).coerceIn(0.6f * u, 24f * u)
            fill.color = col(230); c.drawRect(x0 + i * bw, mid - a, x0 + i * bw + bw * 0.7f, mid, fill)
            fill.color = Color.argb(200, 255, 255, 255); c.drawRect(x0 + i * bw, mid, x0 + i * bw + bw * 0.7f, mid + b, fill)
        }
    }

    private fun wFeed(c: Canvas, r: RectF) {
        panel(c, r, "// LIVE LOG", hot = WallBus.feed.firstOrNull()?.let { now - it.at < 3000 } == true)
        val l = WallBus.feed
        if (l.isEmpty()) { txt(c, "abhi koi event nahi", r.left + 8 * u, r.top + 30 * u, 8f, col(170)); return }
        for ((i, e) in l.take(4).withIndex()) {
            val y = r.top + 22 * u + i * 9.2f * u
            val age = now - e.at
            val s = fmtHms.format(Date(e.at)) + " " + e.tag + " " + e.text
            txt(c, fit(s, 7.2f, r.width() - 14 * u), r.left + 8 * u, y, 7.2f, if (age < 4000) Color.WHITE else col(215 - min(100, (age / 1000).toInt() * 3)))
        }
    }

    private fun wFiles(c: Canvas, r: RectF) {
        panel(c, r, "// NEW FILES", hot = now < WallBus.flashUntil)
        val l = WallBus.files
        if (l.isEmpty()) { txt(c, "abhi koi nayi file nahi", r.left + 8 * u, r.top + 32 * u, 8f, col(170)); return }
        for ((i, f) in l.take(3).withIndex()) {
            val y = r.top + 24 * u + i * 12 * u
            txt(c, fit(f.name, 7.8f, r.width() - 14 * u), r.left + 8 * u, y, 7.8f, if (f.fresh) Color.WHITE else col(200), bold = f.fresh)
            txt(c, WallData.fmtBytes(f.size), r.left + 8 * u, y + 5.5f * u, 6.8f, col(170))
        }
    }

    private fun wClip(c: Canvas, r: RectF) {
        panel(c, r, "// CLIPBOARD")
        val s = WallData.snap
        val t = s?.clipText ?: ""
        if (t.isEmpty()) { txt(c, "khali", r.left + 8 * u, r.top + 32 * u, 8f, col(170)); return }
        txt(c, fit((s?.clipAbout ?: "").uppercase(), 8f, r.width() - 14 * u), r.left + 8 * u, r.top + 25 * u, 8f, Color.WHITE, bold = true)
        val ls = wrap(t, 7.5f, r.width() - 14 * u, 3)
        for ((i, l) in ls.withIndex()) txt(c, l, r.left + 8 * u, r.top + 35 * u + i * 8.5f * u, 7.5f, col(225))
    }

    private fun wTermux(c: Canvas, r: RectF) {
        panel(c, r, "// TERMUX", hot = now - WallBus.termuxAt < 6000)
        if (WallBus.termuxCmd.isEmpty()) { txt(c, "koi command nahi chali", r.left + 8 * u, r.top + 30 * u, 8f, col(170)); return }
        txt(c, fit("$ " + WallBus.termuxCmd.replace('\n', ' '), 8f, r.width() - 14 * u), r.left + 8 * u, r.top + 24 * u, 8f, Color.WHITE, bold = true)
        val o = WallBus.termuxOut.replace("stdout:", "").replace("stderr:", "").trim().replace('\n', ' ')
        val ls = wrap(o, 7.5f, r.width() - 14 * u, 2)
        for ((i, l) in ls.withIndex()) txt(c, l, r.left + 8 * u, r.top + 34 * u + i * 8.5f * u, 7.5f, col(225))
    }

    private fun wWifi(c: Canvas, r: RectF) {
        panel(c, r, "// LINK")
        val s = WallData.snap ?: return
        if (s.wifiRssi > -127) {
            txt(c, "WIFI ${s.wifiRssi} dBm  ${s.wifiMbps} Mbps", r.left + 8 * u, r.top + 24 * u, 7.8f, Color.WHITE, bold = true)
            bar(c, r.left + 8 * u, r.top + 28 * u, r.width() - 16 * u, 3 * u, ((s.wifiRssi + 100) / 60f), col(255))
        } else txt(c, s.net, r.left + 8 * u, r.top + 24 * u, 8f, Color.WHITE, bold = true)
        txt(c, fit("DATA v" + WallData.fmtBytes(s.rxTotal) + " ^" + WallData.fmtBytes(s.txTotal), 7.2f, r.width() - 14 * u), r.left + 8 * u, r.top + 38 * u, 7.2f, col(215))
    }

    private fun wEvent(c: Canvas, r: RectF) {
        panel(c, r, "// NEXT EVENT")
        val e = WallData.snap?.nextEvent ?: ""
        if (e.isEmpty()) { txt(c, "calendar mein kuch nahi", r.left + 8 * u, r.top + 28 * u, 7.8f, col(170)); return }
        val ls = wrap(e, 7.8f, r.width() - 14 * u, 2)
        for ((i, l) in ls.withIndex()) txt(c, l, r.left + 8 * u, r.top + 24 * u + i * 9 * u, 7.8f, if (i == 0) Color.WHITE else col(225))
    }

    /** NOVA ka browser jo abhi kar raha hai, uski live (halki) tasveer. Video wali jagah kaali ho sakti hai. */
    private fun wBrowser(c: Canvas, r: RectF) {
        panel(c, r, "// NOVA BROWSER", true)
        val bm = BrowserMirror.frame
        val top = r.top + 16 * u; val bottom = r.bottom - 14 * u
        if (bm != null && bm.width > 0 && bm.height > 0 && now - BrowserMirror.frameAt < 5000L) {
            val sc = min((r.width() - 14 * u) / bm.width, (bottom - top) / bm.height)
            val dw = bm.width * sc; val dh = bm.height * sc
            val dx = r.centerX() - dw / 2f
            rf.set(dx, top, dx + dw, top + dh)
            c.drawBitmap(bm, null, rf, null)
            line.strokeWidth = 1f * u; line.color = col(160); c.drawRect(rf, line)
        } else txt(c, "page load ho raha hai...", r.left + 8 * u, r.top + 40 * u, 8f, col(200))
        txt(c, fit(BrowserMirror.label.ifEmpty { "NOVA browser" }, 7.6f, r.width() - 14 * u), r.left + 8 * u, r.bottom - 4 * u, 7.6f, col(220))
    }

    private fun wNova(c: Canvas, r: RectF) {
        val srv = if (MiniServer.root != null) "SRV :" + MiniServer.PORT else "SRV off"
        val dl = if (WallBus.dlLine.isNotEmpty() && now - WallBus.dlAt < 120_000L) "DL " + WallBus.dlLine else "DL idle"
        val s = "NOVA tools:${WallBus.toolCount} msgs:${WallBus.msgCount} | $srv | $dl"
        txt(c, fit(s, 7.6f, r.width() - 4 * u), r.left + 2 * u, r.centerY() + 3 * u, 7.6f, col(210))
    }

    private fun wAction(c: Canvas, r: RectF) {
        if (WallBus.actionLine.isEmpty()) return
        txt(c, fit("LAST > " + WallBus.actionLine, 7.8f, r.width() - 4 * u), r.left + 2 * u, r.centerY() + 3 * u, 7.8f, col(220))
    }

    /** v30: 5 wallpaper styles ke liye clock alag jagah/font mein (center bada / corner chhota / bilkul nahi). */
    private fun drawClockStyle(c: Canvas, cs: Int) {
        val d = Date(now)
        val time = fmtHms.format(d); val date = fmtDate.format(d)
        val font = clockFonts[(cs - 1).coerceIn(0, clockFonts.size - 1)]
        tp.typeface = font
        when (cs) {
            1 -> {
                tp.textAlign = Paint.Align.CENTER
                tp.textSize = 46f * u; tp.color = col(235, Color.WHITE)
                c.drawText(time, w / 2f, h * 0.46f, tp)
                tp.textSize = 12f * u; tp.color = col(220)
                c.drawText(date, w / 2f, h * 0.46f + 20 * u, tp)
            }
            2 -> {
                tp.textAlign = Paint.Align.LEFT
                tp.textSize = 15f * u; tp.color = col(235, Color.WHITE)
                c.drawText(time, 10 * u, 24 * u, tp)
                tp.textSize = 8f * u; tp.color = col(200)
                c.drawText(date, 10 * u, 34 * u, tp)
            }
            3 -> {
                tp.textAlign = Paint.Align.RIGHT
                tp.textSize = 15f * u; tp.color = col(235, Color.WHITE)
                c.drawText(time, w - 10 * u, 24 * u, tp)
                tp.textSize = 8f * u; tp.color = col(200)
                c.drawText(date, w - 10 * u, 34 * u, tp)
            }
        }
        tp.textAlign = Paint.Align.LEFT
    }
}
