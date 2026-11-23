package com.nova.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * NOVA ka apna clipboard. Jo bhi copy hota hai (link/text) yahan save hota hai, saath mein "kis cheez ka tha".
 * Android 10+ mein background app clipboard nahi padh sakti, isliye ye teen jagah se save hota hai:
 *  1) NOVA app/bubble foreground mein ho (listener), 2) NOVA khulte hi, 3) bubble menu "Clip save" ya "copy_to_clipboard" tool.
 * Backup mein bhi jata hai: /sdcard/NOVA/clips.json
 */
object ClipStore {
    @Volatile var pendingAbout = ""
    @Volatile var grabResult = ""
    @Volatile var grabLatch: java.util.concurrent.CountDownLatch? = null
    private var lastSaved = ""
    private var started = false
    private fun file(ctx: Context) = File(ctx.filesDir, "clips.json")

    @Synchronized fun all(ctx: Context): List<JSONObject> {
        val a = try { JSONArray(if (file(ctx).exists()) file(ctx).readText() else "[]") } catch (_: Exception) { JSONArray() }
        return (0 until a.length()).map { a.getJSONObject(it) }
    }

    @Synchronized private fun write(ctx: Context, l: List<JSONObject>) {
        val a = JSONArray(); l.takeLast(300).forEach { a.put(it) }
        try { file(ctx).writeText(a.toString()) } catch (_: Exception) {}
        Backup.replace("clips.json", a.toString())
    }

    fun add(ctx: Context, text: String, about: String = ""): Boolean {
        val t = text.trim(); if (t.isEmpty() || t == lastSaved) return false
        lastSaved = t
        val l = all(ctx)
        if (l.lastOrNull()?.optString("text") == t) return false
        val kind = if (t.startsWith("http://") || t.startsWith("https://")) "link" else "text"
        val ab = about.ifBlank { guessAbout(ctx, t, kind) }
        write(ctx, l + JSONObject().put("t", System.currentTimeMillis()).put("text", t.take(4000)).put("kind", kind).put("about", ab))
        return true
    }

    /** "Kis cheez ka tha" - link ho to site ka naam, warna shuru ke lafz. */
    private fun guessAbout(ctx: Context, t: String, kind: String): String {
        if (kind == "link") {
            val host = Regex("https?://([^/]+)").find(t)?.groupValues?.get(1)?.removePrefix("www.") ?: "link"
            val what = when {
                host.contains("youtu") -> "YouTube video"; host.contains("instagram") -> "Instagram post"
                host.contains("facebook") || host.contains("fb.") -> "Facebook post"; host.contains("tiktok") -> "TikTok video"
                host.contains("maps") -> "Maps location"; else -> host
            }
            return what
        }
        return t.take(40)
    }

    fun readNow(ctx: Context): String? = try {
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
    } catch (_: Exception) { null }

    /** Foreground mein jo abhi copy hua hai use save karo (bubble menu / app open). */
    fun saveCurrent(ctx: Context, about: String = ""): String {
        val t = readNow(ctx)
        if (t.isNullOrBlank()) return "clipboard khali hai ya Android ne padhne nahi diya (NOVA app kholkar dobara koshish karo)"
        return if (add(ctx, t, about)) "clip save ho gaya: ${t.take(60)}" else "ye pehle se save hai"
    }

    fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        Handler(Looper.getMainLooper()).post {
            try {
                val cm = app.getSystemService(ClipboardManager::class.java)
                cm.addPrimaryClipChangedListener { try { readNow(app)?.let { add(app, it) } } catch (_: Exception) {} }
            } catch (_: Exception) {}
        }
    }

    fun find(ctx: Context, query: String): JSONObject? {
        val l = all(ctx)
        if (query.isBlank()) return l.lastOrNull()
        return l.lastOrNull { it.optString("text").contains(query, true) || it.optString("about").contains(query, true) }
    }

    /** Phone ke clipboard mein wapas rakho taake paste ho sake. */
    fun putOnClipboard(ctx: Context, text: String): Boolean {
        val latch = java.util.concurrent.CountDownLatch(1); var ok = false
        Handler(Looper.getMainLooper()).post {
            try { ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("nova", text)); lastSaved = text.trim(); ok = true } catch (_: Exception) {}
            latch.countDown()
        }
        try { latch.await(2, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
        return ok
    }

    fun describe(j: JSONObject): String =
        SimpleDateFormat("dd MMM HH:mm", Locale.US).format(Date(j.optLong("t"))) + " [" + j.optString("about") + "] " + j.optString("text").take(120)

    fun list(ctx: Context, n: Int = 10): String {
        val l = all(ctx).takeLast(n).reversed()
        return if (l.isEmpty()) "NOVA clipboard khali hai" else l.joinToString("\n") { describe(it) }
    }

    /** Background se bhi clipboard pakadna: transparent activity ek pal ke liye aati hai. Background thread se bulao. */
    fun grabBlocking(ctx: Context, about: String = ""): String {
        pendingAbout = about; grabResult = ""
        val latch = java.util.concurrent.CountDownLatch(1); grabLatch = latch
        try {
            ctx.startActivity(android.content.Intent(ctx, ClipGrabActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION))
        } catch (e: Exception) { return saveCurrent(ctx, about) }
        try { latch.await(3, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
        return grabResult.ifEmpty { "clipboard nahi pakda ja saka (overlay permission do)" }
    }
}
