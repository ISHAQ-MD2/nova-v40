package com.nova.app

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * Trading analysis (sirf analysis - guarantee nahi, auto-trade nahi):
 *  - analyze_candles : Binance public candles (crypto, jaise BTCUSDT) -> EMA, RSI, candle patterns, support/resistance -> UP / DOWN / WAIT
 *  - analyze_chart   : screen par khula chart (TradingView, Exness...) ka screenshot Gemini ko dikha kar candles padhwana
 *  - watch_*         : upar wale kaam har N minute par khud (reminder ki tarah), notification + awaaz
 */
object Trading {
    private class C(val o: Double, val h: Double, val l: Double, val c: Double, val v: Double)

    private const val DISCLAIMER = "Yeh sirf analysis hai, guarantee nahi - apna risk khud dekho."

    // ---------- candles from Binance ----------
    private fun fetch(symbol: String, interval: String, limit: Int = 100): List<C> {
        val url = "https://api.binance.com/api/v3/klines?symbol=${symbol.uppercase().replace("/", "").replace("-", "")}&interval=$interval&limit=$limit"
        val r = Net.client(readTimeoutSec = 20).newCall(Request.Builder().url(url).build()).execute()
        val txt = r.body?.string() ?: ""; val code = r.code; r.close()
        if (code != 200) throw RuntimeException("candle data nahi mila (HTTP $code) - symbol sahi likho jaise BTCUSDT: ${txt.take(100)}")
        val a = JSONArray(txt)
        return (0 until a.length()).map { val k = a.getJSONArray(it); C(k.getString(1).toDouble(), k.getString(2).toDouble(), k.getString(3).toDouble(), k.getString(4).toDouble(), k.getString(5).toDouble()) }
    }

    private fun ema(v: List<Double>, n: Int): Double {
        val k = 2.0 / (n + 1); var e = v.take(n).average()
        for (i in n until v.size) e = v[i] * k + e * (1 - k)
        return e
    }

    private fun rsi(v: List<Double>, n: Int = 14): Double {
        var g = 0.0; var l = 0.0
        for (i in 1..n) { val d = v[i] - v[i - 1]; if (d > 0) g += d else l -= d }
        g /= n; l /= n
        for (i in n + 1 until v.size) { val d = v[i] - v[i - 1]; g = (g * (n - 1) + maxOf(d, 0.0)) / n; l = (l * (n - 1) + maxOf(-d, 0.0)) / n }
        return if (l == 0.0) 100.0 else 100 - 100 / (1 + g / l)
    }

    /** Aakhri candles ke patterns: +ve = bullish, -ve = bearish. */
    private fun patterns(c: List<C>): Pair<List<String>, Int> {
        val out = ArrayList<String>(); var sc = 0
        val x = c.last(); val p = c[c.size - 2]
        val body = abs(x.c - x.o); val range = (x.h - x.l).coerceAtLeast(1e-9)
        val up = x.h - maxOf(x.o, x.c); val lo = minOf(x.o, x.c) - x.l
        if (body / range < 0.1) out.add("Doji (faisla nahi - palat sakta hai)")
        if (lo > body * 2 && up < body && body / range > 0.05) { out.add("Hammer (neeche se kharidari)"); sc += 1 }
        if (up > body * 2 && lo < body && body / range > 0.05) { out.add("Shooting star (upar se bikri)"); sc -= 1 }
        if (x.c > x.o && p.c < p.o && x.c >= p.o && x.o <= p.c) { out.add("Bullish engulfing (upar ka signal)"); sc += 2 }
        if (x.c < x.o && p.c > p.o && x.o >= p.c && x.c <= p.o) { out.add("Bearish engulfing (neeche ka signal)"); sc -= 2 }
        if (body / range > 0.85) { if (x.c > x.o) { out.add("Bullish marubozu (tez buyers)"); sc += 1 } else { out.add("Bearish marubozu (tez sellers)"); sc -= 1 } }
        if (c.size >= 3) {
            val q = c[c.size - 3]
            if (q.c < q.o && abs(p.c - p.o) < (p.h - p.l) * 0.3 && x.c > x.o && x.c > (q.o + q.c) / 2) { out.add("Morning star (neeche se palat)"); sc += 2 }
            if (q.c > q.o && abs(p.c - p.o) < (p.h - p.l) * 0.3 && x.c < x.o && x.c < (q.o + q.c) / 2) { out.add("Evening star (upar se palat)"); sc -= 2 }
        }
        return out to sc
    }

