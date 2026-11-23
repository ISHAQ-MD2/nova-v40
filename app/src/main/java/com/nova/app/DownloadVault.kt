package com.nova.app

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.URLUtil
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

enum class DlStatus { QUEUED, RUNNING, PAUSED, DONE, FAILED, UNSUPPORTED }
enum class DlType { DIRECT, HLS }

data class DownloadTask(
    val id: String,
    var url: String,
    var title: String,
    var filePath: String = "",
    var totalBytes: Long = 0L,
    var downloadedBytes: Long = 0L,
    var status: DlStatus = DlStatus.QUEUED,
    var type: DlType = DlType.DIRECT,
    var error: String = "",
    var nextSegment: Int = 0,
    var totalSegments: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Resumable Download Engine for NOVA:
 *  - Direct files (.mp4, .webm, .zip, .apk, etc.) with HTTP Range resume
 *  - HLS (.m3u8) segment fetcher & .ts merger
 *  - Best-effort public og:video extraction for TikTok / Instagram pages
 *  - Explicitly rejects YouTube URLs as unsupported (no DRM circumvention)
 */
object DownloadVault {
    private const val PREF_FILE = "nova_downloads"
    private const val KEY_LIST = "tasks_v1"

    val tasks = CopyOnWriteArrayList<DownloadTask>()
    private val pausedIds = ConcurrentHashMap.newKeySet<String>()
    private val cancelledIds = ConcurrentHashMap.newKeySet<String>()
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var loaded = false
    @Volatile var onUpdate: (() -> Unit)? = null

    fun isYouTubeUrl(url: String): Boolean {
        val h = try { Uri.parse(url).host?.lowercase() ?: "" } catch (_: Exception) { "" }
        return h.contains("youtube.com") || h.contains("youtu.be") ||
            h.contains("youtube-nocookie.com") || h.contains("googlevideo.com")
    }

    fun outputDir(ctx: Context): File {
        val pub = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dir = File(pub, "NOVA-Downloads")
        if (dir.exists() || dir.mkdirs()) {
            if (dir.canWrite()) return dir
        }
        return File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "NOVA-Downloads").apply { mkdirs() }
    }

    fun init(ctx: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            loaded = true
            val raw = ctx.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE).getString(KEY_LIST, "[]") ?: "[]"
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val st = try { DlStatus.valueOf(o.optString("status", "FAILED")) } catch (_: Exception) { DlStatus.FAILED }
                    val normalizedSt = if (st == DlStatus.RUNNING || st == DlStatus.QUEUED) DlStatus.PAUSED else st
                    tasks.add(
                        DownloadTask(
                            id = o.optString("id"),
                            url = o.optString("url"),
                            title = o.optString("title"),
                            filePath = o.optString("filePath"),
                            totalBytes = o.optLong("totalBytes"),
                            downloadedBytes = o.optLong("downloadedBytes"),
                            status = normalizedSt,
                            type = try { DlType.valueOf(o.optString("type", "DIRECT")) } catch (_: Exception) { DlType.DIRECT },
                            error = o.optString("error"),
                            nextSegment = o.optInt("nextSegment"),
                            totalSegments = o.optInt("totalSegments"),
                            createdAt = o.optLong("createdAt", System.currentTimeMillis())
                        )
                    )
                }
            } catch (_: Exception) {}
        }
    }

    @Synchronized
    private fun persist(ctx: Context) {
        val arr = JSONArray()
        for (t in tasks.takeLast(100)) {
            arr.put(
                JSONObject()
                    .put("id", t.id)
                    .put("url", t.url)
                    .put("title", t.title)
                    .put("filePath", t.filePath)
                    .put("totalBytes", t.totalBytes)
                    .put("downloadedBytes", t.downloadedBytes)
                    .put("status", t.status.name)
                    .put("type", t.type.name)
                    .put("error", t.error)
                    .put("nextSegment", t.nextSegment)
                    .put("totalSegments", t.totalSegments)
                    .put("createdAt", t.createdAt)
            )
        }
        ctx.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE).edit().putString(KEY_LIST, arr.toString()).apply()
    }

    private fun notifyUi() {
        main.post { try { onUpdate?.invoke() } catch (_: Throwable) {} }
    }

    private fun sanitize(name: String): String {
        val clean = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trimStart('.')
        return clean.ifEmpty { "nova_media_${System.currentTimeMillis() % 100000}.mp4" }.take(110)
    }

    private fun uniqueFile(dir: File, rawName: String): File {
        var f = File(dir, rawName)
        if (!f.exists()) return f
        val dot = rawName.lastIndexOf('.')
        val base = if (dot > 0) rawName.substring(0, dot) else rawName
        val ext = if (dot > 0) rawName.substring(dot) else ""
        var idx = 1
        while (f.exists() && idx < 500) {
            f = File(dir, "$base-$idx$ext")
            idx++
        }
        return f
    }

    fun enqueue(ctx: Context, rawUrl: String, titleHint: String = ""): DownloadTask {
        init(ctx)
        val app = ctx.applicationContext
        val url = rawUrl.trim()
        if (isYouTubeUrl(url)) {
            val t = DownloadTask(
                id = UUID.randomUUID().toString(),
                url = url,
                title = titleHint.ifBlank { "YouTube Video" },
                status = DlStatus.UNSUPPORTED,
                error = "YouTube downloads are unsupported in this app."
            )
            tasks.add(0, t)
            persist(app)
            notifyUi()
            return t
        }
        val guessed = sanitize(titleHint.ifBlank { URLUtil.guessFileName(url, null, null) })
        val isHls = url.lowercase().contains(".m3u8")
        val task = DownloadTask(
            id = UUID.randomUUID().toString(),
            url = url,
            title = guessed,
            status = DlStatus.QUEUED,
            type = if (isHls) DlType.HLS else DlType.DIRECT
        )
        tasks.add(0, task)
        persist(app)
        notifyUi()
        startWorker(app, task)
        return task
    }

    fun pause(ctx: Context, id: String) {
        init(ctx)
        pausedIds.add(id)
        tasks.firstOrNull { it.id == id }?.let {
            if (it.status == DlStatus.RUNNING || it.status == DlStatus.QUEUED) {
                it.status = DlStatus.PAUSED
                persist(ctx.applicationContext)
                notifyUi()
            }
        }
    }

    fun resume(ctx: Context, id: String) {
        init(ctx)
        val t = tasks.firstOrNull { it.id == id } ?: return
        if (t.status == DlStatus.UNSUPPORTED || t.status == DlStatus.DONE) return
        pausedIds.remove(id)
        cancelledIds.remove(id)
        t.error = ""
        t.status = DlStatus.QUEUED
        persist(ctx.applicationContext)
        notifyUi()
        startWorker(ctx.applicationContext, t)
    }

    fun delete(ctx: Context, id: String, deleteFile: Boolean = true) {
        init(ctx)
        cancelledIds.add(id)
        pausedIds.add(id)
        val t = tasks.firstOrNull { it.id == id } ?: return
        if (deleteFile && t.filePath.isNotBlank()) {
            try { File(t.filePath).delete() } catch (_: Exception) {}
        }
        tasks.remove(t)
        persist(ctx.applicationContext)
        notifyUi()
    }

    private fun startWorker(app: Context, task: DownloadTask) {
        pool.execute {
            runTask(app, task)
        }
    }

    private fun runTask(app: Context, task: DownloadTask) {
        try {
            if (isYouTubeUrl(task.url)) {
                task.status = DlStatus.UNSUPPORTED
                task.error = "YouTube downloads are unsupported in this app."
                persist(app); notifyUi(); return
            }
            pausedIds.remove(task.id)
            task.status = DlStatus.RUNNING
            task.error = ""
            persist(app); notifyUi()

            val resolvedUrl = maybeExtractPublicSocialVideo(task.url)
            if (resolvedUrl == null) {
                task.status = DlStatus.FAILED
                task.error = "Could not extract a public video stream (page may require login or DRM)."
                persist(app); notifyUi(); return
            }
            if (resolvedUrl != task.url) {
                task.url = resolvedUrl
                if (resolvedUrl.lowercase().contains(".m3u8")) task.type = DlType.HLS
            }

            if (task.type == DlType.HLS || task.url.lowercase().contains(".m3u8")) {
                task.type = DlType.HLS
                downloadHls(app, task)
            } else {
                downloadDirect(app, task)
            }
        } catch (e: Exception) {
            if (task.id in cancelledIds) return
            if (task.id in pausedIds) {
                task.status = DlStatus.PAUSED
            } else {
                task.status = DlStatus.FAILED
                task.error = e.message ?: "Download failed"
            }
            persist(app); notifyUi()
        }
    }

    /** Best-effort public og:video meta extraction for TikTok/Instagram links. No DRM bypass. */
    private fun maybeExtractPublicSocialVideo(url: String): String? {
        val h = try { Uri.parse(url).host?.lowercase() ?: "" } catch (_: Exception) { "" }
        val isSocialPage = (h.contains("tiktok.com") || h.contains("instagram.com")) &&
            !url.lowercase().contains(".mp4") && !url.lowercase().contains(".m3u8")
        if (!isSocialPage) return url

        val http = Net.client(readTimeoutSec = 25)
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
            .build()
        http.newCall(req).execute().use { resp ->
            val ct = resp.header("Content-Type")?.lowercase() ?: ""
            if (ct.startsWith("video/")) return url
            val html = resp.body?.string() ?: ""
            val m1 = Regex("""<meta[^>]+property=["']og:video(?::secure_url)?["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)
                ?: Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+property=["']og:video(?::secure_url)?["']""", RegexOption.IGNORE_CASE).find(html)
            val found = m1?.groupValues?.get(1)?.replace("&amp;", "&")
            if (!found.isNullOrBlank() && found.startsWith("http")) return found
        }
        return null
    }

    private fun downloadDirect(app: Context, task: DownloadTask) {
        val dir = outputDir(app)
        val targetFile = if (task.filePath.isNotBlank()) File(task.filePath) else uniqueFile(dir, sanitize(task.title)).also {
            task.filePath = it.absolutePath
        }
        val existing = if (targetFile.exists()) targetFile.length() else 0L
        task.downloadedBytes = existing

        val http = Net.client(readTimeoutSec = 60)
        val rb = Request.Builder().url(task.url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36")
        try {
            val ck = CookieManager.getInstance().getCookie(task.url)
            if (!ck.isNullOrBlank()) rb.header("Cookie", ck)
        } catch (_: Throwable) {}
        if (existing > 0L) rb.header("Range", "bytes=$existing-")

        http.newCall(rb.build()).execute().use { resp ->
            if (resp.code == 416 && existing > 0L) {
                task.status = DlStatus.DONE
                task.totalBytes = existing
                persist(app); notifyUi(); return
            }
            if (!resp.isSuccessful) {
                task.status = DlStatus.FAILED
                task.error = "HTTP ${resp.code}"
                persist(app); notifyUi(); return
            }
            val ct = resp.header("Content-Type")?.lowercase() ?: ""
            if (ct.contains("mpegurl") || ct.contains("m3u8")) {
                task.type = DlType.HLS
                resp.close()
                downloadHls(app, task)
                return
            }

            val append = resp.code == 206 && existing > 0L
            val contentLen = resp.header("Content-Length")?.toLongOrNull() ?: -1L
            task.totalBytes = when {
                append && contentLen > 0 -> existing + contentLen
                contentLen > 0 -> contentLen
                else -> -1L
            }
            if (!append && existing > 0L) {
                task.downloadedBytes = 0L
            }
            if (!task.title.contains('.') && ct.contains("video/mp4")) {
                task.title = task.title + ".mp4"
            }

            val body = resp.body ?: throw IllegalStateException("Empty body")
            val buf = ByteArray(16 * 1024)
            var lastUi = 0L
            FileOutputStream(targetFile, append).use { fos ->
                body.byteStream().use { inp ->
                    while (true) {
                        if (task.id in cancelledIds || task.id in pausedIds) {
                            task.status = DlStatus.PAUSED
                            persist(app); notifyUi(); return
                        }
                        val n = inp.read(buf)
                        if (n <= 0) break
                        fos.write(buf, 0, n)
                        task.downloadedBytes += n
                        val now = System.currentTimeMillis()
                        if (now - lastUi > 350L) {
                            lastUi = now
                            persist(app); notifyUi()
                        }
                    }
                }
            }
            task.totalBytes = targetFile.length()
            task.downloadedBytes = targetFile.length()
            task.status = DlStatus.DONE
            persist(app); notifyUi()
            try { MediaScannerConnection.scanFile(app, arrayOf(targetFile.absolutePath), null, null) } catch (_: Throwable) {}
            WallBus.download("done: ${targetFile.name}")
        }
    }

    private fun downloadHls(app: Context, task: DownloadTask) {
        val dir = outputDir(app)
        val base = sanitize(task.title).substringBeforeLast('.') + ".ts"
        val targetFile = if (task.filePath.isNotBlank()) File(task.filePath) else uniqueFile(dir, base).also {
            task.filePath = it.absolutePath
        }
        val http = Net.client(readTimeoutSec = 30)
        fun fetchText(u: String): String {
            val req = Request.Builder().url(u).header("User-Agent", "Mozilla/5.0").build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw IllegalStateException("HLS HTTP ${r.code}")
                return r.body?.string() ?: ""
            }
        }

        var playlistUrl = task.url
        var playlist = fetchText(playlistUrl)
        // Resolve master playlist to highest bandwidth media playlist
        if (playlist.contains("#EXT-X-STREAM-INF")) {
            val lines = playlist.lines()
            var bestBw = -1L
            var bestUri = ""
            var i = 0
            while (i < lines.size) {
                val ln = lines[i].trim()
                if (ln.startsWith("#EXT-X-STREAM-INF")) {
                    val bw = Regex("BANDWIDTH=(\\d+)").find(ln)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    val next = lines.getOrNull(i + 1)?.trim() ?: ""
                    if (next.isNotEmpty() && !next.startsWith("#") && bw >= bestBw) {
                        bestBw = bw
                        bestUri = URL(URL(playlistUrl), next).toString()
                    }
                }
                i++
            }
            if (bestUri.isNotEmpty()) {
                playlistUrl = bestUri
                playlist = fetchText(playlistUrl)
            }
        }

        val segments = playlist.lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { URL(URL(playlistUrl), it).toString() }
        if (segments.isEmpty()) throw IllegalStateException("No HLS segments found")

        task.totalSegments = segments.size
        val startIdx = task.nextSegment.coerceIn(0, segments.size)
        FileOutputStream(targetFile, startIdx > 0).use { fos ->
            for (idx in startIdx until segments.size) {
                if (task.id in cancelledIds || task.id in pausedIds) {
                    task.status = DlStatus.PAUSED
                    persist(app); notifyUi(); return
                }
                val segReq = Request.Builder().url(segments[idx]).header("User-Agent", "Mozilla/5.0").build()
                http.newCall(segReq).execute().use { sr ->
                    if (!sr.isSuccessful) throw IllegalStateException("Segment ${idx + 1} HTTP ${sr.code}")
                    val bytes = sr.body?.bytes() ?: ByteArray(0)
                    fos.write(bytes)
                    task.downloadedBytes += bytes.size
                    task.nextSegment = idx + 1
                    persist(app); notifyUi()
                }
            }
        }
        task.totalBytes = targetFile.length()
        task.status = DlStatus.DONE
        persist(app); notifyUi()
        try { MediaScannerConnection.scanFile(app, arrayOf(targetFile.absolutePath), null, null) } catch (_: Throwable) {}
        WallBus.download("done: ${targetFile.name}")
    }
}
