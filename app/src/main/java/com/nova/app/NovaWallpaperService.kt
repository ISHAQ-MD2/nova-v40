package com.nova.app

import android.graphics.Canvas
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.MotionEvent
import android.view.SurfaceHolder

/**
 * v30 ANDROID LIVE WALLPAPER: Hacker phone. Sab data asli (battery, storage, RAM, net, reminders, voice text, nayi files, Termux ...).
 * Drawing HackerWall.kt mein hai; yahan sirf surface, touch aur frame loop. Wallpaper chhupne par sampling band (battery bachti hai).
 */
class NovaWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = NovaEngine()

    inner class NovaEngine : Engine() {
        private var wall: HackerWall? = null
        private val h = Handler(Looper.getMainLooper())
        private var visible = false
        private var held = false
        private fun hold() { if (!held) { held = true; try { WallData.start(applicationContext) } catch (_: Throwable) {} } }
        private fun release() { BrowserMirror.wanted = false; if (held) { held = false; try { WallData.stop() } catch (_: Throwable) {} } }
        private val tick = object : Runnable { override fun run() { drawFrame(); if (visible) h.postDelayed(this, 33) } }

        override fun onCreate(sh: SurfaceHolder) {
            super.onCreate(sh)
            setTouchEventsEnabled(true)
            wall = HackerWall(applicationContext)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            wall?.resize(width, height)
            drawFrame()
        }

        override fun onVisibilityChanged(v: Boolean) {
            visible = v
            h.removeCallbacks(tick)
            if (v) {
                hold()
                try { wall?.reloadStyle() } catch (_: Throwable) {}
                h.post(tick)
            } else release()
        }

        override fun onOffsetsChanged(xOffset: Float, yOffset: Float, xStep: Float, yStep: Float, xPixels: Int, yPixels: Int) {
            wall?.panX = -(xOffset - 0.5f) * 40f
        }

        override fun onTouchEvent(e: MotionEvent) {
            val down = e.actionMasked != MotionEvent.ACTION_UP && e.actionMasked != MotionEvent.ACTION_CANCEL
            wall?.touch(e.x, e.y, down)
        }

        /** Kai launcher raw touch nahi dete, sirf tap command bhejte hain - us par bhi worms aate hain. */
        override fun onCommand(action: String?, x: Int, y: Int, z: Int, extras: Bundle?, resultRequested: Boolean): Bundle? {
            if (action == "android.wallpaper.tap" || action == "android.wallpaper.secondaryTap") wall?.tap(x.toFloat(), y.toFloat())
            return super.onCommand(action, x, y, z, extras, resultRequested)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) { visible = false; h.removeCallbacks(tick); release(); super.onSurfaceDestroyed(holder) }
        override fun onDestroy() { visible = false; h.removeCallbacks(tick); release(); super.onDestroy() }

        private fun drawFrame() {
            val wl = wall ?: return
            if (wl.w <= 0 || wl.h <= 0) return
            val holder = surfaceHolder
            var c: Canvas? = null
            try {
                c = holder.lockCanvas()
                if (c != null) wl.draw(c)
            } catch (_: Throwable) {
            } finally { try { if (c != null) holder.unlockCanvasAndPost(c) } catch (_: Throwable) {} }
        }
    }
}
