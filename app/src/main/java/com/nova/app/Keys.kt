package com.nova.app

import android.content.Context

/**
 * v30: Do (ya zyada) Gemini API keys. Ek ki limit (429/quota) khatam ho to NOVA khud doosri key par chali jati hai.
 * Key 1 = pref "k" (Setup mein), Key 2 = pref "k2" (Settings > V30 FEATURES > 2nd API key).
 */
object Keys {
    @Volatile private var idx = 0
    private fun p(ctx: Context) = ctx.getSharedPreferences("nova", Context.MODE_PRIVATE)

    fun all(ctx: Context): List<String> {
        val a = Crypto.dec(p(ctx).getString("k", "") ?: "")
        val b = Crypto.dec(p(ctx).getString("k2", "") ?: "")
        return listOf(a, b).filter { it.isNotBlank() }.distinct()
    }

    fun hasSecond(ctx: Context) = Crypto.dec(p(ctx).getString("k2", "") ?: "").isNotBlank()
    fun saveSecond(ctx: Context, key: String) { p(ctx).edit().putString("k2", if (key.isBlank()) "" else Crypto.enc(key.trim())).apply() }

    /** Abhi ki key (pehli). */
    fun active(ctx: Context): String { val l = all(ctx); return if (l.isEmpty()) "" else l[idx.coerceIn(0, l.size - 1)] }

    /** Limit lagne par agli key. Doosri key na ho to null. */
    @Synchronized fun rotate(ctx: Context): String? {
        val l = all(ctx)
        if (l.size < 2) return null
        idx = (idx + 1) % l.size
        WallBus.pushFeed("KEY", "API key badli (limit/quota)")
        return l[idx]
    }
}
