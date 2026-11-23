package com.nova.app

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.*
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.sin

class SItem(val icon: String, val title: String, val sub: () -> String, val on: (() -> Boolean)? = null, val action: () -> Unit)
class SSection(val title: String, val items: List<SItem>)

/** Settings ka row: glass card, ghoomti chamak, animated toggle switch. Subtitle/ON-OFF har baar live padha jata hai. */
class SettingRow(ctx: Context, private val item: SItem, var accent: Int) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path(); private val pos = FloatArray(2); private val rr = RectF(); private val tr = RectF()
    private var knob = if (item.on?.invoke() == true) 1f else 0f
    private var shown = true
    init { isClickable = true; setOnClickListener { item.action(); invalidate() } }
    override fun onWindowVisibilityChanged(v: Int) { super.onWindowVisibilityChanged(v); shown = v == VISIBLE; if (shown) invalidate() }

    override fun onDraw(c: Canvas) {
        val tt = (System.currentTimeMillis() % 100000L) / 1000f
        val breath = sin(tt * 1.6f + (item.title.length)) * 0.5f + 0.5f
        val w = width.toFloat(); val h = height.toFloat(); val pad = 6 * d
        rr.set(pad, pad, w - pad, h - pad)
        p.style = Paint.Style.FILL; p.color = Color.argb(190, 10, 7, 16); c.drawRoundRect(rr, 18 * d, 18 * d, p)
        p.style = Paint.Style.STROKE
        for ((i, a) in intArrayOf(22, 40).withIndex()) {
            p.strokeWidth = (5 - i * 2) * d; p.color = accent.withAlpha((a * (0.6f + 0.8f * breath)).toInt())
            tr.set(rr.left - (2 - i) * d, rr.top - (2 - i) * d, rr.right + (2 - i) * d, rr.bottom + (2 - i) * d); c.drawRoundRect(tr, 19 * d, 19 * d, p)
        }
        p.strokeWidth = 1.3f * d; p.color = accent.withAlpha(200); c.drawRoundRect(rr, 18 * d, 18 * d, p)
        // daudti roshni
        path.reset(); path.addRoundRect(rr, 18 * d, 18 * d, Path.Direction.CW)
        val pm = PathMeasure(path, false); val len = pm.length
        p.style = Paint.Style.FILL
        for (k in 0 until 5) {
            val f = (((tt * 0.16f) + item.title.length * 0.07f - k * 0.012f) % 1f + 1f) % 1f
            pm.getPosTan(f * len, pos, null); p.color = Color.WHITE.withAlpha(230 - k * 40); c.drawCircle(pos[0], pos[1], (2.8f - k * 0.3f) * d, p)
        }
        // icon gol mein
        val icx = rr.left + 30 * d; val icy = h / 2
        p.color = accent.withAlpha(40); c.drawCircle(icx, icy, 19 * d, p)
        p.color = accent; Icons.draw(c, item.icon, icx, icy, 20 * d, p)
        // text
        val tx = rr.left + 60 * d; val right = rr.right - (if (item.on != null) 62 * d else 38 * d)
        tp.textAlign = Paint.Align.LEFT
        tp.textSize = 15f * d; tp.color = Color.WHITE; tp.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        c.drawText(TextUtils.ellipsize(item.title, tp, right - tx, TextUtils.TruncateAt.END).toString(), tx, h / 2 - 3 * d, tp)
        tp.textSize = 11.5f * d; tp.color = Color.argb(190, 200, 210, 225); tp.typeface = Typeface.DEFAULT
        val sub = try { item.sub() } catch (_: Exception) { "" }
        if (sub.isNotEmpty()) c.drawText(TextUtils.ellipsize(sub, tp, right - tx, TextUtils.TruncateAt.END).toString(), tx, h / 2 + 15 * d, tp)
        // toggle ya chevron
        val on = item.on?.invoke()
        if (on != null) {
            val target = if (on) 1f else 0f; knob += (target - knob) * 0.25f
            val sw = RectF(rr.right - 56 * d, h / 2 - 12 * d, rr.right - 14 * d, h / 2 + 12 * d)
            p.style = Paint.Style.FILL; p.color = if (on) accent.withAlpha(200) else Color.argb(120, 130, 140, 160); c.drawRoundRect(sw, 12 * d, 12 * d, p)
            val kx = sw.left + 12 * d + knob * (sw.width() - 24 * d)
            p.color = Color.WHITE; c.drawCircle(kx, h / 2, 9 * d, p)
        } else {
            p.color = Color.argb(180, 180, 190, 210); Icons.draw(c, "chevron", rr.right - 22 * d, h / 2, 18 * d, p)
        }
        if (shown) postInvalidateDelayed(45)
    }
}

object SettingsPanel {
    /** Poori screen ka live Settings panel (peeche halka blur wallpaper chalta rehta hai). */
    fun show(act: Activity, accent: Int, sections: List<SSection>): Dialog {
        val d = act.resources.displayMetrics.density
        fun dp(x: Int) = (x * d).toInt()
        val dlg = Dialog(act, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = FrameLayout(act)
        val bg = CharacterView(act).apply { applyPrefs(); clockStyle = 0; setBlur(85, 0); hudOn = true; setInsets(0, 0) }
        root.addView(bg, FrameLayout.LayoutParams(-1, -1))
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val sid = act.resources.getIdentifier("status_bar_height", "dimen", "android")
        val sb = if (sid > 0) act.resources.getDimensionPixelSize(sid) else dp(24)
        val head = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), sb + dp(6), dp(10), dp(6)) }
        val back = GlowCircle(act).apply { icon = "chevron"; this.accent = accent; iconScale = 0.4f; rotation = 180f; setOnClickListener { dlg.dismiss() } }
        val title = TextView(act).apply { text = "SETTINGS"; textSize = 24f; letterSpacing = 0.3f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD); setShadowLayer(24f, 0f, 0f, accent) }
        val bolt = GlowCircle(act).apply { icon = "bolt"; this.accent = accent; iconScale = 0.4f }
        head.addView(back, LinearLayout.LayoutParams(dp(54), dp(54)))
        head.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(bolt, LinearLayout.LayoutParams(dp(54), dp(54)))
        col.addView(head, LinearLayout.LayoutParams(-1, -2))
        val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(4), dp(10), dp(30)) }
        for (sec in sections) {
            list.addView(TextView(act).apply {
                text = sec.title; textSize = 12.5f; letterSpacing = 0.22f; setTextColor(accent); typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setPadding(dp(8), dp(16), 0, dp(4)); setShadowLayer(12f, 0f, 0f, accent)
            })
            for (it in sec.items) list.addView(SettingRow(act, it, accent), LinearLayout.LayoutParams(-1, dp(68)))
        }
        col.addView(ScrollView(act).apply { isVerticalScrollBarEnabled = false; addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(col, FrameLayout.LayoutParams(-1, -1))
        dlg.setContentView(root)
        dlg.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.BLACK))
        dlg.show()
        return dlg
    }
}
