package com.nova.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Clean wallpaper themes without any face/cutout drawing:
 *  - Built-in: 32 curated cyber & gradient Wallpaper Widgets (h1..h4 + w5..w32)
 *  - Custom: User gallery backgrounds saved in internal storage
 */
class Theme(
    val id: String,
    val name: String,
    val accent: Int,
    val bg: Int,
    val fx: Int,
    val custom: Boolean = false,
    val category: String = "Cyber"
)

object Themes {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("nova", Context.MODE_PRIVATE)
    private fun dir(ctx: Context): File = File(ctx.filesDir, "custom").apply { mkdirs() }

    private val builtin: List<Theme> by lazy {
        WallpaperWidgets.ALL.map { w ->
            val fx = when (w.style) { 4, 5 -> 0; 6, 7 -> 2; else -> 2 }
            Theme(w.id, w.name, w.accent, w.bg1, fx, custom = false, category = w.category)
        }
    }

    fun customThemes(ctx: Context): List<Theme> = try {
        val a = JSONArray(prefs(ctx).getString("custom_walls", "[]"))
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Theme(o.getString("id"), o.getString("name"), o.getInt("accent"), o.getInt("bg"), o.optInt("fx", 0), custom = true, category = "Custom")
        }
    } catch (_: Exception) { emptyList() }

    fun all(ctx: Context): List<Theme> = builtin + customThemes(ctx)
    fun byId(ctx: Context, id: String): Theme = all(ctx).firstOrNull { it.id == id } ?: builtin[0]
    fun current(ctx: Context): Theme = byId(ctx, prefs(ctx).getString("theme", "h1") ?: "h1")
    fun set(ctx: Context, id: String) { prefs(ctx).edit().putString("theme", id).apply() }

    private fun res(ctx: Context, name: String): Bitmap? = try {
        val rid = ctx.resources.getIdentifier(name, "drawable", ctx.packageName)
        if (rid == 0) null else BitmapFactory.decodeResource(ctx.resources, rid)
    } catch (_: Throwable) { null }

    fun wallpaper(ctx: Context, t: Theme): Bitmap? {
        if (t.custom) {
            return try { BitmapFactory.decodeFile(File(dir(ctx), t.id + ".jpg").path) } catch (_: Throwable) { null }
        }
        if (t.id in setOf("h1", "h2", "h3", "h4")) {
            res(ctx, "wp_" + t.id)?.let { return it }
        }
        val widget = WallpaperWidgets.byId(t.id) ?: return null
        val dm = ctx.resources.displayMetrics
        val rw = minOf(dm.widthPixels.coerceAtLeast(540), 1080)
        val rh = minOf(dm.heightPixels.coerceAtLeast(960), 1920)
        return WallpaperWidgets.renderBitmap(widget, rw, rh)
    }

    /** Thumbnail for wallpaper widget picker */
    fun thumb(ctx: Context, t: Theme): Bitmap? {
        if (!t.custom) {
            if (t.id in setOf("h1", "h2", "h3", "h4")) {
                res(ctx, "thumb_" + t.id)?.let { return it }
            }
            val widget = WallpaperWidgets.byId(t.id)
            if (widget != null) return WallpaperWidgets.renderThumbnail(widget, 160)
            return null
        }
        return try {
            val o = BitmapFactory.Options().apply { inSampleSize = 4 }
            val b = BitmapFactory.decodeFile(File(dir(ctx), t.id + ".jpg").path, o) ?: return null
            val s = minOf(b.width, b.height); val l = (b.width - s) / 2
            Bitmap.createBitmap(b, l, 0, s, s)
        } catch (_: Throwable) { null }
    }

    /** Add custom background picture from gallery. Call on background thread. */
    fun addCustom(ctx: Context, uri: Uri): Theme? {
        return try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            var sample = 1
            while (maxOf(o.outWidth, o.outHeight) / sample > 2000) sample *= 2
            val bo = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bo) } ?: return null
            val id = "c" + System.currentTimeMillis()
            val f = File(dir(ctx), "$id.jpg")
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            try { Backup.sub("wallpapers")?.let { f.copyTo(File(it, "$id.jpg"), true) } } catch (_: Exception) {}
            val w = bmp.width; val h = bmp.height
            var r = 0L; var g = 0L; var b = 0L; var n = 0
            for (x in 0 until w step maxOf(1, w / 24)) for (y in intArrayOf(0, h - 1)) {
                val p = bmp.getPixel(x, y); r += Color.red(p); g += Color.green(p); b += Color.blue(p); n++
            }
            val bg = Color.rgb((r / maxOf(1, n) * 0.5).toInt(), (g / maxOf(1, n) * 0.5).toInt(), (b / maxOf(1, n) * 0.5).toInt())
            var best = 0f; var acc = Color.parseColor("#38F9D7")
            val hsv = FloatArray(3)
            for (x in 0 until w step maxOf(1, w / 30)) for (y in 0 until h step maxOf(1, h / 40)) {
                Color.colorToHSV(bmp.getPixel(x, y), hsv); val sc = hsv[1] * hsv[2]
                if (sc > best) { best = sc; acc = Color.HSVToColor(floatArrayOf(hsv[0], minOf(1f, hsv[1] + 0.15f), 1f)) }
            }
            val list = JSONArray(prefs(ctx).getString("custom_walls", "[]"))
            val name = "My Background " + (list.length() + 1)
            list.put(JSONObject().put("id", id).put("name", name).put("accent", acc).put("bg", bg).put("fx", 0))
            prefs(ctx).edit().putString("custom_walls", list.toString()).apply()
            Theme(id, name, acc, bg, 0, custom = true, category = "Custom")
        } catch (_: Throwable) { null }
    }

    fun deleteCustom(ctx: Context, id: String) {
        try {
            val a = JSONArray(prefs(ctx).getString("custom_walls", "[]")); val out = JSONArray()
            for (i in 0 until a.length()) if (a.getJSONObject(i).getString("id") != id) out.put(a.getJSONObject(i))
            prefs(ctx).edit().putString("custom_walls", out.toString()).apply()
            File(dir(ctx), "$id.jpg").delete()
            if (prefs(ctx).getString("theme", "") == id) set(ctx, "h1")
        } catch (_: Exception) {}
    }
}
