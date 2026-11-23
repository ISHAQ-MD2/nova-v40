package com.nova.app

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Poori baat-cheet ka permanent record (kabhi auto-delete nahi). /storage/emulated/0/NOVA-FILES/chat_history.txt + app ke andar copy. */
object ChatLog {
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private var appCtx: Context? = null
    private val lock = Any()

    fun init(ctx: Context) { appCtx = ctx.applicationContext }
    private fun internal(ctx: Context) = File(ctx.filesDir, "chat_history.txt")

    fun add(kind: String, text: String) {
        val ctx = appCtx ?: return
        val who = if (kind == "user") "AAP" else "NOVA"
        val line = fmt.format(Date()) + " | " + who + " | " + text.replace("\n", " ").trim()
        synchronized(lock) {
            try { internal(ctx).appendText(line + "\n") } catch (_: Exception) {}
            Backup.appendLine("chat_history.txt", line)
        }
    }

    /** Aakhri n lines (naye pehle). Offline dekhne ke liye. */
    fun last(ctx: Context, n: Int = 300): List<String> = synchronized(lock) {
        try { val f = internal(ctx); if (!f.exists()) emptyList() else f.readLines().takeLast(n).reversed() } catch (_: Exception) { emptyList() }
    }

    fun count(ctx: Context): Int = try { val f = internal(ctx); if (f.exists()) f.readLines().size else 0 } catch (_: Exception) { 0 }
}
