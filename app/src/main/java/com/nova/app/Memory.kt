package com.nova.app

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.*

/**
 * NOVA ki yaaddaasht (30 MB tak):
 *  - memory_log.txt   : har baat-cheet + kaam ka log
 *  - memory_notes.txt : "yaad rakhna" wali important baatein (kabhi automatic delete nahi hoti)
 *  - Description      : user ka naam, dost ke numbers
 * Sab kuch /sdcard/NOVA/ mein bhi mirror hota hai, to API key badalne ya reinstall se kuch nahi bhoolta.
 */
object Memory {
    const val CAP = 30L * 1024 * 1024
    private val lock = Any()
    private val fmt get() = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    private fun logFile(ctx: Context) = File(ctx.filesDir, "memory_log.txt")
    private fun noteFile(ctx: Context) = File(ctx.filesDir, "memory_notes.txt")
    private fun clean(s: String) = s.replace('\n', ' ').replace('\r', ' ').trim()

    private var appCtx: Context? = null
    @Volatile private var restored = false

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        ChatLog.init(ctx)
        if (!restored) { restored = true; Backup.restoreIfNeeded(ctx.applicationContext) }
    }

    @Volatile var onLog: ((String, String) -> Unit)? = null

    fun log(kind: String, text: String) {
        val c = appCtx ?: return
        if (text.isBlank()) return
        try { onLog?.invoke(kind, text) } catch (_: Exception) {}
        if (kind == "user" || kind == "nova") ChatLog.add(kind, text)     // poori chat hamesha mehfooz
        synchronized(lock) {
            try {
                val line = "${fmt.format(Date())} | $kind | ${clean(text).take(800)}"
                val f = logFile(c)
                f.appendText(line + "\n")
                Backup.appendLine("memory_log.txt", line)
                if (f.length() > CAP) trim(f)
            } catch (_: Exception) {}
        }
    }

    private fun trim(f: File) {
        val keep = (CAP * 0.8).toLong()
        val rest = RandomAccessFile(f, "r").use { r ->
            r.seek(f.length() - keep); r.readLine()
            ByteArray((f.length() - r.filePointer).toInt()).also { r.readFully(it) }
        }
        f.writeBytes(rest)
        Backup.file("memory_log.txt")?.let { try { it.writeBytes(rest) } catch (_: Exception) {} }
    }

    fun note(ctx: Context, text: String) {
        init(ctx)
        synchronized(lock) {
            try {
                val line = "${fmt.format(Date())} | ${clean(text).take(600)}"
                noteFile(ctx).appendText(line + "\n")
                Backup.appendLine("memory_notes.txt", line)
            } catch (_: Exception) {}
        }
        log("note", text)
    }

    private fun lines(f: File): List<String> = try { if (f.exists()) f.readLines() else emptyList() } catch (_: Exception) { emptyList() }

    /** Naye se purane: query ke lafzon wali lines (notes + log dono mein). Badi file ho to bhi stream karke. */
    fun search(ctx: Context, q: String, limit: Int = 15): List<String> {
        synchronized(lock) {
            val words = q.lowercase().split(Regex("\\s+")).filter { it.length >= 2 }
            val hits = ArrayDeque<String>()
            fun scan(f: File, prefix: String) {
                if (!f.exists()) return
                try {
                    f.bufferedReader().useLines { seq ->
                        seq.forEach { l ->
                            val x = l.lowercase()
                            if (words.isEmpty() || words.any { x.contains(it) }) {
                                hits.addLast(prefix + l); if (hits.size > 400) hits.removeFirst()
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
            scan(logFile(ctx), ""); scan(noteFile(ctx), "[NOTE] ")
            return hits.toList().sortedBy { it.removePrefix("[NOTE] ") }.reversed().take(limit).map { it.take(320) }
        }
    }

    fun notes(ctx: Context): List<String> = synchronized(lock) { lines(noteFile(ctx)) }

    fun recent(ctx: Context, n: Int): List<String> = synchronized(lock) {
        val f = logFile(ctx)
        if (!f.exists()) return emptyList()
        try {
            RandomAccessFile(f, "r").use { r ->
                val start = maxOf(0L, r.length() - 60_000)
                r.seek(start)
                val b = ByteArray((r.length() - start).toInt()); r.readFully(b)
                String(b, Charsets.UTF_8).lines().filter { it.isNotBlank() }.takeLast(n)
            }
        } catch (_: Exception) { emptyList() }
    }

    fun sizeBytes(ctx: Context): Long = logFile(ctx).let { if (it.exists()) it.length() else 0L } + noteFile(ctx).let { if (it.exists()) it.length() else 0L }

    fun clearAll(ctx: Context) = synchronized(lock) {
        logFile(ctx).delete(); noteFile(ctx).delete()
        Backup.file("memory_log.txt")?.delete(); Backup.file("memory_notes.txt")?.delete()
    }

    fun description(ctx: Context): String = ctx.getSharedPreferences("nova", Context.MODE_PRIVATE).getString("desc", "") ?: ""

    /** System prompt mein jaane wala hissa. */
    fun contextBlock(ctx: Context): String {
        val now = SimpleDateFormat("EEEE, yyyy-MM-dd HH:mm", Locale.US).format(Date())
        val sb = StringBuilder("\n\nCURRENT DEVICE TIME: $now (${TimeZone.getDefault().id}).")
        val d = description(ctx).trim()
        sb.append("\n\nUSER PROFILE & CONTACT BOOK (written by the user - names, numbers, notes):\n")
        sb.append(if (d.isEmpty()) "(empty)" else d.take(4000))
        try {
            val bi = ctx.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            val lv = (bi?.getIntExtra("level", -1) ?: -1) * 100 / (bi?.getIntExtra("scale", 100) ?: 100).coerceAtLeast(1)
            val st = bi?.getIntExtra("status", -1) ?: -1
            sb.append("\n\nPHONE BATTERY: $lv% (${if (st == 2 || st == 5) "charging" else "not charging"}).")
        } catch (_: Exception) {}
        val notes = synchronized(lock) { lines(noteFile(ctx)).takeLast(60) }
        if (notes.isNotEmpty()) sb.append("\n\nSAVED NOTES (things the user asked you to remember - these are permanent):\n").append(notes.joinToString("\n") { it.take(300) })
        val tasks = TaskStore.all(ctx)
        if (tasks.isNotEmpty()) sb.append("\n\nPENDING REMINDERS/SCHEDULED TASKS:\n").append(tasks.joinToString("\n") { TaskStore.describe(it) })
        val rec = recent(ctx, 40)
        if (rec.isNotEmpty()) sb.append("\n\nRECENT LOG (older things: use the recall tool):\n").append(rec.joinToString("\n") { it.take(240) })
        return sb.toString().take(12000)
    }

    /** "bhool jao X": jin notes mein X ho wo hata do. Kitne hate wo wapas. */
    fun forgetNote(ctx: Context, query: String): Int {
        val q = query.trim().lowercase(); if (q.length < 2) return 0
        return synchronized(lock) { forgetInner(ctx, q) }
    }

    private fun forgetInner(ctx: Context, q: String): Int {
        val all = lines(noteFile(ctx)); val keep = all.filter { !it.lowercase().contains(q) }
        val removed = all.size - keep.size
        if (removed > 0) try {
            val txt = keep.joinToString("\n") + (if (keep.isEmpty()) "" else "\n")
            noteFile(ctx).writeText(txt); Backup.replace("memory_notes.txt", txt)
        } catch (_: Exception) {}
        return removed
    }

    /** Faltu baat-cheet ka log (user/nova) purana ho to saaf; notes aur kaam ke log kabhi nahi hatate. days=0 = sab chat saaf. */
    fun pruneChat(ctx: Context, days: Int): Int = synchronized(lock) { pruneInner(ctx, days) }

    private fun pruneInner(ctx: Context, days: Int): Int {
        val f = logFile(ctx); if (!f.exists()) return 0
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        val all = lines(f); var removed = 0
        val keep = all.filter { ln ->
            val parts = ln.split(" | ", limit = 3)
            val chat = parts.size >= 3 && (parts[1] == "user" || parts[1] == "nova")
            if (!chat) true else {
                val ts = try { fmt.parse(parts[0])?.time ?: 0L } catch (_: Exception) { 0L }
                val old = days == 0 || ts < cutoff
                if (old) removed++
                !old
            }
        }
        if (removed > 0) try {
            val txt = keep.joinToString("\n") + (if (keep.isEmpty()) "" else "\n")
            f.writeText(txt); Backup.replace("memory_log.txt", txt)
        } catch (_: Exception) {}
        return removed
    }
}
