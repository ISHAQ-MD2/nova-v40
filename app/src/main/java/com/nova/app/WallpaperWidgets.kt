package com.nova.app

import android.app.WallpaperManager
import android.content.Context
import android.graphics.*
import kotlin.math.cos
import kotlin.math.sin

data class WallWidget(
    val id: String,
    val name: String,
    val category: String,
    val accent: Int,
    val bg1: Int,
    val bg2: Int,
    val style: Int
)

/**
 * 32 clean cyber / gradient / abstract wallpaper widgets (no faces).
 * Generates both square thumbnails for the picker and full-screen bitmaps for Home + Lock screens.
 */
object WallpaperWidgets {
    private fun c(hex: String) = Color.parseColor(hex)

    val ALL: List<WallWidget> = listOf(
        WallWidget("h1", "Matrix Terminal", "Cyber", c("#00FF9C"), c("#021008"), c("#042615"), 0),
        WallWidget("h2", "Cyber Circuit", "Cyber", c("#28BEFF"), c("#020814"), c("#061E3A"), 1),
        WallWidget("h3", "Red Alert", "Cyber", c("#FF3B56"), c("#120206"), c("#2A050E"), 2),
        WallWidget("h4", "Hex Grid Purple", "Cyber", c("#BE5AFF"), c("#080214"), c("#1D0736"), 3),
        WallWidget("w5", "Aurora Horizon", "Gradient", c("#38F9D7"), c("#061326"), c("#114B5F"), 4),
        WallWidget("w6", "Midnight Blossom", "Gradient", c("#FF7EB6"), c("#0F0618"), c("#2A0F35"), 5),
        WallWidget("w7", "Solar Flare", "Abstract", c("#FF9E40"), c("#180802"), c("#3A1504"), 6),
        WallWidget("w8", "Deep Ocean", "Gradient", c("#00D4FF"), c("#020C1B"), c("#0A2540"), 4),
        WallWidget("w9", "Emerald Pulse", "Cyber", c("#10B981"), c("#03130E"), c("#073626"), 2),
        WallWidget("w10", "Violet Vortex", "Abstract", c("#A855F7"), c("#090414"), c("#240C47"), 7),
        WallWidget("w11", "Crimson Radar", "HUD", c("#F43F5E"), c("#140308"), c("#2C0814"), 2),
        WallWidget("w12", "Arctic Frost", "Minimal", c("#7DD3FC"), c("#081420"), c("#13304A"), 5),
        WallWidget("w13", "Synthwave Grid", "Retro", c("#FF4ECD"), c("#120424"), c("#280B4E"), 8),
        WallWidget("w14", "Golden Circuit", "Cyber", c("#FBBF24"), c("#140E02"), c("#2F2106"), 1),
        WallWidget("w15", "Cobalt Mesh", "Geo", c("#60A5FA"), c("#040B1A"), c("#0E224A"), 3),
        WallWidget("w16", "Velvet Nebula", "Space", c("#C084FC"), c("#080312"), c("#1E0938"), 6),
        WallWidget("w17", "Neon Lime Code", "Cyber", c("#84CC16"), c("#060D02"), c("#142906"), 0),
        WallWidget("w18", "Amber Forge", "Abstract", c("#FB923C"), c("#150902"), c("#331705"), 7),
        WallWidget("w19", "Cyber Tokyo", "Neon", c("#EC4899"), c("#090412"), c("#29092D"), 8),
        WallWidget("w20", "Plasma Wave", "Wave", c("#22D3EE"), c("#04101A"), c("#0C2B42"), 4),
        WallWidget("w21", "Starlight Void", "Space", c("#E2E8F0"), c("#03050A"), c("#0F172A"), 5),
        WallWidget("w22", "Stealth Carbon", "Minimal", c("#94A3B8"), c("#090A0D"), c("#1A1D24"), 3),
        WallWidget("w23", "Coral Dusk", "Gradient", c("#FB7185"), c("#16070C"), c("#36111F"), 4),
        WallWidget("w24", "Mint Terminal", "Cyber", c("#2DD4BF"), c("#021210"), c("#082E2A"), 0),
        WallWidget("w25", "Royal Indigo", "Geo", c("#818CF8"), c("#06071A"), c("#141947"), 1),
        WallWidget("w26", "Laser Prism", "Abstract", c("#34D399"), c("#03110C"), c("#083324"), 6),
        WallWidget("w27", "Copper Wire", "Cyber", c("#F97316"), c("#140803"), c("#2E1408"), 1),
        WallWidget("w28", "Electric Cyan", "HUD", c("#06B6D4"), c("#021016"), c("#072B3A"), 2),
        WallWidget("w29", "Rose Quartz", "Minimal", c("#F472B6"), c("#14050E"), c("#310C22"), 5),
        WallWidget("w30", "Glacier Peak", "Space", c("#BAE6FD"), c("#06101B"), c("#122B45"), 7),
        WallWidget("w31", "Lava Core", "Abstract", c("#EF4444"), c("#140303"), c("#360909"), 6),
        WallWidget("w32", "Midnight Crescent", "Geo", c("#FDE047"), c("#040814"), c("#0B1B36"), 5)
    )

