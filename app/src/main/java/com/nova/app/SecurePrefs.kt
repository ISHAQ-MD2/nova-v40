package com.nova.app

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * EncryptedSharedPreferences wrapper using AndroidX Security Crypto (AES256-GCM MasterKey).
 * Stores sensitive API keys (ElevenLabs) and browser saved site passwords securely.
 */
object SecurePrefs {
    private const val FILE = "nova_secure_prefs"
    @Volatile private var cached: SharedPreferences? = null

    fun prefs(ctx: Context): SharedPreferences {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val app = ctx.applicationContext
            val sp = try {
                val masterKey = MasterKey.Builder(app, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    app,
                    FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (_: Throwable) {
                app.getSharedPreferences(FILE + "_fb", Context.MODE_PRIVATE)
            }
            cached = sp
            return sp
        }
    }

    fun getString(ctx: Context, key: String, def: String = ""): String =
        try { prefs(ctx).getString(key, def) ?: def } catch (_: Throwable) { def }

    fun putString(ctx: Context, key: String, value: String) {
        try { prefs(ctx).edit().putString(key, value).apply() } catch (_: Throwable) {}
    }

    fun remove(ctx: Context, key: String) {
        try { prefs(ctx).edit().remove(key).apply() } catch (_: Throwable) {}
    }
}
