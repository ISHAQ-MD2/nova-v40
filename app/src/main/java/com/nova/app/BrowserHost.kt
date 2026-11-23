package com.nova.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * NOVA BROWSER ki FLOATING WINDOW (kisi bhi app / game ke upar tairti hai).
 * - Title bar se kheencho = jagah badlo, neeche-right kone se kheencho = size badlo.
 * - Buttons: ⌨ (keyboard/typing ON-OFF), ▁ (chhupa do, gaana chalta rahe), ⤢ (poori screen), ✕ (band).
 * - Page par touch karo to khud typing mode ON hota hai, 20 second chup rehne par wapas OFF (taake game ka back/keys na ruken).
 * Is mein NOVA jo browser mein kar rahi hoti hai wahi live dikhta hai.
 */
class FloatWindow(private val ctx: Context) {
    @Volatile var shown = false
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val h = Handler(Looper.getMainLooper())
    private val d = ctx.resources.displayMetrics.density
    private var root: FrameLayout? = null
    private var titleTv: TextView? = null
    private var kbBtn: TextView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var typing = false
    private val unfocus = Runnable { setTyping(false) }

    private fun dp(x: Int) = (x * d).toInt()
    private fun prefs() = ctx.getSharedPreferences("nova", Context.MODE_PRIVATE)

    /** Web page isi mein lagta hai (NovaBrowser.attachActive). Page par pehli touch par typing mode ON. */
    val box: FrameLayout = object : FrameLayout(ctx) {
        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) wantTyping()
            return false
        }
    }

    private fun txt(t: String, size: Float = 15f, onClick: () -> Unit): TextView = TextView(ctx).apply {
        text = t; textSize = size; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }

    fun show() {
        if (shown) return
        val dm = ctx.resources.displayMetrics
        val sw = dm.widthPixels; val sh = dm.heightPixels
        val w = prefs().getInt("nb_fw", minOf(sw - dp(16), dp(330))).coerceIn(dp(180), sw)
        val hh = prefs().getInt("nb_fh", dp(260)).coerceIn(dp(150), sh)
        val x = prefs().getInt("nb_fx", (sw - w) / 2).coerceIn(0, maxOf(0, sw - dp(60)))
        val y = prefs().getInt("nb_fy", dp(70)).coerceIn(0, maxOf(0, sh - dp(60)))

        val frame = FrameLayout(ctx)
        frame.background = GradientDrawable().apply { setColor(Color.parseColor("#0B1220")); setStroke(dp(2), Color.parseColor("#00E5FF")); cornerRadius = dp(10).toFloat() }
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        val bar = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(Color.parseColor("#141E33")) }
        val tv = TextView(ctx).apply {
            text = "NOVA Browser"; textSize = 12f; setTextColor(Color.parseColor("#C8D4EA")); setSingleLine(); ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(10), 0, dp(4), 0)
        }
        titleTv = tv
        val kb = txt("⌨", 15f) { setTyping(!typing); if (typing) scheduleUnfocus() }
        kbBtn = kb
        bar.addView(tv, LinearLayout.LayoutParams(0, -1, 1f))
        bar.addView(kb, LinearLayout.LayoutParams(dp(34), -1))
        bar.addView(txt("▁", 15f) { NovaBrowser.minimizeToHidden() }, LinearLayout.LayoutParams(dp(34), -1))
        bar.addView(txt("⤢", 16f) { NovaBrowser.openUi(ctx) }, LinearLayout.LayoutParams(dp(34), -1))
        bar.addView(txt("✕", 15f) { NovaBrowser.window("close") }, LinearLayout.LayoutParams(dp(34), -1))
        col.addView(bar, LinearLayout.LayoutParams(-1, dp(32)))
        col.addView(box, LinearLayout.LayoutParams(-1, 0, 1f))
        frame.addView(col, FrameLayout.LayoutParams(-1, -1))

        val grip = TextView(ctx).apply {
            text = "◢"; textSize = 16f; setTextColor(Color.parseColor("#00E5FF")); gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#66000000"))
        }
        frame.addView(grip, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.BOTTOM or Gravity.END))

        val params = WindowManager.LayoutParams(w, hh, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED, PixelFormat.TRANSLUCENT)
        params.gravity = Gravity.TOP or Gravity.START
        params.x = x; params.y = y
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        lp = params

        // title bar kheench kar window hilao
        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f
        bar.setOnTouchListener { _, e ->
            val p = lp ?: return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = p.x; sy = p.y; tx = e.rawX; ty = e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    p.x = (sx + (e.rawX - tx)).toInt().coerceIn(-p.width + dp(60), sw - dp(60))
                    p.y = (sy + (e.rawY - ty)).toInt().coerceIn(0, sh - dp(40))
                    update()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> savePos()
            }
            true
        }
        // kone se kheench kar size badlo
        var sw0 = 0; var sh0 = 0; var gx = 0f; var gy = 0f
        grip.setOnTouchListener { _, e ->
            val p = lp ?: return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sw0 = p.width; sh0 = p.height; gx = e.rawX; gy = e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    p.width = (sw0 + (e.rawX - gx)).toInt().coerceIn(dp(180), sw)
                    p.height = (sh0 + (e.rawY - gy)).toInt().coerceIn(dp(150), sh)
                    update()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> savePos()
            }
            true
        }

        try {
            wm.addView(frame, params)
            root = frame
            shown = true
        } catch (_: Exception) {
            shown = false
            Toast.makeText(ctx, "Floating window ke liye 'Display over other apps' ON karo", Toast.LENGTH_LONG).show()
        }
    }

    private fun update() { try { root?.let { wm.updateViewLayout(it, lp) } } catch (_: Exception) {} }

    private fun savePos() {
        val p = lp ?: return
        prefs().edit().putInt("nb_fx", p.x).putInt("nb_fy", p.y).putInt("nb_fw", p.width).putInt("nb_fh", p.height).apply()
    }

    private fun wantTyping() {
        if (!typing) setTyping(true)
        scheduleUnfocus()
    }

    private fun scheduleUnfocus() { h.removeCallbacks(unfocus); h.postDelayed(unfocus, 20_000L) }

    /** Typing ON = window focus leti hai (keyboard aata hai); OFF = game/app ko keys wapas. */
    private fun setTyping(on: Boolean) {
        val p = lp ?: return
        typing = on
        p.flags = if (on) p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() else p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        update()
        kbBtn?.setTextColor(if (on) Color.parseColor("#00E5FF") else Color.WHITE)
        if (on) { try { NovaBrowser.active?.web?.requestFocus() } catch (_: Exception) {} } else h.removeCallbacks(unfocus)
    }

    fun setTitle(s: String) { h.post { titleTv?.text = s.ifEmpty { "NOVA Browser" } } }

    fun hide() {
        h.removeCallbacks(unfocus)
        try { box.removeAllViews() } catch (_: Exception) {}
        root?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        root = null; shown = false; typing = false
    }
}

