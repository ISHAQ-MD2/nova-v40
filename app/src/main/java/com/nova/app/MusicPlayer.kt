package com.nova.app

import android.content.ContentUris
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * NOVA ka apna music/video-audio player (YouTube ya MX Player kholne ki zaroorat nahi).
 * Phone ki memory ke gaane + video (video ki sirf awaaz) chalata hai. Sirf wohi jo phone mein maujood hai - internet se stream nahi karta.
 */
object MusicPlayer {
    class Track(val title: String, val uri: Uri, val size: Long, val durMs: Long, val isVideo: Boolean)

    private var mp: MediaPlayer? = null
    private var queue: List<Track> = emptyList()
    private var idx = -1
    private var paused = false
    private var hooked = false
    private lateinit var app: Context

    val current: Track? get() = queue.getOrNull(idx)
    fun isPlaying() = try { mp?.isPlaying == true } catch (_: Exception) { false }

    private fun hook(ctx: Context) {
        app = ctx.applicationContext
        if (hooked) return
        hooked = true
        // NOVA bolti hai to music dheema, khatam hone par wapas
        Vol.addSpeakListener { sp -> try { val v = if (sp) 0.2f else 1f; mp?.setVolume(v, v) } catch (_: Exception) {} }
    }

    fun library(ctx: Context, includeVideo: Boolean = true): List<Track> {
        val out = ArrayList<Track>()
        fun scan(base: Uri, video: Boolean) {
            val proj = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DURATION)
            try {
                ctx.contentResolver.query(base, proj, null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
                    while (c.moveToNext()) {
                        val name = (c.getString(1) ?: continue).substringBeforeLast('.')
                        val dur = c.getLong(3)
                        if (!video && dur in 1..30_000) continue      // chhoti ringtones/notification chhodo
                        out.add(Track(name, ContentUris.withAppendedId(base, c.getLong(0)), c.getLong(2), dur, video))
                    }
                }
            } catch (_: Exception) {}
        }
        scan(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, false)
        if (includeVideo) scan(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
        return out
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9\u0600-\u06FF ]"), " ").replace(Regex("\\s+"), " ").trim()

    fun search(ctx: Context, q: String): List<Track> {
        val lib = library(ctx)
        val words = norm(q).split(" ").filter { it.length >= 2 }
        if (words.isNotEmpty() && !Script.hasNonLatin(q)) {
            val full = lib.filter { t -> val n = norm(t.title); words.all { n.contains(it) } }
            if (full.isNotEmpty()) return full
            val any = lib.filter { t -> val n = norm(t.title); words.any { n.contains(it) } }
            if (any.isNotEmpty()) return any
        }
        // Urdu / Hindi / ghalat spelling: consonants ka dhancha milao ("پل پل" ~ "palpal")
        val qs = Script.skeleton(q)
        if (qs.length < 2) return emptyList()
        val scored = lib.mapNotNull { t ->
            val sk = Script.skeleton(t.title)
            val score = when {
                sk == qs -> 0
                sk.contains(qs) -> 1
                qs.length >= 4 && (0..maxOf(0, sk.length - qs.length)).any { i -> Script.editDistance(qs, sk.substring(i, minOf(sk.length, i + qs.length))) <= 1 } -> 2
                else -> -1
            }
            if (score >= 0) t to score else null
        }
        return scored.sortedBy { it.second }.map { it.first }
    }

    fun playList(ctx: Context, tracks: List<Track>, start: Int = 0): String {
        hook(ctx)
        if (tracks.isEmpty()) return "koi gaana nahi mila"
        queue = tracks; idx = start.coerceIn(0, tracks.size - 1)
        return startCurrent()
    }

    private fun startCurrent(): String {
        val t = current ?: return "queue khali"
        try { mp?.release() } catch (_: Exception) {}
        return try {
            val p = MediaPlayer()
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            p.setDataSource(app, t.uri)
            p.setOnCompletionListener { next() }
            p.setOnErrorListener { _, _, _ -> next(); true }
            p.prepare(); p.start()
            if (Vol.speaking) p.setVolume(0.2f, 0.2f)
            mp = p; paused = false
            Memory.log("music", "play: ${t.title}")
            Vol.emote("happy")
            "chal raha hai: ${t.title}" + if (t.isVideo) " (video ki awaaz)" else ""
        } catch (e: Exception) { "play nahi ho saka: ${t.title} (${e.message})" }
    }

