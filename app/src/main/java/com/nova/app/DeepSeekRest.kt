package com.nova.app

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * v30: DeepSeek ki ASLI developer API (platform.deepseek.com se key) - koi app kholne/type karne ki zaroorat nahi,
 * seedha HTTP call. Key na ho to AiCode.kt waala "app khol kar type karo" wala tareeqa chalta rehta hai (fallback).
 */
object DeepSeekRest {
    private val http = Net.client(readTimeoutSec = 90)
    private const val URL = "https://api.deepseek.com/chat/completions"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("nova", Context.MODE_PRIVATE)

    fun hasKey(ctx: Context) = Crypto.dec(prefs(ctx).getString("deepseek_key", "") ?: "").isNotBlank()
    fun saveKey(ctx: Context, key: String) { prefs(ctx).edit().putString("deepseek_key", if (key.isBlank()) "" else Crypto.enc(key.trim())).apply() }
    private fun key(ctx: Context) = Crypto.dec(prefs(ctx).getString("deepseek_key", "") ?: "")

    /** null = key nahi hai ya call fail hui (caller AiCode ke UI-automation tareeqe par fallback kare). */
    fun ask(ctx: Context, prompt: String, system: String = ""): String? {
        val k = key(ctx); if (k.isBlank()) return null
        return try {
            val msgs = JSONArray()
            if (system.isNotBlank()) msgs.put(JSONObject().put("role", "system").put("content", system))
            msgs.put(JSONObject().put("role", "user").put("content", prompt))
            val body = JSONObject().put("model", "deepseek-chat").put("messages", msgs).put("stream", false)
                .toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url(URL).addHeader("Authorization", "Bearer $k").post(body).build()
            val r = http.newCall(req).execute()
            val txt = r.body?.string() ?: ""; r.close()
            if (!r.isSuccessful) return null
            JSONObject(txt).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")?.ifBlank { null }
        } catch (_: Exception) { null }
    }
}
