package com.nova.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

/**
 * Azan / namaz ke auqaat - bina internet, location ke hisaab se hisaab lagakar.
 * Default: Dera Ghazi Khan, Multan division, Punjab, Pakistan. Method: Karachi (Fajr 18, Isha 18), Asr: Hanafi.
 * Note: apni masjid ke jantri se 1-3 minute ka farq ho sakta hai.
 */
object Prayer {
    const val LAT = 30.0489
    const val LON = 70.6402
    private const val TZ = "Asia/Karachi"
    val NAMES = listOf("Fajr", "Sunrise", "Zuhr", "Asr", "Maghrib", "Isha")
    private val AZAN = setOf("Fajr", "Zuhr", "Asr", "Maghrib", "Isha")

    private fun rad(d: Double) = Math.toRadians(d)
    private fun deg(r: Double) = Math.toDegrees(r)
    private fun fix(x: Double, m: Double): Double { var r = x % m; if (r < 0) r += m; return r }

    private fun julian(y: Int, m: Int, d: Int): Double {
        var yy = y; var mm = m
        if (mm <= 2) { yy -= 1; mm += 12 }
        val a = floor(yy / 100.0); val b = 2 - a + floor(a / 4)
        return floor(365.25 * (yy + 4716)) + floor(30.6001 * (mm + 1)) + d + b - 1524.5
    }

    /** sun declination + equation of time (hours) */
    private fun sunPos(jd: Double): Pair<Double, Double> {
        val d = jd - 2451545.0
        val g = fix(357.529 + 0.98560028 * d, 360.0)
        val q = fix(280.459 + 0.98564736 * d, 360.0)
        val l = fix(q + 1.915 * sin(rad(g)) + 0.020 * sin(rad(2 * g)), 360.0)
        val e = 23.439 - 0.00000036 * d
        val ra = deg(atan2(cos(rad(e)) * sin(rad(l)), cos(rad(l)))) / 15.0
        val eqt = q / 15.0 - fix(ra, 24.0)
        val decl = deg(asin(sin(rad(e)) * sin(rad(l))))
        return decl to (if (eqt > 12) eqt - 24 else if (eqt < -12) eqt + 24 else eqt)
    }

    /** Aaj (ya di gayi date) ke auqaat: naam -> epoch millis */
    fun times(cal: Calendar = Calendar.getInstance(TimeZone.getTimeZone(TZ))): LinkedHashMap<String, Long> {
        val tz = TimeZone.getTimeZone(TZ)
        val c = Calendar.getInstance(tz).apply { timeInMillis = cal.timeInMillis }
        val y = c.get(Calendar.YEAR); val m = c.get(Calendar.MONTH) + 1; val d = c.get(Calendar.DAY_OF_MONTH)
        val offH = tz.getOffset(c.timeInMillis) / 3600000.0
        val jd = julian(y, m, d) - LON / (15.0 * 24.0)
        val (decl, eqt) = sunPos(jd + 0.5)
        val noon = 12 + offH - LON / 15.0 - eqt
        fun angleTime(angle: Double, ccw: Boolean): Double {
            val v = (-sin(rad(angle)) - sin(rad(decl)) * sin(rad(LAT))) / (cos(rad(decl)) * cos(rad(LAT)))
            val t = deg(acos(v.coerceIn(-1.0, 1.0))) / 15.0
            return if (ccw) noon - t else noon + t
        }
        val fajr = angleTime(18.0, true)
        val sunrise = angleTime(0.833, true)
        val maghrib = angleTime(0.833, false)
        val isha = angleTime(18.0, false)
        // Asr (Hanafi: shadow = 2x)
        val a = atan(1.0 / (2 + tan(rad(abs(LAT - decl)))))
        val va = (sin(a) - sin(rad(decl)) * sin(rad(LAT))) / (cos(rad(decl)) * cos(rad(LAT)))
        val asr = noon + deg(acos(va.coerceIn(-1.0, 1.0))) / 15.0
        val zuhr = noon + 2.0 / 60.0   // zawal ke baad 2 minute ehtiyat
        val base = Calendar.getInstance(tz).apply { set(y, m - 1, d, 0, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        fun ms(h: Double) = base + (h * 3600000.0).toLong()
        return linkedMapOf("Fajr" to ms(fajr), "Sunrise" to ms(sunrise), "Zuhr" to ms(zuhr),
            "Asr" to ms(asr), "Maghrib" to ms(maghrib), "Isha" to ms(isha))
    }

    private fun fmt(ms: Long): String = SimpleDateFormat("hh:mm a", Locale.US).apply { timeZone = TimeZone.getTimeZone(TZ) }.format(Date(ms))

    fun todayText(): String {
        val t = times()
        val now = System.currentTimeMillis()
        val nxt = nextPrayer(now)
        val sb = StringBuilder("Dera Ghazi Khan, Punjab - aaj ke auqaat:\n")
        t.forEach { (k, v) -> sb.append(if (k == "Sunrise") "Sunrise (ishraq)" else k).append(": ").append(fmt(v)).append('\n') }
        if (nxt != null) sb.append("Agli namaz: ${nxt.first} ${fmt(nxt.second)} (${(nxt.second - now) / 60000} minute baad)")
        if (Calendar.getInstance(TimeZone.getTimeZone(TZ)).get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY) sb.append("\nAaj Jummah hai.")
        return sb.toString()
    }

    /** Agli azan wali namaz (aaj ya kal ki Fajr). */
    fun nextPrayer(now: Long): Pair<String, Long>? {
        val today = times()
        today.entries.firstOrNull { it.key in AZAN && it.value > now }?.let { return it.key to it.value }
        val tomorrow = Calendar.getInstance(TimeZone.getTimeZone(TZ)).apply { add(Calendar.DAY_OF_MONTH, 1) }
        val t = times(tomorrow)
        return "Fajr" to (t["Fajr"] ?: return null)
    }

    private fun pi(ctx: Context, name: String) = PendingIntent.getBroadcast(ctx, 9000 + NAMES.indexOf(name).coerceAtLeast(0),
        Intent(ctx, PrayerReceiver::class.java).putExtra("name", name), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Sirf agli namaz ka alarm lagata hai; baje to receiver agli lagata hai. */
    fun scheduleNext(ctx: Context) {
        if (!ctx.getSharedPreferences("nova", Context.MODE_PRIVATE).getBoolean("azan", true)) return
        val n = nextPrayer(System.currentTimeMillis()) ?: return
        val am = ctx.getSystemService(AlarmManager::class.java)
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, n.second, pi(ctx, n.first))
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, n.second, pi(ctx, n.first))
        } catch (_: Exception) {}
    }

    fun cancelAll(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        NAMES.forEach { try { am.cancel(pi(ctx, it)) } catch (_: Exception) {} }
    }
}

class PrayerReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val name = i.getStringExtra("name") ?: "namaz"
        Prayer.scheduleNext(ctx)
        val line = "Sir, ${name} ki azan ka waqt ho gaya hai. Namaz ke liye tayyar ho jaiye."
        Notifier.show(ctx, "🕌 $name ki azan", line)
        Unlocker.wake(ctx, 15000)
        RobotPet.say(ctx, line)
        Thread { Notifier.speakBlocking(ctx, line, 20000) }.start()
    }
}