    fun next(): String { if (queue.isEmpty()) return "queue khali"; idx = (idx + 1) % queue.size; return startCurrent() }
    fun prev(): String { if (queue.isEmpty()) return "queue khali"; idx = (idx - 1 + queue.size) % queue.size; return startCurrent() }
    fun pause(): String { try { mp?.pause(); paused = true } catch (_: Exception) {}; return "music pause" }
    fun resume(): String { try { if (paused) { mp?.start(); paused = false } } catch (_: Exception) {}; return "music dobara chalu" }
    fun stop(): String { try { mp?.stop(); mp?.release() } catch (_: Exception) {}; mp = null; queue = emptyList(); idx = -1; return "music band" }

    // ---- tool-level helpers ----
    fun playQuery(ctx: Context, query: String, mode: String): String {
        hook(ctx)
        val lib = library(ctx)
        if (lib.isEmpty()) return "phone mein koi gaana nahi mila - storage/audio permission check karo"
        return when (mode.lowercase()) {
            "biggest", "size", "mb" -> playList(ctx, lib.sortedByDescending { it.size })
            "longest", "time", "duration" -> playList(ctx, lib.sortedByDescending { it.durMs })
            "random", "shuffle" -> playList(ctx, lib.shuffled())
            "all" -> playList(ctx, lib)
            else -> {
                val r = search(ctx, query)
                if (r.isEmpty()) "'$query' naam ka gaana phone mein nahi mila (NOVA sirf phone ke gaane chalati hai)"
                else playList(ctx, r)
            }
        }
    }

    fun status(): String = current?.let { (if (isPlaying()) "chal raha: " else "ruka hua: ") + it.title + " (${idx + 1}/${queue.size})" } ?: "kuch nahi chal raha"

    // ---- playlists ----
    private fun pf(ctx: Context) = File(ctx.filesDir, "playlists.json")
    private fun loadPl(ctx: Context): JSONObject = try { JSONObject(if (pf(ctx).exists()) pf(ctx).readText() else "{}") } catch (_: Exception) { JSONObject() }
    private fun savePl(ctx: Context, o: JSONObject) { try { pf(ctx).writeText(o.toString()) } catch (_: Exception) {}; Backup.replace("playlists.json", o.toString()) }

    fun playlistAdd(ctx: Context, name: String, query: String): String {
        val cur = if (query.isBlank()) current?.title else search(ctx, query).firstOrNull()?.title
        cur ?: return "gaana nahi mila jo playlist mein dalun"
        val o = loadPl(ctx); val a = o.optJSONArray(name) ?: JSONArray()
        for (i in 0 until a.length()) if (a.optString(i) == cur) return "'$cur' pehle se '$name' playlist mein hai"
        a.put(cur); o.put(name, a); savePl(ctx, o)
        return "'$cur' ko '$name' playlist mein daal diya"
    }

    fun playlistShow(ctx: Context, name: String): String {
        val o = loadPl(ctx)
        if (name.isBlank()) return if (o.length() == 0) "koi playlist nahi" else o.keys().asSequence().joinToString("\n") { "$it (${o.getJSONArray(it).length()} gaane)" }
        val a = o.optJSONArray(name) ?: return "'$name' naam ki playlist nahi"
        return (0 until a.length()).joinToString("\n") { "${it + 1}. ${a.getString(it)}" }
    }

    fun playlistPlay(ctx: Context, name: String): String {
        val a = loadPl(ctx).optJSONArray(name) ?: return "'$name' playlist nahi mili"
        val lib = library(ctx)
        val tracks = (0 until a.length()).mapNotNull { i -> lib.firstOrNull { it.title == a.getString(i) } }
        return if (tracks.isEmpty()) "playlist ke gaane phone mein nahi mile" else playList(ctx, tracks)
    }
}