/**
 * Floating / chhupa hua browser chal raha ho to process ko zinda rakhta hai (gaana, download). Notification se band ho sakta hai.
 */
class BrowserService : Service() {
    companion object {
        fun start(ctx: Context) {
            try {
                val i = Intent(ctx, BrowserService::class.java)
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (_: Throwable) {}
        }
        fun stop(ctx: Context) { try { ctx.stopService(Intent(ctx, BrowserService::class.java)) } catch (_: Throwable) {} }
    }

    private var wl: PowerManager.WakeLock? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        if (i?.action == "stop") {
            try { NovaBrowser.window("close") } catch (_: Throwable) {}
            stopSelf()
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("nova_browser", "NOVA Browser", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val stopPi = PendingIntent.getService(this, 1, Intent(this, BrowserService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "nova_browser")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("NOVA Browser chal raha hai")
            .setContentText("Kholne ke liye tap karo")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Band karo", stopPi).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) else startForeground(7, n)
        try {
            if (wl == null) {
                wl = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nova:browser")
                wl?.acquire(3 * 60 * 60 * 1000L)
            }
        } catch (_: Throwable) {}
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try { if (wl?.isHeld == true) wl?.release() } catch (_: Throwable) {}
        wl = null
        super.onDestroy()
    }
}

/** Website "file chuno" maange to phone ka file picker isi transparent activity se khulta hai, jawab NovaBrowser ko jata hai. */
class BrowserPickActivity : Activity() {
    private var done = false

    @Suppress("DEPRECATION")
    override fun onCreate(b: android.os.Bundle?) {
        super.onCreate(b)
        val inner: Intent? = try {
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra("inner", Intent::class.java) else intent.getParcelableExtra("inner")
        } catch (_: Exception) { null }
        if (inner == null) { finishWith(null); return }
        try { startActivityForResult(inner, 77) } catch (_: Exception) { finishWith(null) }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val r: Array<Uri>? = try { WebChromeClient.FileChooserParams.parseResult(resultCode, data) } catch (_: Exception) { null }
        finishWith(r)
    }

    private fun finishWith(r: Array<Uri>?) {
        if (done) return
        done = true
        NovaBrowser.fileChosen(r)
        finish()
    }

    override fun onDestroy() {
        if (!done) { done = true; NovaBrowser.fileChosen(null) }
        super.onDestroy()
    }
}
