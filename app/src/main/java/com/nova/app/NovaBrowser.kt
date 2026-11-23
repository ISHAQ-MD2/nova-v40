package com.nova.app

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.MediaScannerConnection
import android.net.Uri
import android.net.http.SslError
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.webkit.ProfileStore
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object BrowserMirror {
    @Volatile var wanted = false
    @Volatile var frame: Bitmap? = null
    @Volatile var frameAt = 0L
    @Volatile var label = ""
}

/**
 * NOVA Browser Engine (Soul / UC Browser level):
 * - Persistent logins & cookies (never cleared on exit; flushed on navigation/pause)
 * - Normal & Incognito tabs (isolated history/passwords; safe cookie handling)
 * - AdBlock, Night mode, Text zoom, Custom homepage (nova://home), HTTP warning flag
 * - Video sniffer (.mp4, .webm, .m3u8) + Media3 VideoPlayerActivity / DownloadVault handoff
 * - Full AI tool bridge (browser_*, AiRouter coding providers)
 */
object NovaBrowser {
    class Tab(val id: Int, var web: WebView, val incognito: Boolean = false) {
        @Volatile var url = ""
        @Volatile var title = ""
        @Volatile var progress = 0
        @Volatile var loading = false
        @Volatile var error = ""
        @Volatile var desktop = false
        @Volatile var insecureHttp = false
        val sniffedVideos = CopyOnWriteArrayList<String>()
    }

    class Dl(val name: String, val url: String, var id: Long, @Volatile var status: String)
    enum class Host { NONE, ACTIVITY, FLOAT, HIDDEN }

    private lateinit var app: Context
    @Volatile private var inited = false
    val main = Handler(Looper.getMainLooper())

    val tabs = CopyOnWriteArrayList<Tab>()
    @Volatile var active: Tab? = null
    @Volatile var host = Host.NONE
    @Volatile var activity: BrowserActivity? = null
    @Volatile var onChange: (() -> Unit)? = null
    @Volatile var onCredsCaptured: ((host: String, user: String, pass: String) -> Unit)? = null
    @Volatile var mediaWanted = false
    @Volatile var lastUse = System.currentTimeMillis()
    @Volatile var presetFiles: List<File> = emptyList()
    val downloads = CopyOnWriteArrayList<Dl>()

    private var nextId = 1
    private var floatWin: FloatWindow? = null
    private var hiddenBox: FrameLayout? = null
    private var ytTab: Tab? = null
    private var ytResults: List<Pair<String, String>> = emptyList()
    private var ytIdx = 0
    @Volatile private var autoDlUntil = 0L
    private var fileCb: ValueCallback<Array<Uri>>? = null

    fun init(ctx: Context) {
        if (inited) return
        synchronized(this) {
            if (inited) return
            inited = true
            app = ctx.applicationContext
        }
        main.post {
            try { cookies().setAcceptCookie(true) } catch (_: Throwable) {}
            try { ContextCompat.registerReceiver(app, dlRecv, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED) } catch (_: Throwable) {}
            try { Vol.addSpeakListener(duckListener) } catch (_: Throwable) {}
            main.postDelayed(mirrorRun, 1500L)
            main.postDelayed(idleRun, 20_000L)
        }
    }

    private fun prefs() = app.getSharedPreferences("nova", Context.MODE_PRIVATE)
    private fun dp(x: Int) = (x * app.resources.displayMetrics.density).toInt()
    private fun wm() = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    fun overlayOk() = try { Settings.canDrawOverlays(app) } catch (_: Exception) { false }
    fun ytInBrowser(ctx: Context) = ctx.getSharedPreferences("nova", Context.MODE_PRIVATE).getBoolean("nb_yt", true)
    fun home(): String = prefs().getString("nb_home", BrowserScripts.HOME_URL) ?: BrowserScripts.HOME_URL
    fun adBlockEnabled(): Boolean = prefs().getBoolean("nb_adblock", true)
    fun setAdBlock(on: Boolean) { prefs().edit().putBoolean("nb_adblock", on).apply() }
    fun nightMode(): Boolean = prefs().getBoolean("nb_night", false)
    fun setNightMode(on: Boolean) {
        prefs().edit().putBoolean("nb_night", on).apply()
        main.post { for (t in tabs) applyNightToWeb(t.web, on) }
    }
    fun textZoom(): Int = prefs().getInt("nb_zoom", 100).coerceIn(50, 200)
    fun setTextZoom(z: Int) {
        val c = z.coerceIn(50, 200)
        prefs().edit().putInt("nb_zoom", c).apply()
        main.post { for (t in tabs) try { t.web.settings.textZoom = c } catch (_: Throwable) {} }
    }

    private fun changed() { main.post { try { onChange?.invoke() } catch (_: Throwable) {} } }
    private fun toast(s: String) { main.post { try { Toast.makeText(app, s, Toast.LENGTH_SHORT).show() } catch (_: Throwable) {} } }

    // ------------------------------------------------------------------ profiles & cookies
    fun profileName(): String = prefs().getString("nb_profile", "me") ?: "me"
    fun profileLabel(): String = if (profileName() == "nova") "Nova Profile" else "Personal Profile"
    private fun profileKey(incognito: Boolean = false): String? = when {
        incognito -> "Incognito"
        profileName() == "nova" -> "NovaBot"
        else -> null
    }
    fun profilesSupported(): Boolean = try { WebViewFeature.isFeatureSupported("MULTI_PROFILE") } catch (_: Throwable) { false }

    private fun cookies(incognito: Boolean = false): CookieManager {
        val k = profileKey(incognito)
        if (k != null && profilesSupported()) {
            try { return ProfileStore.getInstance().getOrCreateProfile(k).cookieManager } catch (_: Throwable) {}
        }
        return CookieManager.getInstance()
    }

    fun flushCookies() {
        try { CookieManager.getInstance().flush() } catch (_: Throwable) {}
        if (profilesSupported()) {
            try { ProfileStore.getInstance().getOrCreateProfile("NovaBot").cookieManager.flush() } catch (_: Throwable) {}
        }
    }

    fun setProfile(name: String): String {
        val n = if (name.trim().lowercase().startsWith("n")) "nova" else "me"
        if (n == "nova" && !profilesSupported()) return "Separate browser profiles unsupported on this device WebView."
        prefs().edit().putString("nb_profile", n).apply()
        ui { closeAllTabs() }
        return (if (n == "nova") "Nova Profile ON" else "Personal Profile ON") + " — new tabs keep separate logins"
    }

    fun <T> ui(timeoutMs: Long = 8000L, f: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return try { f() } catch (_: Exception) { null }
        val box = arrayOfNulls<Any>(1)
        val latch = CountDownLatch(1)
        main.post { try { box[0] = f() } catch (_: Exception) {} finally { latch.countDown() } }
        try { latch.await(timeoutMs, TimeUnit.MILLISECONDS) } catch (_: Exception) {}
        @Suppress("UNCHECKED_CAST")
        return box[0] as T?
    }

    private fun decode(r: String?): String {
        if (r == null || r == "null") return ""
        return try { val v = JSONTokener(r).nextValue(); if (v is String) v else v.toString() } catch (_: Exception) { r }
    }

    fun evalSync(js: String, timeoutMs: Long = 6000L, tab: Tab? = null): String {
        val box = arrayOfNulls<String>(1)
        val latch = CountDownLatch(1)
        val run = Runnable {
            val w = (tab ?: active)?.web
            if (w == null) { latch.countDown(); return@Runnable }
            try { w.evaluateJavascript(js) { r -> box[0] = decode(r); latch.countDown() } } catch (_: Exception) { latch.countDown() }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) { run.run(); return "" }
        main.post(run)
        try { latch.await(timeoutMs, TimeUnit.MILLISECONDS) } catch (_: Exception) {}
        return box[0] ?: ""
    }

    private fun evalAsync(js: String, tab: Tab? = null) {
        main.post { try { (tab ?: active)?.web?.evaluateJavascript(js, null) } catch (_: Exception) {} }
    }

    // ------------------------------------------------------------------ user agent
    private fun baseUa(): String {
        val d = try { WebSettings.getDefaultUserAgent(app) }
        catch (_: Exception) { "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36" }
        return d.replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+\\s"), "")
    }
    private fun desktopUa() = baseUa().replace(Regex("\\(Linux; Android[^)]*\\)"), "(X11; Linux x86_64)").replace(" Mobile", "")

