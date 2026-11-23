package com.nova.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Asli mausam (Dera Ghazi Khan) - Open-Meteo se, key ki zaroorat nahi. Net na ho to aakhri save kiya hua dikhata hai. */
object Weather {
    private const val URL_S = "https://api.open-meteo.com/v1/forecast?latitude=30.0489&longitude=70.6402&current=temperature_2m,weather_code&timezone=Asia%2FKarachi"
    private const val STALE = 20 * 60_000L

    fun describe(code: Int): String = when (code) {
        0 -> "Saaf"; 1, 2 -> "Halka badal"; 3 -> "Badal"; 45, 48 -> "Dhund"
        in 51..57 -> "Phuhaar"; in 61..67, in 80..82 -> "Baarish"; in 71..77, 85, 86 -> "Barf"; in 95..99 -> "Toofan"; else -> "Mausam"
    }

    private fun p(ctx: Context) = ctx.getSharedPreferences("nova", Context.MODE_PRIVATE)

    /** (temp "27°C", haal "Badal", code) ya null */
    fun cached(ctx: Context): Triple<String, String, Int>? {
        val s = p(ctx); val t = s.getString("wx_t", null) ?: return null
        return Triple(t, s.getString("wx_c", "") ?: "", s.getInt("wx_code", 3))
    }

    fun ageMin(ctx: Context): Long = (System.currentTimeMillis() - p(ctx).getLong("wx_at", 0L)) / 60000L

    /** Background thread se bulao. true = naya data aaya. */
    fun fetchBlocking(ctx: Context): Boolean {
        if (!Net.online(ctx)) return false
        return try {
            val con = URL(URL_S).openConnection() as HttpURLConnection
            con.connectTimeout = 6000; con.readTimeout = 6000
            val j = JSONObject(con.inputStream.bufferedReader().readText()).getJSONObject("current")
            val temp = Math.round(j.getDouble("temperature_2m")).toInt(); val code = j.getInt("weather_code")
            p(ctx).edit().putString("wx_t", "$temp°C").putString("wx_c", describe(code)).putInt("wx_code", code).putLong("wx_at", System.currentTimeMillis()).apply()
            true
        } catch (_: Exception) { false }
    }

    /** UI ke liye: pehle purana dikhao, phir naya (agar purana ho chuka). cb main thread par chalta hai. */
    fun refresh(ctx: Context, force: Boolean = false, cb: () -> Unit) {
        val app = ctx.applicationContext
        val stale = ageMin(app) * 60000L > STALE || cached(app) == null
        if (!force && !stale) { cb(); return }
        Thread { fetchBlocking(app); Handler(Looper.getMainLooper()).post { cb() } }.start()
    }

    /** Bolne ke liye (tool / offline command). */
    fun now(ctx: Context): String {
        if (ageMin(ctx) > 20 || cached(ctx) == null) fetchBlocking(ctx)
        val c = cached(ctx) ?: return "Mausam abhi nahi mil saka (net band hai aur pehle ka data nahi)."
        val age = ageMin(ctx)
        return "Dera Ghazi Khan mein ${c.first}, ${c.second}." + if (age > 30) " (ye ${age / 60} ghante purana data hai)" else ""
    }
}
