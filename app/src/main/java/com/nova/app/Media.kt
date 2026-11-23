package com.nova.app

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File

/** Gallery (photo/video/audio) aur Download folder ki files dhoondna. */
object Media {
    class Item(val name: String, val uri: Uri, val mime: String, val modified: Long)

    fun find(ctx: Context, kind: String, query: String, limit: Int): List<Item> {
        val k = kind.lowercase()
        return when {
            k.startsWith("photo") || k.startsWith("image") || k.startsWith("pic") ->
                store(ctx, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, query, limit, "image/jpeg")
            k.startsWith("video") -> store(ctx, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, query, limit, "video/mp4")
            k.startsWith("audio") || k == "music" || k == "song" || k.startsWith("voice") ->
                store(ctx, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, query, limit, "audio/mpeg")
            else -> files(ctx, query, limit)
        }
    }

    private fun store(ctx: Context, base: Uri, q: String, limit: Int, defMime: String): List<Item> {
        val out = ArrayList<Item>()
        val proj = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATE_ADDED)
        val sel = if (q.isBlank()) null else "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
        val args = if (q.isBlank()) null else arrayOf("%${q.trim()}%")
        try {
            ctx.contentResolver.query(base, proj, sel, args, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    out.add(Item(c.getString(1) ?: "item", ContentUris.withAppendedId(base, c.getLong(0)),
                        c.getString(2) ?: defMime, c.getLong(3) * 1000))
                }
            }
        } catch (_: Exception) {}
        return out
    }

    private fun mimeOf(f: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "application/octet-stream"

    private fun files(ctx: Context, q: String, limit: Int): List<Item> {
        val direct = File(q.trim())
        val found = ArrayList<File>()
        if (q.startsWith("/") && direct.isFile) found.add(direct)
        else {
            val roots = listOf(Environment.DIRECTORY_DOWNLOADS, Environment.DIRECTORY_DOCUMENTS)
                .map { Environment.getExternalStoragePublicDirectory(it) }
            fun walk(f: File, depth: Int) {
                val l = f.listFiles() ?: return
                for (x in l) {
                    if (x.isDirectory) { if (depth < 2) walk(x, depth + 1) }
                    else if (!x.name.startsWith(".") && (q.isBlank() || x.name.contains(q.trim(), true))) found.add(x)
                }
            }
            roots.forEach { walk(it, 0) }
        }
        return found.sortedByDescending { it.lastModified() }.take(limit).mapNotNull {
            try { Item(it.name, FileProvider.getUriForFile(ctx, ctx.packageName + ".fp", it), mimeOf(it), it.lastModified()) }
            catch (_: Exception) { null }
        }
    }

    fun jpeg(b: Bitmap, maxSide: Int): ByteArray {
        val s = maxSide.toFloat() / maxOf(b.width, b.height)
        val bm = if (s < 1f) Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true) else b
        val o = ByteArrayOutputStream(); bm.compress(Bitmap.CompressFormat.JPEG, 72, o); return o.toByteArray()
    }

    fun jpegFromUri(ctx: Context, uri: Uri, maxSide: Int = 1024): ByteArray? = try {
        val bo = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bo) }
        var ss = 1; while (maxOf(bo.outWidth, bo.outHeight) / ss > maxSide * 2) ss *= 2
        val o = BitmapFactory.Options().apply { inSampleSize = ss }
        val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        bmp?.let { jpeg(it, maxSide) }
    } catch (_: Exception) { null }
}
