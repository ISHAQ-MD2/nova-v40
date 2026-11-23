package com.nova.app

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Gemini REST (generateContent) - model khud dhoondhta hai.
 * "models/gemini-2.0-flash is no longer available (404)" jaisa error ab nahi aayega:
 *  1) Setup wala apna model  2) pichli baar jo chala tha  3) naye model naam  4) Google se ListModels mangwa kar sabse naya flash model.
 */
object GeminiRest {
    class Result(val ok: Boolean, val text: String, val model: String = "")

    private val http = Net.client(readTimeoutSec = 90)
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta"
    private val PREFERRED = listOf("gemini-3.8-flash", "gemini-3.5-flash", "gemini-3.1-flash", "gemini-3-flash", "gemini-2.5-flash", "gemini-flash-latest")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("nova", Context.MODE_PRIVATE)

    private fun candidates(ctx: Context): MutableList<String> {
        val p = prefs(ctx)
        val out = LinkedHashSet<String>()
        p.getString("model", "")?.trim()?.takeIf { it.isNotEmpty() && !it.contains("live") && !it.contains("audio") }?.let { out.add(it.removePrefix("models/")) }
        p.getString("rest_ok", "")?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        out.addAll(PREFERRED)
        return out.toMutableList()
    }

    private fun versionOf(name: String): Double =
        Regex("gemini-(\\d+(?:\\.\\d+)?)").find(name)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

    /** Google se abhi ki model list: generateContent chalane wale flash models, naye pehle. */
    private fun discover(key: String): List<String> = try {
        val r = http.newCall(Request.Builder().url("$BASE/models?pageSize=200&key=$key").build()).execute()
        val txt = r.body?.string() ?: ""; r.close()
        val arr = JSONObject(txt).optJSONArray("models")
        val names = ArrayList<String>()
        if (arr != null) for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            val n = m.optString("name").removePrefix("models/")
            val methods = m.optJSONArray("supportedGenerationMethods")
            var gen = false
            if (methods != null) for (j in 0 until methods.length()) if (methods.optString(j) == "generateContent") gen = true
            val bad = listOf("live", "tts", "image", "audio", "embedding", "robotics", "computer-use", "learnlm", "gemma")
            if (gen && n.startsWith("gemini") && n.contains("flash") && bad.none { n.contains(it) }) names.add(n)
        }
        names.sortedWith(compareByDescending<String> { versionOf(it) }
            .thenBy { it.contains("preview") || it.contains("exp") }.thenBy { it.contains("lite") })
    } catch (_: Exception) { emptyList() }

    private fun modelGone(code: Int, txt: String): Boolean {
        val t = txt.lowercase()
        return code == 404 || (code == 400 && (t.contains("not found") || t.contains("not supported") || t.contains("no longer available")))
    }

    /** body mein systemInstruction / contents / tools do; "model" khud lag jata hai. */
    fun post(ctx: Context, body: JSONObject): Result {
        var key = Keys.active(ctx)
        if (key.isEmpty()) return Result(false, "API key nahi mili - Setup mein daalo")
        val list = candidates(ctx)
        val tried = HashSet<String>()
        var discovered = false
        var i = 0
        var lastErr = ""
        var rotated = false
        while (i < list.size) {
            val model = list[i++]
            if (!tried.add(model)) continue
            var resp: okhttp3.Response? = null
            var netErr = ""
            for (attempt in 1..3) {
                try {
                    resp = http.newCall(Request.Builder().url("$BASE/models/$model:generateContent?key=$key")
                        .post(body.toString().toRequestBody("application/json".toMediaType())).build()).execute()
                    break
                } catch (e: java.io.IOException) { netErr = e.message ?: "network"; Thread.sleep(2500L * attempt) }
            }
            if (resp == null) return Result(false, "Internet ka masla: " + Net.friendly(ctx, netErr))
            val txt = resp.body?.string() ?: ""
            val code = resp.code
            val ok = resp.isSuccessful
            resp.close()
            if (ok) {
                prefs(ctx).edit().putString("rest_ok", model).apply()
                return Result(true, txt, model)
            }
            if (prefs(ctx).getString("rest_ok", "") == model) prefs(ctx).edit().remove("rest_ok").apply()
            lastErr = "API error $code ($model): ${txt.take(160)}"
            if ((code == 429 || code == 403) && !rotated) { rotated = true; val alt = Keys.rotate(ctx); if (alt != null && alt != key) { key = alt; tried.remove(model); i-- } }
            if (modelGone(code, txt) || code == 429 || code == 503 || code == 500) {
                if (i >= list.size && !discovered) {
                    discovered = true
                    list.addAll(discover(key).filter { it !in tried })
                }
                continue
            }
            return Result(false, lastErr)
        }
        return Result(false, "Koi chalne wala Gemini model nahi mila. Aakhri error: $lastErr")
    }
}