    fun analyzeCandles(ctx: Context, symbol: String, interval: String): String {
        return try {
            val iv = interval.ifBlank { "15m" }
            val c = fetch(symbol, iv)
            if (c.size < 30) return "kaafi candles nahi mili"
            val cl = c.map { it.c }
            val e9 = ema(cl, 9); val e21 = ema(cl, 21); val r = rsi(cl)
            val (pat, ps) = patterns(c)
            val last = c.last().c
            val sup = c.takeLast(30).minOf { it.l }; val res = c.takeLast(30).maxOf { it.h }
            var score = ps
            score += if (e9 > e21) 1 else -1
            score += if (last > e21) 1 else -1
            if (r > 70) score -= 1 else if (r < 30) score += 1
            val last5 = c.takeLast(5)
            score += if (last5.last().c > last5.first().o) 1 else -1
            val sig = when { score >= 3 -> "UP"; score <= -3 -> "DOWN"; else -> "WAIT" }
            val data = "SIGNAL: $sig\n$symbol $iv | price ${"%.4f".format(last)} | EMA9 ${"%.4f".format(e9)} ${if (e9 > e21) ">" else "<"} EMA21 ${"%.4f".format(e21)} | RSI ${"%.1f".format(r)} | " +
                "support ${"%.4f".format(sup)} resistance ${"%.4f".format(res)} | patterns: ${if (pat.isEmpty()) "koi khaas nahi" else pat.joinToString(", ")} | score $score"
            val lastRows = c.takeLast(12).joinToString("\n") { "O${"%.4f".format(it.o)} H${"%.4f".format(it.h)} L${"%.4f".format(it.l)} C${"%.4f".format(it.c)}" }
            val explain = explain(ctx, "Technical analyst. Data (computed locally):\n$data\nLast 12 candles:\n$lastRows\n" +
                "Reply in Hinglish, max 5 short lines: pehli line exactly 'SIGNAL: UP' or 'SIGNAL: DOWN' or 'SIGNAL: WAIT' (agree with data unless candles clearly contradict), " +
                "phir kyun, kaunsa level toot/ruk jaye to palat sakta hai, agli 1-3 candles ka andaza. Last line: '$DISCLAIMER'", null)
            explain ?: ("$data\n$DISCLAIMER")
        } catch (e: Exception) { "candle analysis fail: ${e.message}" }
    }

    // ---------- screenshot chart ----------
    fun analyzeScreen(ctx: Context, note: String): String {
        val acc = NovaAccessibilityService.instance ?: return "Accessibility ON nahi hai"
        val bmp = acc.takeBitmap() ?: return "screenshot nahi ho saka (Android 11+ chahiye; kuch trading apps screenshot block karti hain)"
        val jpg = Media.jpeg(bmp, 1024)
        return explain(ctx, "Yeh trading chart ka screenshot hai. $note\nProfessional technical analyst ki tarah: instrument/timeframe (agar dikhe), trend, aakhri 5-10 candles ke patterns " +
            "(doji, hammer, engulfing, shooting star, morning/evening star...), support/resistance, dikhte indicators (RSI/MACD/MA). " +
            "Reply Hinglish, max 6 lines: pehli line exactly 'SIGNAL: UP' / 'SIGNAL: DOWN' / 'SIGNAL: WAIT', phir wajah, key level jo toot jaye to view badal jaye, agli 1-3 candles ka andaza. " +
            "Agar screen par chart nahi hai to saaf bolo. Last line: '$DISCLAIMER'", jpg) ?: "Gemini se jawab nahi mila"
    }

    private fun explain(ctx: Context, prompt: String, jpeg: ByteArray?): String? {
        val parts = JSONArray().put(JSONObject().put("text", prompt))
        if (jpeg != null) parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))))
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
        val r = GeminiRest.post(ctx, body)
        if (!r.ok) return null
        return try {
            val ps = JSONObject(r.text).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
            (0 until ps.length()).joinToString("") { ps.getJSONObject(it).optString("text") }.trim().ifEmpty { null }
        } catch (_: Exception) { null }
    }

    // ---------- regular watching ----------
    fun schedule(ctx: Context, instruction: String, everyMin: Int, forMin: Int): String {
        val ev = everyMin.coerceAtLeast(1)
        val now = System.currentTimeMillis()
        val until = if (forMin > 0) now + forMin * 60_000L else now + 8 * 3600_000L
        val id = TaskStore.add(ctx, now + 5_000L, instruction, false, ev, until)
        Memory.log("schedule", "trading watch #$id har $ev min: $instruction")
        return "watch #$id shuru: har $ev minute par analysis, ${(until - now) / 60000} minute tak. Rokne ke liye cancel_task($id)."
    }

    fun runWatch(ctx: Context, instruction: String): String {
        val p = ctx.getSharedPreferences("nova", Context.MODE_PRIVATE)
        val res = if (instruction.startsWith("candles_watch:")) {
            val a = instruction.substringAfter(":").split("|")
            analyzeCandles(ctx, a.getOrElse(0) { "BTCUSDT" }, a.getOrElse(1) { "15m" })
        } else {
            val a = instruction.substringAfter(":").split("|")
            val app = a.getOrElse(0) { "" }.trim()
            if (app.isNotEmpty()) { Tools.execute(ctx, "open_app", JSONObject().put("name", app)); Thread.sleep(4500) }
            analyzeScreen(ctx, a.getOrElse(1) { "" })
        }
        val sig = Regex("SIGNAL:\\s*(UP|DOWN|WAIT)").find(res)?.groupValues?.get(1) ?: "?"
        val prev = p.getString("last_signal_" + instruction.hashCode(), "")
        p.edit().putString("last_signal_" + instruction.hashCode(), sig).apply()
        Memory.log("trading", res.take(500))
        return (if (prev != sig && prev != "") "BADLAV: $prev -> $sig\n" else "") + res
    }

    fun spokenLine(res: String): String {
        val sig = Regex("SIGNAL:\\s*(UP|DOWN|WAIT)").find(res)?.groupValues?.get(1)
        val head = when (sig) { "UP" -> "Sir, candles upar jaane ka signal hai"; "DOWN" -> "Sir, candles neeche jaane ka signal hai"; "WAIT" -> "Sir, abhi wait karo, saaf signal nahi"; else -> "Sir, trading update" }
        val why = res.lines().filter { it.isNotBlank() && !it.startsWith("SIGNAL") && !it.startsWith("BADLAV") && !it.contains("guarantee") }.take(2).joinToString(" ")
        return "$head. ${why.take(220)} Yeh guarantee nahi hai."
    }
}