    fun byId(id: String): WallWidget? = ALL.firstOrNull { it.id == id }

    fun renderThumbnail(w: WallWidget, size: Int = 180): Bitmap {
        return renderBitmap(w, size, size)
    }

    fun renderBitmap(w: WallWidget, width: Int, height: Int): Bitmap {
        val W = width.coerceAtLeast(64)
        val H = height.coerceAtLeast(64)
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val fw = W.toFloat()
        val fh = H.toFloat()
        val minDim = minOf(fw, fh)

        p.shader = LinearGradient(0f, 0f, fw * 0.4f, fh, w.bg1, w.bg2, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, fw, fh, p)
        p.shader = null

        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        glowPaint.shader = RadialGradient(
            fw * 0.5f, fh * 0.38f, minDim * 0.65f,
            intArrayOf(Color.argb(95, Color.red(w.accent), Color.green(w.accent), Color.blue(w.accent)), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
        c.drawCircle(fw * 0.5f, fh * 0.38f, minDim * 0.65f, glowPaint)

        p.color = Color.argb(65, Color.red(w.accent), Color.green(w.accent), Color.blue(w.accent))
        p.strokeWidth = maxOf(1.5f, minDim * 0.004f)
        p.style = Paint.Style.STROKE

        when (w.style % 9) {
            0 -> { // Matrix columns
                val cols = 14
                val step = fw / cols
                for (i in 0..cols) {
                    val x = i * step
                    p.alpha = 28 + (i % 4) * 16
                    c.drawLine(x, 0f, x, fh, p)
                }
                p.style = Paint.Style.FILL
                for (i in 0 until 24) {
                    val cx = ((i * 73) % 100) / 100f * fw
                    val cy = ((i * 41) % 100) / 100f * fh
                    p.alpha = 90 + (i % 3) * 45
                    c.drawCircle(cx, cy, minDim * 0.009f, p)
                }
            }
            1 -> { // Cyber Circuit lines + nodes
                val step = minDim / 8f
                for (i in 1..6) {
                    val y = i * (fh / 7f)
                    p.alpha = 70
                    c.drawLine(0f, y, fw * 0.35f, y, p)
                    c.drawLine(fw * 0.35f, y, fw * 0.5f, y - step * 0.4f, p)
                    c.drawLine(fw * 0.5f, y - step * 0.4f, fw, y - step * 0.4f, p)
                }
                p.style = Paint.Style.FILL
                p.alpha = 180
                for (i in 1..6) {
                    val y = i * (fh / 7f)
                    c.drawCircle(fw * 0.35f, y, minDim * 0.012f, p)
                    c.drawCircle(fw * 0.5f, y - step * 0.4f, minDim * 0.012f, p)
                }
            }
            2 -> { // Concentric Radar HUD rings
                val cx = fw * 0.5f
                val cy = fh * 0.42f
                for (k in 1..5) {
                    p.alpha = 140 - k * 20
                    c.drawCircle(cx, cy, minDim * 0.12f * k, p)
                }
                c.drawLine(cx - minDim * 0.55f, cy, cx + minDim * 0.55f, cy, p)
                c.drawLine(cx, cy - minDim * 0.55f, cx, cy + minDim * 0.55f, p)
            }
            3 -> { // Hex / isometric diagonal mesh
                val step = minDim / 7f
                var x = -fh
                while (x < fw + fh) {
                    p.alpha = 52
                    c.drawLine(x, 0f, x + fh * 0.6f, fh, p)
                    c.drawLine(x + fh * 0.6f, 0f, x, fh, p)
                    x += step
                }
            }
            4 -> { // Smooth aurora waves
                val path = Path()
                for (band in 0..3) {
                    path.reset()
                    val baseY = fh * (0.3f + band * 0.14f)
                    path.moveTo(0f, baseY)
                    for (ix in 0..24) {
                        val t = ix / 24f
                        val py = baseY + sin(t * 6.28f + band * 1.1f) * minDim * 0.08f
                        path.lineTo(t * fw, py)
                    }
                    p.strokeWidth = minDim * (0.025f - band * 0.003f)
                    p.alpha = 95 - band * 15
                    c.drawPath(path, p)
                }
            }
            5 -> { // Starlight & constellation geometry
                p.style = Paint.Style.FILL
                for (i in 0 until 36) {
                    val sx = ((i * 97 + 17) % 100) / 100f * fw
                    val sy = ((i * 53 + 29) % 100) / 100f * fh
                    p.alpha = 90 + (i % 5) * 32
                    c.drawCircle(sx, sy, minDim * (0.004f + (i % 3) * 0.003f), p)
                }
                p.style = Paint.Style.STROKE
                p.alpha = 65
                c.drawCircle(fw * 0.5f, fh * 0.36f, minDim * 0.24f, p)
            }
            6 -> { // Orbital nebula arcs
                val rect = RectF(fw * 0.1f, fh * 0.2f, fw * 0.9f, fh * 0.2f + fw * 0.8f)
                for (k in 0..6) {
                    p.alpha = 110 - k * 12
                    p.strokeWidth = minDim * 0.01f
                    c.drawArc(rect, k * 35f, 190f, false, p)
                    rect.inset(minDim * 0.035f, minDim * 0.035f)
                }
            }
            7 -> { // Radial rays burst
                val cx = fw * 0.5f
                val cy = fh * 0.45f
                for (i in 0 until 24) {
                    val ang = (i * 15.0) * Math.PI / 180.0
                    p.alpha = if (i % 2 == 0) 90 else 40
                    c.drawLine(
                        cx + cos(ang).toFloat() * minDim * 0.08f,
                        cy + sin(ang).toFloat() * minDim * 0.08f,
                        cx + cos(ang).toFloat() * minDim * 0.75f,
                        cy + sin(ang).toFloat() * minDim * 0.75f,
                        p
                    )
                }
            }
            else -> { // Synthwave perspective grid
                val horizon = fh * 0.55f
                p.alpha = 120
                c.drawLine(0f, horizon, fw, horizon, p)
                for (i in 1..9) {
                    val f = (i / 9f)
                    val y = horizon + f * f * (fh - horizon)
                    p.alpha = 50 + i * 10
                    c.drawLine(0f, y, fw, y, p)
                }
                for (i in -6..6) {
                    val bx = fw * 0.5f + i * (fw * 0.16f)
                    c.drawLine(fw * 0.5f + i * (fw * 0.03f), horizon, bx, fh, p)
                }
            }
        }
        return bmp
    }

    fun applyHomeLockPref(ctx: Context): Boolean =
        ctx.getSharedPreferences("nova", Context.MODE_PRIVATE).getBoolean("wp_home_lock", true)

    fun setHomeLockPref(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences("nova", Context.MODE_PRIVATE).edit().putBoolean("wp_home_lock", on).apply()
    }

    /** Applies the current Theme or WallWidget bitmap to Home Screen and/or Lock Screen. */
    fun applySystemWallpaper(ctx: Context, bmp: Bitmap, applyHomeAndLock: Boolean, lockOnly: Boolean = false): String {
        val wm = WallpaperManager.getInstance(ctx.applicationContext)
        return try {
            when {
                applyHomeAndLock -> {
                    wm.setBitmap(bmp, null, true, WallpaperManager.FLAG_SYSTEM)
                    wm.setBitmap(bmp, null, true, WallpaperManager.FLAG_LOCK)
                    "Wallpaper applied to Home + Lock screen"
                }
                lockOnly -> {
                    wm.setBitmap(bmp, null, true, WallpaperManager.FLAG_LOCK)
                    "Wallpaper applied to Lock screen"
                }
                else -> {
                    wm.setBitmap(bmp, null, true, WallpaperManager.FLAG_SYSTEM)
                    "Wallpaper applied to Home screen"
                }
            }
        } catch (e: Exception) {
            "Wallpaper error: ${e.message}"
        }
    }
}
