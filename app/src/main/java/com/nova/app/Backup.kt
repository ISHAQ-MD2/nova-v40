package com.nova.app

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Yaaddaasht ka doosra ghar: /sdcard/NOVA/
 * App uninstall / reinstall / API key change par bhi memory, description aur reminders wapas aa jaate hain.
 * (API key is folder mein save NAHI hoti - security.)
 */
object Backup {
    /** Sab kuch yahan: /storage/emulated/0/NOVA-FILES (backup, chat history, exports, model). */
    fun dir(): File? = try {
        val root = Environment.getExternalStorageDirectory()
        val d = File(root, "NOVA-FILES")
        if (!d.exists()) { val old = File(root, "NOVA"); if (old.exists()) old.renameTo(d) }    // purana /NOVA folder yahan le aao
        if (d.exists() || d.mkdirs()) d else null
    } catch (_: Exception) { null }

    fun sub(name: String): File? = dir()?.let { val f = File(it, name); if (f.exists() || f.mkdirs()) f else null }

    fun usable(): Boolean = try { dir()?.canWrite() == true } catch (_: Exception) { false }

    fun file(name: String): File? = dir()?.let { File(it, name) }

    fun appendLine(name: String, line: String) {
        try { file(name)?.appendText(line + "\n") } catch (_: Exception) {}
    }

    fun replace(name: String, content: String) {
        try { file(name)?.writeText(content) } catch (_: Exception) {}
    }

    /** Profile (description, naam, country code, persona) + tasks mirror. */
    fun saveProfile(ctx: Context) {
        try {
            val p = ctx.getSharedPreferences("nova", Context.MODE_PRIVATE)
            val o = JSONObject()
                .put("desc", p.getString("desc", "") ?: "")
                .put("cc", p.getString("cc", "92") ?: "92")
                .put("u", p.getString("u", "") ?: "")
                .put("p", p.getString("p", "girl") ?: "girl")
            replace("profile.json", o.toString())
        } catch (_: Exception) {}
    }

    fun saveTasks(ctx: Context) {
        try {
            val a = JSONArray(); TaskStore.all(ctx).forEach { a.put(it) }
            replace("tasks.json", a.toString())
        } catch (_: Exception) {}
    }

    private fun readLines(f: File): List<String> = try { if (f.exists()) f.readLines() else emptyList() } catch (_: Exception) { emptyList() }

    /** Poori yaaddaasht ek JSON file mein (API key NAHI). */
    fun export(ctx: Context): String {
        val p = ctx.getSharedPreferences("nova", Context.MODE_PRIVATE)
        val o = JSONObject().put("app", "NOVA").put("format", 1).put("time", System.currentTimeMillis())
        o.put("profile", JSONObject().put("desc", p.getString("desc", "") ?: "").put("cc", p.getString("cc", "92") ?: "92")
            .put("u", p.getString("u", "") ?: "").put("p", p.getString("p", "girl") ?: "girl"))
        o.put("settings", JSONObject().put("wake", p.getBoolean("wake", true)).put("bubble", p.getBoolean("bubble", true))
            .put("auto", p.getBoolean("auto", false)).put("vol", p.getInt("vol", 100)).put("college", p.getBoolean("college", false)))
        o.put("notes", JSONArray(readLines(File(ctx.filesDir, "memory_notes.txt"))))
        o.put("log", JSONArray(readLines(File(ctx.filesDir, "memory_log.txt")).takeLast(400)))
        val ta = JSONArray(); try { TaskStore.all(ctx).forEach { ta.put(it) } } catch (_: Exception) {}
        o.put("tasks", ta)
        val ca = JSONArray(); try { ClipStore.all(ctx).forEach { ca.put(it) } } catch (_: Exception) {}
        o.put("clips", ca)
        try { val pf = File(ctx.filesDir, "playlists.json"); if (pf.exists()) o.put("playlists", JSONObject(pf.readText())) } catch (_: Exception) {}
        return o.toString(2)
    }

    /** Backup file se wapas lao (jo pehle se hai usse jodta hai, mitata nahi). */
    fun importFrom(ctx: Context, text: String): String {
        val o = JSONObject(text)
        require(o.optString("app") == "NOVA") { "Ye NOVA ki backup file nahi hai" }
        val p = ctx.getSharedPreferences("nova", Context.MODE_PRIVATE)
        val ed = p.edit()
        o.optJSONObject("profile")?.let { pr ->
            if ((p.getString("desc", "") ?: "").isEmpty() && (p.getString("u", "") ?: "").isEmpty()) {
                ed.putString("desc", pr.optString("desc")).putString("cc", pr.optString("cc", "92"))
                    .putString("u", pr.optString("u")).putString("p", pr.optString("p", "girl"))
            }
        }
        o.optJSONObject("settings")?.let { s ->
            ed.putBoolean("wake", s.optBoolean("wake", true)).putBoolean("bubble", s.optBoolean("bubble", true))
                .putBoolean("auto", s.optBoolean("auto", false)).putInt("vol", s.optInt("vol", 100)).putBoolean("college", s.optBoolean("college", false))
        }
        ed.apply()
        fun merge(key: String, name: String): Int {
            val a = o.optJSONArray(key) ?: return 0
            val f = File(ctx.filesDir, name)
            val have = readLines(f).toHashSet(); var n = 0
            for (i in 0 until a.length()) {
                val l = a.optString(i); if (l.isBlank() || l in have) continue
                f.appendText(l + "\n"); appendLine(name, l); have.add(l); n++
            }
            return n
        }
        val nn = merge("notes", "memory_notes.txt"); val nl = merge("log", "memory_log.txt")
        var nt = 0
        o.optJSONArray("tasks")?.let { a ->
            val now = System.currentTimeMillis()
            for (i in 0 until a.length()) {
                val t = a.getJSONObject(i); var at = t.optLong("at")
                val ev = t.optInt("every", 0); val until = t.optLong("until", 0L)
                if (ev > 0 && at <= now) at = now + 60_000L
                if (at <= now && t.optBoolean("rep")) while (at <= now) at += 24L * 3600 * 1000
                if (at > now && (until == 0L || at <= until)) { TaskStore.addRestored(ctx, at, t.optString("text"), t.optBoolean("rep"), ev, until); nt++ }
            }
        }
        o.optJSONArray("clips")?.let { a ->
            val have = ClipStore.all(ctx).map { it.optString("text") }.toHashSet()
            for (i in 0 until a.length()) { val j = a.optJSONObject(i) ?: continue; if (j.optString("text") !in have) ClipStore.add(ctx, j.optString("text"), j.optString("about")) }
        }
        o.optJSONObject("playlists")?.let { try { File(ctx.filesDir, "playlists.json").writeText(it.toString()) } catch (_: Exception) {} }
        saveProfile(ctx); saveTasks(ctx)
        return "Wapas aaya: $nn notes, $nl log, $nt reminders, clipboard + playlists (profile + settings bhi)"
    }

