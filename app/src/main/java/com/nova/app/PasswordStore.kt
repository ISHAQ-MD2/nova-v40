package com.nova.app

import android.content.Context
import android.net.Uri
import org.json.JSONObject

/**
 * Encrypted website credential manager backed by SecurePrefs (AES256-GCM EncryptedSharedPreferences).
 * Never stores secrets in plaintext preferences.
 */
object PasswordStore {
    private const val KEY_MAP = "browser_saved_passwords"

    data class Cred(val host: String, val user: String, val pass: String, val updatedAt: Long)

    private fun readMap(ctx: Context): JSONObject {
        val raw = SecurePrefs.getString(ctx, KEY_MAP, "{}")
        return try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
    }

    private fun writeMap(ctx: Context, obj: JSONObject) {
        SecurePrefs.putString(ctx, KEY_MAP, obj.toString())
    }

    fun hostOf(url: String?): String {
        if (url.isNullOrBlank()) return ""
        return try { (Uri.parse(url).host ?: "").lowercase().removePrefix("www.") } catch (_: Exception) { "" }
    }

    fun save(ctx: Context, urlOrHost: String, user: String, pass: String) {
        val h = if (urlOrHost.contains("://")) hostOf(urlOrHost) else urlOrHost.lowercase().removePrefix("www.")
        if (h.isBlank() || pass.isBlank()) return
        val m = readMap(ctx)
        m.put(h, JSONObject().put("u", user).put("p", pass).put("at", System.currentTimeMillis()))
        writeMap(ctx, m)
    }

    fun get(ctx: Context, urlOrHost: String): Cred? {
        val h = if (urlOrHost.contains("://")) hostOf(urlOrHost) else urlOrHost.lowercase().removePrefix("www.")
        if (h.isBlank()) return null
        val o = readMap(ctx).optJSONObject(h) ?: return null
        val p = o.optString("p")
        if (p.isBlank()) return null
        return Cred(h, o.optString("u"), p, o.optLong("at"))
    }

    fun list(ctx: Context): List<Cred> {
        val m = readMap(ctx)
        val out = ArrayList<Cred>()
        val it = m.keys()
        while (it.hasNext()) {
            val h = it.next()
            val o = m.optJSONObject(h) ?: continue
            out.add(Cred(h, o.optString("u"), o.optString("p"), o.optLong("at")))
        }
        return out.sortedByDescending { it.updatedAt }
    }

    fun remove(ctx: Context, host: String) {
        val m = readMap(ctx)
        m.remove(host.lowercase().removePrefix("www."))
        writeMap(ctx, m)
    }

    fun clearAll(ctx: Context) {
        SecurePrefs.remove(ctx, KEY_MAP)
    }
}