    fun setDesktop(t: Tab, on: Boolean) {
        t.desktop = on
        t.web.settings.userAgentString = if (on) desktopUa() else baseUa()
        t.web.settings.useWideViewPort = true
        t.web.reload()
    }

    private fun applyNightToWeb(w: WebView, on: Boolean) {
        val s = w.settings
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, on)
            } else if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                @Suppress("DEPRECATION")
                WebSettingsCompat.setForceDark(s, if (on) WebSettingsCompat.FORCE_DARK_ON else WebSettingsCompat.FORCE_DARK_OFF)
            }
        } catch (_: Throwable) {}
        try { w.evaluateJavascript(if (on) BrowserScripts.JS_NIGHT_ON else BrowserScripts.JS_NIGHT_OFF, null) } catch (_: Throwable) {}
    }

    // ------------------------------------------------------------------ webview builder
    private fun makeWeb(incognito: Boolean): WebView {
        val w = WebView(app)
        profileKey(incognito)?.let { k ->
            if (profilesSupported()) {
                try { WebViewCompat.setProfile(w, k) } catch (_: Throwable) {}
            }
        }
        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        @Suppress("DEPRECATION") s.databaseEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = true
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.setSupportZoom(true)
        s.textZoom = textZoom()
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.userAgentString = baseUa()
        if (incognito) {
            @Suppress("DEPRECATION")
            s.saveFormData = false
            s.cacheMode = WebSettings.LOAD_NO_CACHE
        }
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, emptySet())
            }
        } catch (_: Throwable) {}
        try {
            val cm = cookies(incognito)
            cm.setAcceptCookie(true)
            cm.setAcceptThirdPartyCookies(w, true)
        } catch (_: Throwable) {}
        w.webViewClient = client
        w.webChromeClient = chrome
        try { w.addJavascriptInterface(DlBridge(w), "NovaDl") } catch (_: Throwable) {}
        w.setDownloadListener { url, ua, cd, mime, _ -> startDownload(url, ua, cd, mime, findTab(w)?.url ?: "", w) }
        w.setOnLongClickListener { v -> val a = activity; if (a == null) false else a.onLongPress((v as WebView).hitTestResult) }
        applyNightToWeb(w, nightMode())
        try { w.layout(0, 0, dp(360), dp(640)) } catch (_: Throwable) {}
        return w
    }

    private fun findTab(v: WebView?): Tab? = tabs.firstOrNull { it.web === v }

    private fun recordSniffed(t: Tab?, rawUrl: String?) {
        val u = rawUrl?.trim() ?: return
        if (!u.startsWith("http") || DownloadVault.isYouTubeUrl(u)) return
        val low = u.lowercase()
        val isVideo = (low.contains(".mp4") || low.contains(".webm") || low.contains(".m3u8")) &&
            !low.endsWith(".ts") && !low.contains(".m4s")
        if (isVideo && t != null && u !in t.sniffedVideos) {
            if (t.sniffedVideos.size < 25) {
                t.sniffedVideos.add(0, u)
                changed()
            }
        }
    }

    // ------------------------------------------------------------------ clients
    private val client = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val u = request?.url ?: return false
            val s = u.toString()
            if (s == BrowserScripts.HOME_URL) {
                findTab(view)?.let { loadIntoTab(it, s) }
                return true
            }
            val sc = u.scheme ?: ""
            return when (sc) {
                "http", "https", "about", "data", "blob" -> false
                "intent" -> {
                    try {
                        val i = Intent.parseUri(s, Intent.URI_INTENT_SCHEME)
                        val fb = i.getStringExtra("browser_fallback_url")
                        if (fb != null) view?.loadUrl(fb)
                    } catch (_: Exception) {}
                    true
                }
                "tel", "mailto", "sms", "geo" -> {
                    try { app.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
                    true
                }
                else -> true
            }
        }

        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            val req = request ?: return null
            val u = req.url?.toString() ?: return null
            recordSniffed(findTab(view), u)
            if (adBlockEnabled() && !req.isForMainFrame && AdBlocker.shouldBlock(u)) {
                return AdBlocker.emptyResponse()
            }
            return super.shouldInterceptRequest(view, request)
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            val t = findTab(view) ?: return
            val actualUrl = if (url == "https://nova.home/") BrowserScripts.HOME_URL else (url ?: t.url)
            t.loading = true
            t.error = ""
            t.url = actualUrl
            t.insecureHttp = actualUrl.startsWith("http://", ignoreCase = true)
            t.sniffedVideos.clear()
            if (actualUrl.contains("youtube.com")) evalAsync(JS_BG, t)
            changed()
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            val t = findTab(view) ?: return
            val actualUrl = if (url == "https://nova.home/") BrowserScripts.HOME_URL else (url ?: t.url)
            t.loading = false
            t.url = actualUrl
            t.insecureHttp = actualUrl.startsWith("http://", ignoreCase = true)
            t.title = if (actualUrl == BrowserScripts.HOME_URL) "NOVA Home" else (view?.title ?: t.title)
            if (!t.incognito) {
                flushCookies()
                addHistory(t.title, t.url)
                saveTabs()
            }
            evalAsync(BrowserScripts.JS_SNIFF, t)
            if (!t.incognito) evalAsync(BrowserScripts.JS_PASS_HOOK, t)
            if (nightMode()) applyNightToWeb(t.web, true)
            if (t.url.contains("youtube.com")) {
                evalAsync(JS_BG, t)
                if (prefs().getBoolean("adskip", true)) evalAsync(JS_AD_SKIP, t)
            }
            if (System.currentTimeMillis() < autoDlUntil) scheduleAutoDownload(t)
            changed()
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            if (request?.isForMainFrame != true) return
            val t = findTab(view) ?: return
            val why = error?.description?.toString() ?: "error"
            t.error = why; t.loading = false
            val u = request.url.toString()
            val html = "<html><body style='background:#0B1220;color:#E3E9F2;font-family:sans-serif;padding:28px'>" +
                "<h2>Page failed to load</h2><p>" + why.replace("<", "&lt;") + "</p><p style='word-break:break-all;color:#8FA3BF'>" +
                u.replace("<", "&lt;") + "</p><p><a style='color:#00E5FF' href='" + u.replace("'", "") + "'>Try again</a></p></body></html>"
            try { view?.loadDataWithBaseURL(u, html, "text/html", "UTF-8", u) } catch (_: Exception) {}
            changed()
        }

        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
            handler?.cancel()
            findTab(view)?.error = "Unsafe SSL certificate"
        }

        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
            val t = findTab(view) ?: return true
            val u = t.url
            try {
                (t.web.parent as? ViewGroup)?.removeView(t.web)
                t.web.destroy()
                t.web = makeWeb(t.incognito)
                if (active === t) attachActive()
                if (u.isNotEmpty()) loadIntoTab(t, u)
            } catch (_: Exception) {}
            return true
        }
    }

    private val chrome = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            val t = findTab(view) ?: return
            t.progress = newProgress; changed()
        }

        override fun onReceivedTitle(view: WebView?, title: String?) {
            val t = findTab(view) ?: return
            if (t.url != BrowserScripts.HOME_URL) t.title = title ?: ""
            changed()
        }

        override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
            if (!isUserGesture || resultMsg == null) return false
            val inc = findTab(view)?.incognito == true
            val t = newTab(null, true, inc)
            val tr = resultMsg.obj as? WebView.WebViewTransport ?: return false
            tr.webView = t.web
            resultMsg.sendToTarget()
            return true
        }

        override fun onCloseWindow(window: WebView?) { findTab(window)?.let { closeTab(it) } }

        override fun onShowFileChooser(webView: WebView?, filePathCallback: ValueCallback<Array<Uri>>?, params: WebChromeClient.FileChooserParams?): Boolean {
            val cb = filePathCallback ?: return false
            val pre = presetFiles
            if (pre.isNotEmpty()) {
                presetFiles = emptyList()
                val one = params?.mode != WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
                val list = if (one) pre.take(1) else pre
                cb.onReceiveValue(list.map { Uri.fromFile(it) }.toTypedArray())
                return true
            }
            try { fileCb?.onReceiveValue(null) } catch (_: Exception) {}
            fileCb = cb
            val inner = try { params?.createIntent() } catch (_: Exception) { null }
                ?: Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            try {
                val i = Intent(app, BrowserPickActivity::class.java).putExtra("inner", inner).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val c: Context = NovaAccessibilityService.instance ?: app
                c.startActivity(i)
            } catch (_: Exception) { fileChosen(null) }
            return true
        }

        override fun onPermissionRequest(request: PermissionRequest?) {
            val r = request ?: return
            val ok = ArrayList<String>()
            for (res in r.resources) {
                when (res) {
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> if (granted(android.Manifest.permission.RECORD_AUDIO)) ok.add(res)
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> if (granted(android.Manifest.permission.CAMERA)) ok.add(res)
                    PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID -> ok.add(res)
                }
            }
            try { if (ok.isEmpty()) r.deny() else r.grant(ok.toTypedArray()) } catch (_: Exception) {}
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: android.webkit.GeolocationPermissions.Callback?) {
            callback?.invoke(origin, false, false)
        }

        override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
            val a = activity
            if (a == null) { result?.confirm(); return true }
            try {
                android.app.AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert).setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result?.confirm() }.setOnCancelListener { result?.cancel() }.show()
            } catch (_: Exception) { result?.confirm() }
            return true
        }

        override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
            val a = activity
            if (a == null) { result?.cancel(); return true }
            try {
                android.app.AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert).setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result?.confirm() }.setNegativeButton("Cancel") { _, _ -> result?.cancel() }
                    .setOnCancelListener { result?.cancel() }.show()
            } catch (_: Exception) { result?.cancel() }
            return true
        }

        override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?): Boolean {
            val a = activity
            if (a == null) { result?.cancel(); return true }
            try {
                val et = android.widget.EditText(a); et.setText(defaultValue ?: "")
                android.app.AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert).setMessage(message).setView(et)
                    .setPositiveButton("OK") { _, _ -> result?.confirm(et.text.toString()) }.setNegativeButton("Cancel") { _, _ -> result?.cancel() }
                    .setOnCancelListener { result?.cancel() }.show()
            } catch (_: Exception) { result?.cancel() }
            return true
        }

        override fun onShowCustomView(view: View?, callback: WebChromeClient.CustomViewCallback?) {
            val a = activity
            if (a == null || view == null) { callback?.onCustomViewHidden(); return }
            a.showCustom(view, callback)
        }

        override fun onHideCustomView() { activity?.hideCustom() }
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(app, p) == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun fileChosen(uris: Array<Uri>?) {
        try { fileCb?.onReceiveValue(uris) } catch (_: Exception) {}
        fileCb = null
    }

    // ------------------------------------------------------------------ tabs & persistence
    private fun loadIntoTab(t: Tab, rawUrl: String) {
        val u = normalize(rawUrl)
        t.loading = true
        t.url = u
        t.insecureHttp = u.startsWith("http://", ignoreCase = true)
        if (u == BrowserScripts.HOME_URL) {
            t.title = "NOVA Home"
            t.loading = false
            t.web.loadDataWithBaseURL("https://nova.home/", BrowserScripts.homeHtml(bookmarks()), "text/html", "UTF-8", BrowserScripts.HOME_URL)
        } else {
            t.web.loadUrl(u)
        }
    }

    fun newTab(url: String?, switch: Boolean = true, incognito: Boolean = false): Tab {
        val t = Tab(nextId++, makeWeb(incognito), incognito)
        tabs.add(t)
        if (switch || active == null) setTabActive(t)
        if (url != null) loadIntoTab(t, url)
        saveTabs()
        changed()
        return t
    }

    fun setTabActive(t: Tab) {
        active = t
        attachActive()
        saveTabs()
        changed()
    }

    fun closeTab(t: Tab) {
        tabs.remove(t)
        try { (t.web.parent as? ViewGroup)?.removeView(t.web); t.web.stopLoading(); t.web.destroy() } catch (_: Exception) {}
        if (t.incognito && tabs.none { it.incognito } && profilesSupported()) {
            try {
                val incProf = ProfileStore.getInstance().getOrCreateProfile("Incognito")
                incProf.cookieManager.removeAllCookies(null)
                incProf.cookieManager.flush()
                incProf.webStorage.deleteAllData()
            } catch (_: Throwable) {}
        }
        if (ytTab === t) ytTab = null
        if (active === t) {
            val n = tabs.lastOrNull()
            active = n
            if (n != null) attachActive() else mediaWanted = false
        }
        saveTabs()
        changed()
    }

    fun closeAllTabs() {
        for (t in tabs.toList()) closeTab(t)
        mediaWanted = false
        saveTabs()
        changed()
    }

    fun saveTabs() {
        try {
            val arr = JSONArray()
            var actIdx = 0
            var idx = 0
            for (t in tabs) {
                if (t.incognito) continue
                val u = t.url.ifBlank { BrowserScripts.HOME_URL }
                arr.put(u)
                if (t === active) actIdx = idx
                idx++
            }
            prefs().edit().putString("nb_saved_tabs", arr.toString()).putInt("nb_saved_idx", actIdx).apply()
        } catch (_: Exception) {}
    }

    fun restoreTabsIfNeeded() {
        if (tabs.isNotEmpty()) return
        try {
            val raw = prefs().getString("nb_saved_tabs", "[]") ?: "[]"
            val arr = JSONArray(raw)
            if (arr.length() == 0) {
                newTab(home(), true)
                return
            }
            val actIdx = prefs().getInt("nb_saved_idx", 0)
            for (i in 0 until arr.length()) {
                val u = arr.optString(i).ifBlank { home() }
                newTab(u, i == actIdx)
            }
            if (active == null && tabs.isNotEmpty()) setTabActive(tabs.first())
        } catch (_: Exception) {
            if (tabs.isEmpty()) newTab(home(), true)
        }
    }

    private fun container(): FrameLayout? = when (host) {
        Host.ACTIVITY -> activity?.webBox
        Host.FLOAT -> floatWin?.box
        Host.HIDDEN -> hiddenBox
        Host.NONE -> null
    }

    fun attachActive() {
        val t = active ?: return
        val c = container() ?: return
        val w = t.web
        if (w.parent === c) return
        (w.parent as? ViewGroup)?.removeView(w)
        c.removeAllViews()
        val lp = if (host == Host.HIDDEN) FrameLayout.LayoutParams(dp(360), dp(640)) else FrameLayout.LayoutParams(-1, -1)
        c.addView(w, lp)
        floatWin?.setTitle(t.title.ifEmpty { t.url })
    }

    private fun detachActive() { active?.let { (it.web.parent as? ViewGroup)?.removeView(it.web) } }
    private fun pauseAll() { try { for (t in tabs) t.web.onPause() } catch (_: Throwable) {} }
    private fun resumeAll() { try { for (t in tabs) t.web.onResume() } catch (_: Throwable) {} }

    // ------------------------------------------------------------------ hosts
    fun bindActivity(a: BrowserActivity) {
        activity = a
        hideHosts()
        host = Host.ACTIVITY
        attachActive()
        resumeAll()
    }

    fun unbindActivity(a: BrowserActivity) {
        if (activity !== a) return
        activity = null
        flushCookies()
        saveTabs()
        try { a.webBox.removeAllViews() } catch (_: Exception) {}
        host = Host.NONE
        backgroundHost()
    }

    private fun backgroundHost() {
        if (tabs.isEmpty()) { hideHosts(); host = Host.NONE; BrowserService.stop(app); return }
        if (overlayOk() && prefs().getBoolean("nb_float", false)) showFloat()
        else if (overlayOk() && mediaWanted) showHidden()
        else { hideHosts(); host = Host.NONE; detachActive(); pauseAll(); BrowserService.stop(app) }
    }

    private fun hideHosts() {
        floatWin?.hide(); floatWin = null
        hiddenBox?.let { b -> try { b.removeAllViews(); wm().removeView(b) } catch (_: Exception) {} }
        hiddenBox = null
    }

    fun showFloat() {
        if (!overlayOk()) return
        if (host == Host.FLOAT && floatWin?.shown == true) return
        hideHosts()
        val f = FloatWindow(app); floatWin = f; f.show()
        host = Host.FLOAT
        attachActive(); resumeAll()
        BrowserService.start(app)
    }

    fun showHidden() {
        if (!overlayOk()) return
        if (host == Host.HIDDEN && hiddenBox != null) return
        hideHosts()
        val box = FrameLayout(app)
        val lp = WindowManager.LayoutParams(2, 2, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED, PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.BOTTOM or Gravity.END
        try { wm().addView(box, lp) } catch (_: Exception) { return }
        hiddenBox = box
        host = Host.HIDDEN
        attachActive(); resumeAll()
        BrowserService.start(app)
    }

    fun openUi(ctx: Context, url: String? = null) {
        init(ctx)
        val i = Intent(app, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (url != null) i.putExtra("url", url)
        val c: Context = NovaAccessibilityService.instance ?: app
        c.startActivity(i)
    }

    fun ensureHost(): Boolean {
        if (host != Host.NONE) return true
        if (overlayOk()) {
            ui { if (host == Host.NONE) { if (prefs().getBoolean("nb_float", false)) showFloat() else showHidden() } }
            return host != Host.NONE
        }
        try { openUi(app) } catch (_: Exception) {}
        for (i in 0 until 20) { if (host == Host.ACTIVITY) return true; Thread.sleep(250) }
        return false
    }

    fun setFloatPref(ctx: Context, on: Boolean): String {
        init(ctx)
        prefs().edit().putBoolean("nb_float", on).apply()
        if (on && !overlayOk()) return "Enable 'Display over other apps' in Setup for floating browser."
        main.post {
            if (on) { if (activity == null && tabs.isNotEmpty()) showFloat() }
            else if (host == Host.FLOAT) backgroundHost()
        }
        return if (on) "Floating browser window ON" else "Floating browser window OFF"
    }

    fun window(mode: String): String {
        return when (mode.lowercase()) {
            "float", "floating" -> {
                if (!overlayOk()) return "Display over other apps permission required"
                prefs().edit().putBoolean("nb_float", true).apply()
                ui { if (activity == null) showFloat() }
                "Floating browser window opened"
            }
            "hidden", "background", "bg", "minimize" -> {
                prefs().edit().putBoolean("nb_float", false).apply()
                if (!overlayOk()) return "Display over other apps permission required"
                ui { if (activity == null) showHidden() }
                "Browser running in background"
            }
            "full", "open", "fullscreen" -> { openUi(app); "NOVA Browser opened full screen" }
            "close", "stop" -> {
                flushCookies()
                saveTabs()
                ui { mediaWanted = false; activity?.finish(); hideHosts(); host = Host.NONE; detachActive(); pauseAll(); BrowserService.stop(app) }
                "Browser window closed (tabs and logins stay saved)"
            }
            else -> "mode: float, hidden, full or close"
        }
    }

    fun minimizeToHidden() { main.post { if (activity == null) showHidden() } }

    // ------------------------------------------------------------------ URL helpers
    fun normalize(q0: String): String {
        val q = q0.trim()
        if (q.isEmpty()) return home()
        if (q == BrowserScripts.HOME_URL) return q
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:.*").matches(q) && (q.contains("://") || q.startsWith("about:"))) return q
        if (!q.contains(' ') && (Regex("^[\\w-]+(\\.[\\w-]+)+(:\\d+)?([/?#].*)?\$").matches(q) || q.startsWith("localhost"))) return "https://$q"
        return "https://www.google.com/search?q=" + URLEncoder.encode(q, "UTF-8")
    }

    private fun normalizeDl(u: String): String {
        Regex("drive\\.google\\.com/file/d/([\\w-]+)").find(u)?.let { return "https://drive.google.com/uc?export=download&id=" + it.groupValues[1] }
        Regex("drive\\.google\\.com/open\\?id=([\\w-]+)").find(u)?.let { return "https://drive.google.com/uc?export=download&id=" + it.groupValues[1] }
        return u
    }

    private fun loadSmart(u: String, inNewTab: Boolean): Tab {
        val a = active
        val protect = a != null && a === ytTab && mediaWanted
        return if (inNewTab || a == null || protect) newTab(u, true) else a.also { loadIntoTab(it, u) }
    }

    fun routeUrl(u: String) { loadSmart(normalize(u), false) }
    fun handlesLinks(ctx: Context) = ctx.getSharedPreferences("nova", Context.MODE_PRIVATE).getBoolean("nb_open", true)

    fun openVisible(ctx: Context, url: String): String {
        init(ctx)
        lastUse = System.currentTimeMillis()
        val u = normalize(url)
        if (overlayOk() && prefs().getBoolean("nb_float", false) && activity == null) {
            ui { if (host != Host.FLOAT) showFloat(); loadSmart(u, false) }
            return "Opened in NOVA floating browser: $u"
        }
        if (activity != null) { ui { loadSmart(u, false) }; return "Opened in NOVA browser: $u" }
        openUi(ctx, u)
        return "Opened NOVA Browser: $u"
    }

    fun userGo(text: String) {
        val a = active
        if (a == null) newTab(normalize(text), true) else loadIntoTab(a, text)
    }

    fun autofillSavedCreds(t: Tab): Boolean {
        val cred = PasswordStore.get(app, t.url) ?: return false
        evalAsync(BrowserScripts.jsFillCreds(cred.user, cred.pass), t)
        return true
    }

    fun describe(): String {
        val t = active ?: return "No page open"
        return (t.title.ifEmpty { "(no title)" }) + " - " + t.url
    }

    // ------------------------------------------------------------------ history / bookmarks
    fun addHistory(title: String, url: String) {
        if (!url.startsWith("http") || url.startsWith("https://nova.home")) return
        try {
            val arr = JSONArray(prefs().getString("nb_hist", "[]") ?: "[]")
            if (arr.length() > 0 && arr.getJSONObject(0).optString("u") == url) return
            val n = JSONArray()
            n.put(JSONObject().put("t", title).put("u", url).put("at", System.currentTimeMillis()))
            for (i in 0 until minOf(arr.length(), 149)) n.put(arr.get(i))
            prefs().edit().putString("nb_hist", n.toString()).apply()
        } catch (_: Exception) {}
    }

    fun history(): List<Pair<String, String>> = readList("nb_hist")
    fun bookmarks(): List<Pair<String, String>> = readList("nb_bm")
    private fun readList(k: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        try { val a = JSONArray(prefs().getString(k, "[]") ?: "[]"); for (i in 0 until a.length()) { val o = a.getJSONObject(i); out.add(o.optString("t") to o.optString("u")) } } catch (_: Exception) {}
        return out
    }
    fun addBookmark(title: String, url: String) {
        if (url == BrowserScripts.HOME_URL) return
        try {
            val a = JSONArray(prefs().getString("nb_bm", "[]") ?: "[]")
            for (i in 0 until a.length()) if (a.getJSONObject(i).optString("u") == url) return
            a.put(JSONObject().put("t", title.ifEmpty { url }).put("u", url))
            prefs().edit().putString("nb_bm", a.toString()).apply()
        } catch (_: Exception) {}
    }
    fun clearHistory() { prefs().edit().putString("nb_hist", "[]").apply() }

    /** Explicit manual logout/wipe ONLY when user taps Clear Data. */
    fun clearAllData() {
        main.post {
            try { CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush() } catch (_: Throwable) {}
            try { android.webkit.WebStorage.getInstance().deleteAllData() } catch (_: Throwable) {}
            if (profilesSupported()) {
                for (k in listOf("NovaBot", "Incognito")) {
                    try {
                        val pr = ProfileStore.getInstance().getOrCreateProfile(k)
                        pr.cookieManager.removeAllCookies(null); pr.cookieManager.flush(); pr.webStorage.deleteAllData()
                    } catch (_: Throwable) {}
                }
            }
            for (t in tabs) try { t.web.clearCache(true); t.web.clearHistory() } catch (_: Throwable) {}
            clearHistory()
            PasswordStore.clearAll(app)
        }
    }

    // ------------------------------------------------------------------ downloads
    fun downloadDir(): File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "NOVA-Downloads")

    fun startDownload(url: String, ua: String?, cd: String?, mime: String?, referer: String, from: WebView? = null) {
        if (url.startsWith("blob:") || url.startsWith("data:")) { saveInline(url, cd, mime, from); return }
        if (DownloadVault.isYouTubeUrl(url)) {
            toast("YouTube downloads are unsupported in this app.")
            return
        }
        val low = url.lowercase()
        if (low.contains(".m3u8") || low.contains(".mp4") || low.contains(".webm")) {
            val title = URLUtil.guessFileName(url, cd, mime)
            DownloadVault.enqueue(app, url, title)
            toast("Added to Downloads Manager: $title")
            return
        }
        try {
            val name = URLUtil.guessFileName(url, cd, mime)
            val req = DownloadManager.Request(Uri.parse(url))
            if (!mime.isNullOrEmpty()) req.setMimeType(mime)
            val ck = try { cookies(findTab(from)?.incognito == true).getCookie(url) } catch (_: Throwable) { null }
            if (!ck.isNullOrEmpty()) req.addRequestHeader("Cookie", ck)
            req.addRequestHeader("User-Agent", ua ?: baseUa())
            if (referer.isNotEmpty()) req.addRequestHeader("Referer", referer)
            req.setTitle(name)
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            try { req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "NOVA-Downloads/$name") }
            catch (_: Exception) { req.setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, name) }
            val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val id = dm.enqueue(req)
            downloads.add(Dl(name, url, id, "Downloading"))
            WallBus.download("start: $name")
            Memory.log("download", "started: $name <- ${url.take(120)}")
            toast("Download started: $name")
        } catch (e: Exception) {
            WallBus.download("fail: " + (e.message ?: "error"))
            toast("Download failed: " + (e.message ?: ""))
        }
    }

    @Volatile private var blobTok = ""
    @Volatile private var blobName = ""

    private fun safeName(n: String): String {
        val c = n.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trimStart('.')
        return c.ifEmpty { "download.bin" }.take(120)
    }

    private fun uniqueFile(name: String): File {
        val dir = downloadDir(); dir.mkdirs()
        var f = File(dir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (f.exists() && i < 500) { f = File(dir, base + "-" + i + ext); i++ }
        return f
    }

    fun saveBytes(name: String, bytes: ByteArray): String {
        return try {
            val f = uniqueFile(safeName(name))
            f.writeBytes(bytes)
            try { MediaScannerConnection.scanFile(app, arrayOf(f.absolutePath), null, null) } catch (_: Throwable) {}
            downloads.add(Dl(f.name, "inline", -1L, "Done (Download/NOVA-Downloads)"))
            WallBus.fileEvent(f.name, f.length(), true); WallBus.download("done: " + f.name)
            Memory.log("download", "done: ${f.name}")
            toast("Downloaded: " + f.name)
            try { RobotPet.say(app, "Sir, download completed: " + f.name.take(40), 6000L) } catch (_: Throwable) {}
            "saved " + f.path
        } catch (e: Exception) {
            WallBus.download("fail: " + (e.message ?: "error")); toast("Download failed: " + (e.message ?: ""))
            "fail: " + (e.message ?: "error")
        }
    }

    private fun saveInline(url: String, cd: String?, mime: String?, from: WebView?) {
        val guess = URLUtil.guessFileName(url, cd, mime)
        if (url.startsWith("data:")) {
            Thread {
                try {
                    val comma = url.indexOf(',')
                    val head = url.substring(5, if (comma > 0) comma else 5)
                    val body = if (comma > 0) url.substring(comma + 1) else ""
                    val bytes = if (head.contains(";base64")) Base64.decode(body, Base64.DEFAULT) else Uri.decode(body).toByteArray()
                    saveBytes(guess, bytes)
                } catch (e: Exception) { toast("Download failed: " + (e.message ?: "")) }
            }.start()
            return
        }
        val w = from ?: active?.web ?: return
        blobTok = java.util.UUID.randomUUID().toString()
        blobName = guess
        toast("Download started: $guess")
        val js = "(function(u,tok,mt){var x=new XMLHttpRequest();x.open('GET',u);x.responseType='blob';" +
            "x.onload=function(){var b=x.response;if(!b||b.size>62914560){NovaDl.fail(tok,'file too large or empty');return;}" +
            "var r=new FileReader();r.onloadend=function(){var s=String(r.result||'');var i=s.indexOf(',');NovaDl.save(tok,mt||b.type||'',s.substring(i+1));};r.readAsDataURL(b);};" +
            "x.onerror=function(){NovaDl.fail(tok,'blob read failed');};x.send();})(" +
            JSONObject.quote(url) + "," + JSONObject.quote(blobTok) + "," + JSONObject.quote(mime ?: "") + ")"
        main.post { try { w.evaluateJavascript(js, null) } catch (_: Exception) {} }
    }

    class DlBridge(private val owner: WebView) {
        @JavascriptInterface fun save(tok: String, mime: String, b64: String) {
            if (tok.isEmpty() || tok != blobTok) return
            blobTok = ""
            Thread {
                try { saveBytes(blobName, Base64.decode(b64, Base64.DEFAULT)) } catch (e: Exception) { toast("Download failed: " + (e.message ?: "")) }
            }.start()
        }
        @JavascriptInterface fun fail(tok: String, why: String) {
            if (tok.isEmpty() || tok != blobTok) return
            blobTok = ""; toast("Download failed: $why"); WallBus.download("fail: $why")
        }
        @JavascriptInterface fun onMedia(url: String) {
            main.post { recordSniffed(findTab(owner), url) }
        }
        @JavascriptInterface fun onCreds(host: String, user: String, pass: String) {
            if (host.isBlank() || pass.isBlank()) return
            main.post {
                val t = findTab(owner)
                if (t?.incognito == true) return@post
                onCredsCaptured?.invoke(host, user, pass)
            }
        }
    }

    private val dlRecv = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val id = i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            val d = downloads.firstOrNull { it.id == id } ?: return
            try {
                val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.query(DownloadManager.Query().setFilterById(id))?.use { cur ->
                    if (cur.moveToFirst()) {
                        val st = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        if (st == DownloadManager.STATUS_SUCCESSFUL) {
                            val size = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                            d.status = "Done (Download/NOVA-Downloads)"
                            WallBus.fileEvent(d.name, size, true); WallBus.download("done: " + d.name)
                            Memory.log("download", "done: ${d.name}")
                            try { RobotPet.say(app, "Sir, download completed: " + d.name.take(40), 6000L) } catch (_: Throwable) {}
                        } else { d.status = "Failed"; WallBus.download("fail: " + d.name); Memory.log("download", "fail: ${d.name}") }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun scheduleAutoDownload(t: Tab) {
        for (delay in longArrayOf(1200L, 3500L, 7000L)) {
            main.postDelayed({
                if (System.currentTimeMillis() < autoDlUntil) {
                    try {
                        t.web.evaluateJavascript(JS_DL) { r ->
                            val v = decode(r)
                            if (v.startsWith("clicked") || v.startsWith("form")) autoDlUntil = 0L
                        }
                    } catch (_: Exception) {}
                }
            }, delay)
        }
    }

    fun download(rawUrl: String): String {
        if (rawUrl.isBlank()) return "Provide a download URL"
        if (DownloadVault.isYouTubeUrl(rawUrl)) return "YouTube downloads are unsupported in this app."
        val url = normalizeDl(normalize(rawUrl))
        autoDlUntil = System.currentTimeMillis() + 45_000L
        val dl = ui { val t = newTab(url, false); main.postDelayed({ if (active !== t && tabs.contains(t)) closeTab(t) }, 150_000L); t }
        if (dl == null) return "Could not open download tab"
        Thread.sleep(6000)
        val d = downloads.lastOrNull()
        return if (d != null && System.currentTimeMillis() - 12000 < autoDlUntil) "Download started: ${d.name}. Folder: ${downloadDir().path}"
        else "Page opened for download. Folder: ${downloadDir().path}"
    }

    // ------------------------------------------------------------------ Nova tools
    fun tool(ctx: Context, name: String, a: JSONObject): String {
        init(ctx)
        lastUse = System.currentTimeMillis()
        return try {
            when (name) {
                "browser_open" -> open(a.optString("url"), a.optBoolean("new_tab"))
                "browser_read" -> readPage(a.optInt("max_chars", 3500))
                "browser_click" -> click(a.optString("target"), a.optBoolean("real"))
                "browser_type" -> typeText(a.optString("target"), a.optString("text"), a.optBoolean("submit"))
                "browser_scroll" -> scroll(a.optString("direction", "down"))
                "browser_nav" -> nav(a.optString("action"))
                "browser_media" -> media(a.optString("action"), a.optDouble("value", 0.0))
                "browser_download" -> download(a.optString("url"))
                "browser_upload" -> upload(ctx, a.optString("file"))
                "browser_tabs" -> tabsCmd(a.optString("action", "list"), a.optInt("index", -1))
                "browser_window" -> window(a.optString("mode"))
                "browser_look" -> look()
                "browser_status" -> status()
                "browser_profile" -> if (a.optString("name").isBlank()) ("Active: " + profileLabel()) else setProfile(a.optString("name"))
                else -> "unknown browser tool: $name"
            }
        } catch (e: Exception) { "browser error: " + (e.message ?: e.javaClass.simpleName) }
    }

    fun waitLoaded(t: Tab, ms: Long) {
        val end = System.currentTimeMillis() + ms
        Thread.sleep(250)
        while (System.currentTimeMillis() < end && t.loading) Thread.sleep(200)
    }

    fun open(url: String, inNewTab: Boolean): String {
        if (!ensureHost()) return "ERR: Enable 'Display over other apps' permission for NOVA Browser"
        val u = normalize(url)
        val t = ui { loadSmart(u, inNewTab) } ?: return "ERR: tab not created"
        waitLoaded(t, 20_000L)
        return "Opened: " + describe() + (if (t.error.isNotEmpty()) " (error: ${t.error})" else "")
    }

    private val JS_READ = """
(function(maxc){
 var els=[];
 var nodes=document.querySelectorAll('a[href],button,input,textarea,select,[role=button],[role=link],summary,[contenteditable=true]');
 for(var i=0;i<nodes.length&&els.length<60;i++){
  var e=nodes[i],r=e.getBoundingClientRect();
  if(r.width<4||r.height<4) continue;
  var st=getComputedStyle(e); if(st.visibility==='hidden'||st.display==='none') continue;
  var label=(e.innerText||e.value||e.getAttribute('aria-label')||e.placeholder||e.title||e.alt||'').replace(/\s+/g,' ').trim().slice(0,60);
  var id=e.getAttribute('data-nv');
  if(!id){window.__nvN=(window.__nvN||0)+1;id=String(window.__nvN);e.setAttribute('data-nv',id);}
  els.push('['+id+'] '+e.tagName.toLowerCase()+(e.type?'/'+e.type:'')+' '+label);
 }
 var txt=(document.body?document.body.innerText:'').replace(/\n{3,}/g,'\n\n');
 return JSON.stringify({title:document.title,url:location.href,text:txt.slice(0,maxc),els:els});
})(MAXC)"""

    fun readPage(maxChars: Int): String {
        val t = active ?: return "No page open in browser"
        val raw = evalSync(JS_READ.replace("MAXC", maxChars.coerceIn(500, 12000).toString()), 8000, t)
        if (raw.isEmpty()) return "Page still loading or unreadable: " + describe()
        return try {
            val o = JSONObject(raw)
            val els = o.optJSONArray("els") ?: JSONArray()
            val sb = StringBuilder()
            sb.append("TITLE: ").append(o.optString("title")).append("\nURL: ").append(o.optString("url")).append("\n\nTEXT:\n").append(o.optString("text"))
            sb.append("\n\nCLICKABLE/INPUT:\n")
            for (i in 0 until els.length()) sb.append(els.getString(i)).append('\n')
            sb.toString()
        } catch (_: Exception) { raw.take(maxChars) }
    }

    private val JS_CLICK = """
(function(t,mode,vw){
 var el=null;
 if(/^#?\d+$/.test(t)) el=document.querySelector('[data-nv="'+t.replace('#','')+'"]');
 if(!el){
  var tl=t.toLowerCase(),c=document.querySelectorAll('a,button,input,[role=button],[role=link],[role=menuitem],[role=tab],summary,label,li,span,div,p,h1,h2,h3,td'),bs=0,bl=1e9;
  for(var i=0;i<c.length;i++){
   var e=c[i],r=e.getBoundingClientRect();
   if(r.width<3||r.height<3) continue;
   var x=(e.innerText||e.value||e.getAttribute('aria-label')||e.title||e.alt||'').replace(/\s+/g,' ').trim().toLowerCase();
   if(!x) continue;
   var s=0;
   if(x===tl) s=100; else if(x.indexOf(tl)===0) s=60; else if(x.indexOf(tl)>=0) s=30; else continue;
   var tg=e.tagName; if(tg==='A'||tg==='BUTTON'||tg==='INPUT'||e.getAttribute('role')) s+=15;
   if(s>bs||(s===bs&&x.length<bl)){bs=s;bl=x.length;el=e;}
  }
 }
 if(!el) return JSON.stringify({ok:false});
 try{el.scrollIntoView({block:'center',inline:'center'});}catch(e0){}
 var r2=el.getBoundingClientRect();
 var lab=(el.innerText||el.value||el.getAttribute('aria-label')||el.tagName||'').toString().replace(/\s+/g,' ').trim().slice(0,50);
 if(mode==='rect'){var sc=window.innerWidth>0?vw/window.innerWidth:1;return JSON.stringify({ok:true,label:lab,x:(r2.left+r2.width/2)*sc,y:(r2.top+r2.height/2)*sc});}
 ['pointerdown','mousedown','pointerup','mouseup','click'].forEach(function(n){try{el.dispatchEvent(new MouseEvent(n,{bubbles:true,cancelable:true,view:window}));}catch(e1){}});
 return JSON.stringify({ok:true,label:lab});
})(TARGET,MODE,VW)"""

    private fun realTap(w: WebView, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        val d = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
        w.dispatchTouchEvent(d); d.recycle()
        main.postDelayed({
            val t2 = SystemClock.uptimeMillis()
            val u = MotionEvent.obtain(t, t2, MotionEvent.ACTION_UP, x, y, 0)
            try { w.dispatchTouchEvent(u) } catch (_: Exception) {}
            u.recycle()
        }, 70L)
    }

    fun click(target: String, real: Boolean): String {
        val t = active ?: return "No page open"
        if (target.isBlank()) return "Provide click target"
        val needReal = real || presetFiles.isNotEmpty()
        val vw = ui { t.web.width } ?: 0
        val js = JS_CLICK.replace("TARGET", JSONObject.quote(target)).replace("MODE", if (needReal) "'rect'" else "'js'").replace("VW", vw.toString())
        val raw = evalSync(js, 6000, t)
        val o = try { JSONObject(raw) } catch (_: Exception) { return "click failed" }
        if (!o.optBoolean("ok")) return "'$target' not found on page"
        if (needReal) {
            val x = o.optDouble("x").toFloat(); val y = o.optDouble("y").toFloat()
            ui { realTap(t.web, x, y) }
        }
        waitLoaded(t, 6000L)
        return "clicked: " + o.optString("label") + " -> " + describe()
    }

    private val JS_TYPE = """
(function(t,val,sub){
 var el=null;
 if(/^#?\d+$/.test(t)) el=document.querySelector('[data-nv="'+t.replace('#','')+'"]');
 if(!el&&t){
  var tl=t.toLowerCase(),c=document.querySelectorAll('input,textarea,[contenteditable=true],[role=textbox],[role=searchbox]');
  for(var i=0;i<c.length;i++){var e=c[i];var lab=((e.placeholder||'')+' '+(e.getAttribute('aria-label')||'')+' '+(e.name||'')+' '+(e.id||'')+' '+(e.type||'')).toLowerCase();
   if(lab.indexOf(tl)>=0){var r=e.getBoundingClientRect();if(r.width>3){el=e;break;}}}
 }
 if(!el){
  el=document.activeElement;
  if(!el||el===document.body||!(el.tagName==='INPUT'||el.tagName==='TEXTAREA'||el.isContentEditable)){
   el=null;
   var f=document.querySelectorAll('input:not([type=hidden]):not([type=checkbox]):not([type=radio]):not([type=submit]):not([type=file]),textarea');
   for(var j=0;j<f.length;j++){var r2=f[j].getBoundingClientRect();if(r2.width>3&&r2.height>3){el=f[j];break;}}
  }
 }
 if(!el) return 'no input found';
 try{el.scrollIntoView({block:'center'});}catch(e0){}
 el.focus();
 if(el.isContentEditable){el.textContent=val;}
 else{var proto=el.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;var d=Object.getOwnPropertyDescriptor(proto,'value');if(d&&d.set)d.set.call(el,val);else el.value=val;}
 el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));
 if(sub){
  ['keydown','keypress','keyup'].forEach(function(n){el.dispatchEvent(new KeyboardEvent(n,{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));});
  var fm=el.form;if(fm){try{if(fm.requestSubmit)fm.requestSubmit();else fm.submit();}catch(e2){}}
 }
 return 'typed';
})(TARGET,VALUE,SUBMIT)"""

    fun typeText(target: String, text: String, submit: Boolean): String {
        val t = active ?: return "No page open"
        val js = JS_TYPE.replace("TARGET", JSONObject.quote(target)).replace("VALUE", JSONObject.quote(text)).replace("SUBMIT", if (submit) "true" else "false")
        val r = evalSync(js, 6000, t)
        if (submit) waitLoaded(t, 8000L)
        return if (r == "typed") "typed" + (if (submit) " and submitted" else "") else (if (r.isEmpty()) "type failed" else r)
    }

    fun scroll(dir: String): String {
        val t = active ?: return "No page open"
        val k = if (dir.lowercase() == "up") -1 else 1
        evalSync("(function(){window.scrollBy(0,$k*Math.round(window.innerHeight*0.8));return 'ok'})()", 3000, t)
        return "scrolled"
    }

    fun nav(action: String): String {
        val t = active ?: return "No page open"
        ui {
            when (action.lowercase()) {
                "back" -> if (t.web.canGoBack()) t.web.goBack()
                "forward" -> if (t.web.canGoForward()) t.web.goForward()
                "reload", "refresh" -> if (t.url == BrowserScripts.HOME_URL) loadIntoTab(t, BrowserScripts.HOME_URL) else t.web.reload()
                "stop" -> t.web.stopLoading()
                "home" -> loadIntoTab(t, home())
            }
        }
        if (action.lowercase() != "stop") waitLoaded(t, 12_000L)
        return describe()
    }

    fun media(action: String, value: Double): String {
        val t = active ?: return "No active video/audio"
        val a = action.lowercase()
        if (a == "next") return youtubeStep(1)
        if (a == "prev" || a == "previous") return youtubeStep(-1)
        val body = when (a) {
            "play" -> { mediaWanted = true; "v.play();" }
            "pause" -> { mediaWanted = false; "v.pause();" }
            "toggle" -> "if(v.paused){v.play();}else{v.pause();}"
            "mute" -> "v.muted=true;"
            "unmute" -> "v.muted=false;"
            "volume" -> "v.muted=false;v.volume=${(value / 100.0).coerceIn(0.0, 1.0)};"
            "seek" -> "v.currentTime=Math.max(0,v.currentTime+(${value}));"
            "restart" -> "v.currentTime=0;v.play();"
            "status" -> ""
            else -> return "action: play, pause, toggle, mute, unmute, volume, seek, next, prev, restart, status"
        }
        val js = "(function(){var v=document.querySelector('video,audio');if(!v)return 'no media';" + body +
            "return (v.paused?'paused':'playing')+' '+Math.round(v.currentTime)+'/'+Math.round(v.duration||0)+'s vol='+Math.round(v.volume*100)+(v.muted?' (mute)':'')+' | '+document.title;})()"
        return evalSync(js, 4000, t).ifEmpty { "no media response" }
    }

    fun upload(ctx: Context, file: String): String {
        val files = file.split("|", ";").map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { NFiles.resolve(ctx, it) }
        if (files.isEmpty()) return "file not found: $file"
        presetFiles = files
        return "Prepared upload files: " + files.joinToString { it.name }
    }

    fun tabsCmd(action: String, index: Int): String {
        when (action.lowercase()) {
            "new" -> { ensureHost(); ui { newTab(home()) }; return "new tab opened" }
            "switch" -> { val t = tabs.getOrNull(index) ?: return "tab not found"; ui { setTabActive(t) }; return "switched: " + describe() }
            "close" -> { val t = tabs.getOrNull(index) ?: active ?: return "no tab"; ui { closeTab(t) }; return "tab closed" }
            "close_all" -> { ui { closeAllTabs() }; return "all tabs closed" }
        }
        if (tabs.isEmpty()) return "no open tabs"
        return tabs.mapIndexed { i, t -> "[$i]" + (if (t === active) "*" else "") + " " + t.title.take(40) + " - " + t.url.take(80) }.joinToString("\n")
    }

    private fun capture(maxW: Int): Bitmap? {
        val w = active?.web ?: return null
        if (w.width <= 0 || w.height <= 0) return null
        val bm = Bitmap.createBitmap(w.width, w.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bm); c.drawColor(Color.WHITE)
        w.draw(c)
        if (bm.width <= maxW) return bm
        val h = (bm.height * (maxW.toFloat() / bm.width)).toInt()
        return Bitmap.createScaledBitmap(bm, maxW, h, true)
    }

    fun look(): String {
        val sink = Tools.imageSink ?: return "browser_look requires live voice mode"
        if (active == null) return "No page open"
        val bm = ui { capture(800) } ?: return "screenshot failed"
        sink(Media.jpeg(bm, 800)); Thread.sleep(800)
        return "browser screenshot sent"
    }

    fun status(): String {
        val sb = StringBuilder()
        sb.append("Window: ").append(host.name).append(" | tabs: ").append(tabs.size).append(" | ").append(profileLabel()).append('\n')
        sb.append("Active: ").append(describe()).append('\n')
        if (active != null) sb.append("Media: ").append(media("status", 0.0)).append('\n')
        sb.append("Downloads folder: ").append(downloadDir().path).append('\n')
        for (d in downloads.toList().takeLast(6)) sb.append(" - ").append(d.name).append(": ").append(d.status).append('\n')
        return sb.toString().trim()
    }

    private val JS_YT_LIST = """
(function(){
 try{
  var out=[],seen={},d=window.ytInitialData;
  function title(v){var t=v.title||v.headline;if(!t)return '';if(t.simpleText)return t.simpleText;if(t.runs)return t.runs.map(function(r){return r.text}).join('');if(t.accessibility&&t.accessibility.accessibilityData)return t.accessibility.accessibilityData.label||'';return '';}
  function walk(o,ad,dep){
   if(!o||typeof o!=='object'||dep>40)return;
   if(Array.isArray(o)){for(var i=0;i<o.length;i++)walk(o[i],ad,dep+1);return;}
   for(var k in o){
    var isAd=ad||/(^ad[A-Z])|([a-z]Ad[A-Z])|[Pp]romoted|sparkles/.test(k);
    var v=o[k];
    if(!isAd&&(k==='videoRenderer'||k==='compactVideoRenderer'||k==='videoWithContextRenderer')&&v&&typeof v.videoId==='string'){
     if(!seen[v.videoId]){seen[v.videoId]=1;out.push({id:v.videoId,title:title(v)});}
    } else walk(v,isAd,dep+1);
   }
  }
  if(d)walk(d,false,0);
  if(!out.length){
   var a=document.querySelectorAll('a[href*="/watch?v="]');
   for(var j=0;j<a.length;j++){var m=/[?&]v=([\w-]{11})/.exec(a[j].getAttribute('href'));
    if(m&&!seen[m[1]]){seen[m[1]]=1;out.push({id:m[1],title:(a[j].getAttribute('aria-label')||a[j].innerText||'').trim().slice(0,120)});}}
  }
  return JSON.stringify(out.slice(0,15));
 }catch(e){return '[]';}
})()"""

    private val JS_PLAY = """
(function(){
 var v=document.querySelector('video');
 if(!v){var b0=document.querySelector('.ytp-large-play-button,.ytm-large-play-button');if(b0)b0.click();return 'novideo';}
 try{v.muted=false;var p=v.play();if(p&&p.catch)p.catch(function(){});}catch(e){}
 if(v.paused){var b=document.querySelector('.ytp-large-play-button,.ytm-large-play-button,button[aria-label="Play"]');if(b)b.click();}
 return v.paused?'paused':'playing';
})()"""

    private val JS_BG = """
(function(){
 if(window.__nvBg)return; window.__nvBg=1;
 try{
  Object.defineProperty(document,'hidden',{get:function(){return false;},configurable:true});
  Object.defineProperty(document,'webkitHidden',{get:function(){return false;},configurable:true});
  Object.defineProperty(document,'visibilityState',{get:function(){return 'visible';},configurable:true});
  Object.defineProperty(document,'webkitVisibilityState',{get:function(){return 'visible';},configurable:true});
 }catch(e){}
 ['visibilitychange','webkitvisibilitychange','blur','pagehide'].forEach(function(n){
  window.addEventListener(n,function(e){e.stopImmediatePropagation();},true);
  document.addEventListener(n,function(e){e.stopImmediatePropagation();},true);
 });
})()"""

    private val JS_AD_SKIP = """
(function(){
 if(window.__nvAd)return;
 window.__nvAd=setInterval(function(){
  var b=document.querySelector('.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button,button[class*="skip-button"]');
  if(b)b.click();
  var c=document.querySelector('.ytp-ad-overlay-close-button');if(c)c.click();
 },800);
})()"""

    private val JS_DL = """
(function(){
 var f=document.getElementById('download-form');
 if(f){try{f.submit();return 'form';}catch(e){}}
 var b=document.getElementById('downloadButton')||document.querySelector('a.input.popsok')||document.querySelector('a[aria-label^="Download"]');
 if(b){var h=b.getAttribute('href');if(h&&h.indexOf('http')===0){location.href=h;return 'clicked-link';}b.click();return 'clicked';}
 var all=document.querySelectorAll('a,button'),i;
 for(i=0;i<all.length;i++){var x=(all[i].innerText||'').trim();if(/^download( anyway| file)?$/i.test(x)){all[i].click();return 'clicked-text';}}
 return 'none';
})()"""

    private fun parseYt(raw: String): List<Pair<String, String>> {
        if (raw.isEmpty()) return emptyList()
        return try {
            val a = JSONArray(raw); val out = ArrayList<Pair<String, String>>()
            for (i in 0 until a.length()) { val o = a.getJSONObject(i); out.add(o.optString("id") to o.optString("title")) }
            out
        } catch (_: Exception) { emptyList() }
    }

    private fun bestIndex(q: String, list: List<Pair<String, String>>): Int {
        val words = q.lowercase().split(Regex("[^a-z0-9؀-ۿ]+")).filter { it.length >= 2 && it != "song" && it != "gana" && it != "play" }
        if (words.isEmpty()) return 0
        var best = 0; var bs = -1
        for ((i, p) in list.withIndex()) {
            val t = p.second.lowercase()
            val s = words.count { t.contains(it) }
            if (s > bs) { bs = s; best = i }
        }
        return best
    }

    private fun ytTabFor(): Tab {
        val t = ytTab
        if (t != null && tabs.contains(t)) { if (active !== t) setTabActive(t); return t }
        val n = newTab(null, true); ytTab = n; return n
    }

    fun youtubePlay(ctx: Context, query: String): String {
        init(ctx)
        lastUse = System.currentTimeMillis()
        val q = query.trim()
        if (q.isEmpty()) return "ERR: provide video/song name"
        if (!ensureHost()) return "ERR: Enable Display over other apps"
        val tab = ui { ytTabFor() } ?: return "ERR: tab not created"
        ui { tab.loading = true; tab.web.loadUrl("https://m.youtube.com/results?search_query=" + URLEncoder.encode(q, "UTF-8") + "&sp=EgIQAQ%253D%253D") }
        waitLoaded(tab, 20_000L)
        var list: List<Pair<String, String>> = emptyList()
        for (i in 0 until 14) {
            list = parseYt(evalSync(JS_YT_LIST, 6000, tab))
            if (list.isNotEmpty()) break
            Thread.sleep(700)
        }
        if (list.isEmpty()) return "ERR: No YouTube results found"
        ytResults = list
        ytIdx = bestIndex(q, list)
        return playIndex(tab, ytIdx)
    }

    private fun playIndex(tab: Tab, i: Int): String {
        val (id, title) = ytResults[i]
        ui { tab.loading = true; tab.web.loadUrl("https://m.youtube.com/watch?v=$id") }
        waitLoaded(tab, 20_000L)
        mediaWanted = true
        var state = ""
        for (k in 0 until 12) {
            state = evalSync(JS_PLAY, 4000, tab)
            if (state == "playing") break
            Thread.sleep(800)
        }
        return if (state == "playing") "Playing on YouTube inside NOVA browser: $title"
        else "Opened on YouTube: $title (state=$state)"
    }

    private fun youtubeStep(delta: Int): String {
        if (ytResults.isEmpty()) return "Play a video first with youtube_play"
        val tab = ui { ytTabFor() } ?: return "no tab"
        ytIdx = (ytIdx + delta + ytResults.size) % ytResults.size
        return playIndex(tab, ytIdx)
    }

    private val duckListener: (Boolean) -> Unit = { speaking -> duck(speaking) }
    private fun duck(speaking: Boolean) {
        if (!mediaWanted || host == Host.NONE) return
        val v = if (speaking) "0.2" else "1.0"
        evalAsync("(function(){document.querySelectorAll('video,audio').forEach(function(m){m.volume=$v;});})()")
    }

    private val mirrorRun = object : Runnable {
        override fun run() {
            try { if (BrowserMirror.wanted && host != Host.NONE) captureMirror() } catch (_: Throwable) {}
            main.postDelayed(this, 700L)
        }
    }

    private fun captureMirror() {
        val t = active ?: return
        val w = t.web
        if (w.width <= 0 || w.height <= 0 || w.parent == null) return
        val tw = 360
        val th = (tw.toFloat() * w.height / w.width).toInt().coerceIn(200, 800)
        var bm = BrowserMirror.frame
        if (bm == null || bm.width != tw || bm.height != th) { bm = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888); BrowserMirror.frame = bm }
        val c = Canvas(bm!!)
        c.drawColor(Color.WHITE)
        val sc = tw.toFloat() / w.width
        c.scale(sc, sc)
        w.draw(c)
        BrowserMirror.frameAt = System.currentTimeMillis()
        BrowserMirror.label = (t.title.ifEmpty { t.url }).take(40)
    }

    private var idleChecks = 0
    private val idleRun = object : Runnable {
        override fun run() {
            try {
                if (host == Host.HIDDEN) {
                    val idle = System.currentTimeMillis() - lastUse > 180_000L
                    if (!mediaWanted && idle) { backgroundHost() }
                    else if (mediaWanted && idle) {
                        evalSync("0", 100)
                        active?.web?.evaluateJavascript("(function(){var v=document.querySelector('video,audio');return !!(v&&!v.paused&&!v.ended);})()") { r ->
                            if (r == "true") idleChecks = 0 else { idleChecks++; if (idleChecks >= 6) { idleChecks = 0; mediaWanted = false; backgroundHost() } }
                        }
                    }
                }
            } catch (_: Throwable) {}
            main.postDelayed(this, 20_000L)
        }
    }
}

object NFiles {
    fun resolve(ctx: Context, name: String): File? {
        val direct = File(name)
        if (direct.isAbsolute && direct.exists()) return direct
        val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val roots = ArrayList<File>()
        try { Backup.dir()?.let { roots.add(it) } } catch (_: Throwable) {}
        roots.add(File(dl, "NOVA-Downloads")); roots.add(dl); roots.add(File(dl, "NOVA"))
        roots.add(File(Environment.getExternalStorageDirectory(), "NOVA"))
        ctx.getExternalFilesDir(null)?.let { roots.add(it) }
        for (r in roots) { val f = File(r, name); if (f.exists()) return f }
        val low = name.lowercase()
        for (r in roots) {
            val m = r.listFiles()?.filter { it.name.lowercase().contains(low) }?.maxByOrNull { it.lastModified() }
            if (m != null) return m
        }
        return null
    }
}