    /** App ki apni files khali hon (naya install) to /sdcard/NOVA se wapas le aao. */
    fun restoreIfNeeded(ctx: Context) {
        try {
            val d = dir() ?: return
            val internalLog = File(ctx.filesDir, "memory_log.txt")
            val internalNotes = File(ctx.filesDir, "memory_notes.txt")
            val bl = File(d, "memory_log.txt"); val bn = File(d, "memory_notes.txt")
            if (!internalLog.exists() && bl.exists()) bl.copyTo(internalLog, overwrite = true)
            if (!internalNotes.exists() && bn.exists()) bn.copyTo(internalNotes, overwrite = true)

            val p = ctx.getSharedPreferences("nova", Context.MODE_PRIVATE)
            val pf = File(d, "profile.json")
            if (pf.exists() && (p.getString("desc", "") ?: "").isEmpty() && (p.getString("u", "") ?: "").isEmpty()) {
                val o = JSONObject(pf.readText())
                p.edit().putString("desc", o.optString("desc")).putString("cc", o.optString("cc", "92"))
                    .putString("u", o.optString("u")).putString("p", o.optString("p", "girl")).apply()
            }
            val tf = File(d, "tasks.json")
            if (tf.exists() && TaskStore.all(ctx).isEmpty()) {
                val a = JSONArray(tf.readText()); val now = System.currentTimeMillis()
                for (i in 0 until a.length()) {
                    val t = a.getJSONObject(i)
                    var at = t.optLong("at")
                    val ev = t.optInt("every", 0); val until = t.optLong("until", 0L)
                    if (ev > 0 && at <= now) at = now + 60_000L
                    if (at <= now && t.optBoolean("rep")) while (at <= now) at += 24L * 3600 * 1000
                    if (at > now && (until == 0L || at <= until)) TaskStore.addRestored(ctx, at, t.optString("text"), t.optBoolean("rep"), ev, until)
                }
            }
        } catch (_: Exception) {}
    }

    /** Padhne layak poora export: yaad ki baatein, reminders, clipboard, playlists, poori chat. */
    fun exportText(ctx: Context): String {
        val sb = StringBuilder("NOVA EXPORT - " + java.util.Date() + "\n\n=== YAAD KI BAATEIN ===\n")
        try { Memory.notes(ctx).forEach { sb.append(it).append('\n') } } catch (_: Exception) {}
        sb.append("\n=== REMINDERS ===\n")
        try { TaskStore.all(ctx).forEach { sb.append(TaskStore.describe(it)).append('\n') } } catch (_: Exception) {}
        sb.append("\n=== NOVA CLIPBOARD ===\n")
        try { ClipStore.all(ctx).forEach { sb.append(ClipStore.describe(it)).append('\n') } } catch (_: Exception) {}
        sb.append("\n=== PLAYLISTS ===\n")
        try { val pf = File(ctx.filesDir, "playlists.json"); if (pf.exists()) sb.append(pf.readText()).append('\n') } catch (_: Exception) {}
        sb.append("\n=== POORI CHAT ===\n")
        try { ChatLog.last(ctx, 100000).reversed().forEach { sb.append(it).append('\n') } } catch (_: Exception) {}
        return sb.toString()
    }

    /** "nova backup le lo": NOVA-FILES/backups mein backup, NOVA-FILES/exports mein padhne layak text. */
    fun backupToDownloads(ctx: Context): String {
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm", java.util.Locale.US).format(java.util.Date())
        val name = "NOVA-backup-$stamp.json"
        val data = try { export(ctx) } catch (e: Exception) { return "backup bana nahi saki: ${e.message}" }
        try {
            val b = sub("backups"); val x = sub("exports")
            if (b != null) {
                File(b, name).writeText(data)
                try { x?.let { File(it, "NOVA-export-$stamp.txt").writeText(exportText(ctx)) } } catch (_: Exception) {}
                return "Backup ho gaya: NOVA-FILES/backups/$name (aur NOVA-FILES/exports mein text export)"
            }
        } catch (_: Exception) {}
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            try {
                val v = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/NOVA-FILES")
                }
                val uri = ctx.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
                if (uri != null) { ctx.contentResolver.openOutputStream(uri)?.use { it.write(data.toByteArray()) }; return "Backup ho gaya: Download/NOVA-FILES/$name (All files access do to NOVA-FILES mein jayega)" }
            } catch (_: Exception) {}
        }
        return "Backup folder nahi ban saka - NOVA ko 'All files access' permission do (Settings > Storage permission)"
    }
}
