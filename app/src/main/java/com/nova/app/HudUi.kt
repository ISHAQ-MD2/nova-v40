package com.nova.app

import android.app.ActivityManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/** v12: Cyber HUD look (neon cyan on dark) - header, stat cards, bottom nav. */
object HudUi {
    const val BG = "#050B14"
    const val PANEL = "#0A1626"
    const val CYAN = "#00E5FF"
    const val GREEN = "#00FF9C"
    const val RED = "#FF4D5E"
    const val DIM = "#6F8FA3"

    fun dp(c: Context, x: Int) = (x * c.resources.displayMetrics.density).toInt()

    fun neon(c: Context, fill: String = PANEL, stroke: String = CYAN, radius: Int = 14, w: Int = 1) = GradientDrawable().apply {
        setColor(Color.parseColor(fill)); cornerRadius = dp(c, radius).toFloat(); setStroke(dp(c, w), Color.parseColor(stroke))
    }

    fun label(c: Context, t: String, size: Float, color: String, bold: Boolean = false) = TextView(c).apply {
        text = t; textSize = size; setTextColor(Color.parseColor(color))
        typeface = if (bold) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.MONOSPACE
    }

    /** "GOOD EVENING, Commander" + chhota status card. */
    fun header(c: Context, user: String): Pair<LinearLayout, TextView> {
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val greet = when { h < 5 -> "GOOD NIGHT"; h < 12 -> "GOOD MORNING"; h < 17 -> "GOOD AFTERNOON"; else -> "GOOD EVENING" }
        val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 4)) }
        box.addView(label(c, greet, 10f, DIM))
        box.addView(label(c, user.ifBlank { "Commander" }, 22f, "#FFFFFF", true))
        val card = label(c, "NOVA ko bolo ya likho...", 13f, CYAN).apply {
            background = neon(c, "#07182A", CYAN, 16, 2); setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12))
        }
        box.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(c, 10) })
        return box to card
    }

    class Stat(val root: LinearLayout, val value: TextView, val sub: TextView)

    fun stat(c: Context, name: String): Stat {
        val r = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; background = neon(c, PANEL, "#16384F", 12); setPadding(dp(c, 10), dp(c, 8), dp(c, 10), dp(c, 8)) }
        r.addView(label(c, "● $name", 8f, DIM))
        val v = label(c, "--", 20f, "#FFFFFF", true); r.addView(v)
        val s = label(c, "", 8f, GREEN, true); r.addView(s)
        return Stat(r, v, s)
    }

    fun statsRow(c: Context): Triple<LinearLayout, () -> Unit, Unit> {
        val sys = stat(c, "SYSTEM"); val net = stat(c, "NETWORK"); val bat = stat(c, "BATTERY")
        val row = LinearLayout(c).apply { setPadding(dp(c, 12), dp(c, 6), dp(c, 12), dp(c, 6)) }
        for (s in listOf(sys, net, bat)) row.addView(s.root, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(c, 3), 0, dp(c, 3), 0) })
        val refresh = {
            try {
                val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val mi = ActivityManager.MemoryInfo(); am.getMemoryInfo(mi)
                val free = (100 * mi.availMem / mi.totalMem).toInt()
                sys.value.text = "$free%"; sys.sub.text = if (free > 20) "OPTIMAL" else "LOW RAM"
                sys.sub.setTextColor(Color.parseColor(if (free > 20) GREEN else RED))
                val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val nc = cm.getNetworkCapabilities(cm.activeNetwork)
                val on = nc?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                net.value.text = if (on) "ONLINE" else "OFFLINE"; net.value.textSize = 15f
                net.sub.text = if (on) (if (nc?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) "WI-FI" else "MOBILE DATA") else "OFFLINE VOICE OK"
                net.sub.setTextColor(Color.parseColor(if (on) GREEN else "#FFC107"))
                val bm = c.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                val p = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                bat.value.text = "$p%"
                bat.sub.text = if (bm.isCharging) "CHARGING" else "DISCHARGING"
                bat.sub.setTextColor(Color.parseColor(if (bm.isCharging || p > 20) GREEN else RED))
            } catch (_: Exception) {}
        }
        return Triple(row, refresh, Unit)
    }

    /** Neeche ka nav bar: Home / Terminal / Orb / Offline / Profile. */
    fun nav(c: Context, items: List<Pair<String, () -> Unit>>): LinearLayout {
        val bar = LinearLayout(c).apply { background = neon(c, "#07101C", "#16384F", 0); setPadding(0, dp(c, 6), 0, dp(c, 6)) }
        for ((t, a) in items) {
            val tv = label(c, t, 11f, CYAN).apply { gravity = Gravity.CENTER; setPadding(0, dp(c, 8), 0, dp(c, 8)); setOnClickListener { a() } }
            bar.addView(tv, LinearLayout.LayoutParams(0, -2, 1f))
        }
        return bar
    }
}
