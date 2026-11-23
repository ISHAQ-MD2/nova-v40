package com.nova.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class NovaAccessibilityService : AccessibilityService() {
    companion object { @Volatile var instance: NovaAccessibilityService? = null }

    private var presentRx: android.content.BroadcastReceiver? = null
    override fun onServiceConnected() {
        instance = this
        try {
            presentRx = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context, i: Intent) {
                    // phone unlock hua: ruke hue kaam ko 2.5 second baad shuru karo
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ try { PendingTasks.runNext(applicationContext) } catch (_: Exception) {} }, 2500)
                }
            }
            registerReceiver(presentRx, android.content.IntentFilter(Intent.ACTION_USER_PRESENT))
        } catch (_: Exception) {}
    }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; try { presentRx?.let { unregisterReceiver(it) } } catch (_: Exception) {}; return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; super.onDestroy() }
    private var lastAdCheck = 0L
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null) return
        val t = e.eventType
        if (t != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && t != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val now = System.currentTimeMillis()
        if (now - lastAdCheck < 1200) return
        lastAdCheck = now
        if (!getSharedPreferences("nova", MODE_PRIVATE).getBoolean("adskip", true)) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.termux" || pkg.startsWith("com.android.systemui")) return
        try { skipAd(pkg) } catch (_: Exception) {}
    }

    /** Ad par "Skip" / "Skip ad" / "Close ad" button mile to dabao. Sirf "Skip" akela sirf YouTube mein (baaki apps mein galti se na dabe). */
    fun skipAd(pkg: String = ""): Boolean {
        val root = rootInActiveWindow ?: return false
        val yt = pkg.startsWith("com.google.android.youtube") || (root.packageName?.toString() ?: "").startsWith("com.google.android.youtube")
        var done = false
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || done) return
            val t = n.text?.toString()?.trim()?.lowercase(); val d = n.contentDescription?.toString()?.trim()?.lowercase()
            val id = n.viewIdResourceName ?: ""
            val hit = id.endsWith("skip_ad_button") || listOf(t, d).any { s ->
                s != null && s.length <= 24 && (s.startsWith("skip ad") || s == "close ad" || s == "ad close" || s == "close ads" || (yt && s == "skip")) }
            if (hit && n.isVisibleToUser) {
                var x: AccessibilityNodeInfo? = n
                while (x != null) { if (x.isClickable && x.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { done = true; return }; x = x.parent }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return done
    }

    /** (x,y) par ungli dabaye rakho ms millisecond (fire/record button jaisa). */
    fun holdAt(x: Float, y: Float, ms: Long): Boolean {
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, 0, ms.coerceIn(50, 60_000))).build()
        val latch = CountDownLatch(1); val ok = AtomicBoolean(false)
        if (!dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { ok.set(true); latch.countDown() }
            override fun onCancelled(d: GestureDescription?) { latch.countDown() }
        }, null)) return false
        latch.await(ms + 4000, TimeUnit.MILLISECONDS)
        return ok.get()
    }

    @Volatile private var holdDone: CountDownLatch? = null
    @Volatile private var holdOk = false
    @Volatile private var micX = 0f
    @Volatile private var micY = 0f

    /** Mic button dabao aur pakde rakho (background mein) - swipe nahi. Wait ke liye holdWait(). */
    fun holdStart(labels: List<String>, ms: Long): Boolean {
        val r = findMic(labels) ?: return false
        micX = r.centerX().toFloat(); micY = r.centerY().toFloat()
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(micX, micY) }, 0, ms.coerceIn(500, 59_000))).build()
        val l = CountDownLatch(1); holdDone = l; holdOk = false
        return dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { holdOk = true; l.countDown() }
            override fun onCancelled(d: GestureDescription?) { l.countDown() }
        }, null)
    }
    fun holdWait(ms: Long): Boolean { try { holdDone?.await(ms, TimeUnit.MILLISECONDS) } catch (_: Exception) {}; return holdOk }
    /** Naya chhota tap purani pakad ko cancel kar deta hai (jab record shuru hi na hui ho). */
    fun cancelHold() { tap(micX, micY) }

    override fun onInterrupt() {}

    fun back() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun recents() = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun notifications() = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    fun lock() = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
    fun screenshot() = performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)

    private fun gesture(path: Path, dur: Long): Boolean {
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, dur.coerceAtLeast(1))).build()
        val latch = CountDownLatch(1); val ok = AtomicBoolean(false)
        val sent = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { ok.set(true); latch.countDown() }
            override fun onCancelled(d: GestureDescription?) { latch.countDown() }
        }, null)
        if (!sent) return false
        latch.await(4, TimeUnit.SECONDS)
        return ok.get()
    }

    /** Button ko ms tak dabaye rakho (WhatsApp mic: dabao-bolo-chhodo = voice message bhej do). */
    @Volatile var lastHoldFound = false

    /**
     * v30: WhatsApp chat mein AAYI HUI voice message bubble ka "play" button bhi content-description "Voice message" rakhta hai -
     * isi se pehle ye galti se usi par tap kar deta tha (dusre bande ki voice sunne lagta, naya record nahi hota).
     * Fix: compose bar (screen ke neeche ~18%) ke andar wala hi button lena, upar chat list ke bubbles ignore karna.
     */
    private fun findMic(labels: List<String>): Rect? {
        val root = rootInActiveWindow ?: return null
        val dm = resources.displayMetrics
        val bottomZone = dm.heightPixels * 0.80f
        var found: AccessibilityNodeInfo? = null
        var edit: AccessibilityNodeInfo? = null
        val r0 = Rect()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || found != null) return
            val t = n.text?.toString()?.trim(); val d = n.contentDescription?.toString()?.trim()
            if (n.isEditable && edit == null) edit = n
            if (n.isVisibleToUser && labels.any { l ->
                    (t != null && t.length < 40 && t.contains(l, true)) || (d != null && d.length < 40 && d.contains(l, true)) }) {
                n.getBoundsInScreen(r0)
                if (r0.top >= bottomZone) { found = n; return }   // sirf neeche ka compose-bar button, chat bubble nahi
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        val r = Rect()
        val n = found
        if (n != null) { n.getBoundsInScreen(r); return if (r.width() > 0) r else null }
        val e = edit ?: return null
        val er = Rect(); e.getBoundsInScreen(er)
        val x = dm.widthPixels - (32 * dm.density).toInt()
        r.set(x - 4, er.centerY() - 4, x + 4, er.centerY() + 4)
        return r
    }

    /** Mic dabao -> thodi der pakdo -> upar swipe karo: WhatsApp recording lock (haath hata sakte ho). */
    fun lockVoiceRecording(labels: List<String>): Boolean {
        val r = findMic(labels) ?: return false
        val x = r.centerX().toFloat(); val y = r.centerY().toFloat()
        val up = 260 * resources.displayMetrics.density
        val s1 = GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, 0, 800, true)
        val l1 = CountDownLatch(1); val ok1 = AtomicBoolean(false)
        if (!dispatchGesture(GestureDescription.Builder().addStroke(s1).build(), object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { ok1.set(true); l1.countDown() }
            override fun onCancelled(d: GestureDescription?) { l1.countDown() }
        }, null)) return false
        l1.await(3, TimeUnit.SECONDS)
        if (!ok1.get()) return false
        val s2 = s1.continueStroke(Path().apply { moveTo(x, y); lineTo(x, y - up) }, 0, 500, false)
        val l2 = CountDownLatch(1); val ok2 = AtomicBoolean(false)
        if (!dispatchGesture(GestureDescription.Builder().addStroke(s2).build(), object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { ok2.set(true); l2.countDown() }
            override fun onCancelled(d: GestureDescription?) { l2.countDown() }
        }, null)) return false
        l2.await(3, TimeUnit.SECONDS)
        return ok2.get()
    }

    /** Chat taiyar hai? (message box ya voice button screen par aa gaya) */
    fun voiceReady(): Boolean {
        val root = rootInActiveWindow ?: return false
        fun any(n: AccessibilityNodeInfo?): Boolean {
            if (n == null) return false
            if (n.isEditable) return true
            for (i in 0 until n.childCount) if (any(n.getChild(i))) return true
            return false
        }
        return any(root)
    }

    fun holdByLabel(labels: List<String>, ms: Long): Boolean {
        lastHoldFound = false
        val root = rootInActiveWindow ?: return false
        var found: AccessibilityNodeInfo? = null
        var edit: AccessibilityNodeInfo? = null
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || found != null) return
            val t = n.text?.toString()?.trim(); val d = n.contentDescription?.toString()?.trim()
            if (n.isEditable && edit == null) edit = n
            if (n.isVisibleToUser && labels.any { l ->
                    (t != null && t.length < 40 && t.contains(l, true)) || (d != null && d.length < 40 && d.contains(l, true)) }) { found = n; return }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        val r = Rect()
        val n = found
        if (n != null) n.getBoundsInScreen(r)
        else {
            // label nahi mila: message box ke seedhe haath par mic ka gol button hota hai
            val e = edit ?: return false
            val er = Rect(); e.getBoundsInScreen(er)
            val dm = resources.displayMetrics
            val x = dm.widthPixels - (32 * dm.density).toInt()
            r.set(x - 4, er.centerY() - 4, x + 4, er.centerY() + 4)
        }
        if (r.width() <= 0) return false
        lastHoldFound = true
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(
            Path().apply { moveTo(r.centerX().toFloat(), r.centerY().toFloat()) }, 0, ms.coerceIn(500, 59_000))).build()
        val latch = CountDownLatch(1); val ok = AtomicBoolean(false)
        if (!dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { ok.set(true); latch.countDown() }
            override fun onCancelled(d: GestureDescription?) { latch.countDown() }
        }, null)) return false
        latch.await(ms + 4000, TimeUnit.MILLISECONDS)
        return ok.get()
    }

    fun tap(x: Float, y: Float): Boolean = gesture(Path().apply { moveTo(x, y) }, 60)
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): Boolean =
        gesture(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, ms)

    /** v30: WhatsApp jaisi list mein row ke TEXT (title) se dhoondh kar poori row click karo - group ki photo/avatar par nahi, chat khulti hai. */
    fun clickRowByText(q: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(q)
        val cand = nodes.filter { it.text != null && it.text.toString().contains(q, true) }
        val list = if (cand.isNotEmpty()) cand else nodes.toList()
        for (n in list) {
            var x: AccessibilityNodeInfo? = n
            while (x != null) { if (x.isClickable && x.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true; x = x.parent }
        }
        for (n in list) { val r = Rect(); n.getBoundsInScreen(r); if (r.width() > 0 && r.height() > 0) return tap(r.centerX().toFloat(), r.centerY().toFloat()) }
        return false
    }

    /** Group/Contact Info screen par khade hain ya chat mein - taake "photo par click, chat nahi khulti" wala bug pakda ja sake. */
    fun looksLikeInfoScreen(): Boolean {
        val t = dumpText(2500).lowercase()
        return listOf("exit group", "group description", "added by", "group settings", "block contact", "report contact", "disappearing messages", "mute notifications").any { t.contains(it) }
    }

    fun clickText(t: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(t)
        for (n in nodes) {
            var x: AccessibilityNodeInfo? = n
            while (x != null) {
                if (x.isClickable && x.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                x = x.parent
            }
        }
        for (n in nodes) {            // clickable parent nahi mila: seedha centre par tap
            val r = Rect(); n.getBoundsInScreen(r)
            if (r.width() > 0 && r.height() > 0) return tap(r.centerX().toFloat(), r.centerY().toFloat())
        }
        return false
    }

    private fun findEditable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.isEditable) return n
        for (i in 0 until n.childCount) { val r = findEditable(n.getChild(i)); if (r != null) return r }
        return null
    }

    fun typeText(s: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable(root) ?: return false
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val b = Bundle()
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, s)
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
    }

    /** Screen ke elements: label [clickable] @x,y  - model isse tap ki jagah dhoondta hai. */
    fun dump(): String {
        val root = rootInActiveWindow ?: return "Screen padh nahi paya (koi window nahi)."
        val sb = StringBuilder("app: ${root.packageName}\n")
        var count = 0
        val r = Rect()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || count >= 90) return
            val label = (n.text ?: n.contentDescription)?.toString()
            if (!label.isNullOrBlank() || n.isClickable) {
                n.getBoundsInScreen(r)
                if (r.width() > 0 && r.height() > 0 && n.isVisibleToUser) {
                    sb.append("- ").append(label?.take(60) ?: "(no label)")
                    if (n.isClickable) sb.append(" [clickable]")
                    sb.append(" @").append(r.centerX()).append(',').append(r.centerY()).append('\n')
                    count++
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return sb.toString()
    }

    /** Exact naam (text ya content-description) wala button dhoondh kar dabata hai, e.g. WhatsApp ka "Send". */
    fun clickExact(labels: List<String>): Boolean {
        val root = rootInActiveWindow ?: return false
        val found = arrayOfNulls<AccessibilityNodeInfo>(1)
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || found[0] != null) return
            val t = n.text?.toString()?.trim(); val d = n.contentDescription?.toString()?.trim()
            if (n.isVisibleToUser && labels.any { it.equals(t, true) || it.equals(d, true) }) { found[0] = n; return }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        val n = found[0] ?: return false
        var x: AccessibilityNodeInfo? = n
        while (x != null) { if (x.isClickable && x.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true; x = x.parent }
        val r = Rect(); n.getBoundsInScreen(r)
        return if (r.width() > 0 && r.height() > 0) tap(r.centerX().toFloat(), r.centerY().toFloat()) else false
    }

    /** Screen ka poora dikhta text (lamba reply padhne ke liye, jaise Gemini ka jawab). */
    fun dumpText(max: Int = 3500): String {
        val root = rootInActiveWindow ?: return ""
        val sb = StringBuilder(); var last = ""
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || sb.length >= max) return
            val t = (n.text ?: n.contentDescription)?.toString()?.trim()
            if (!t.isNullOrEmpty() && t != last && n.isVisibleToUser) { sb.append(t).append('\n'); last = t }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return sb.toString().take(max)
    }

    /** Screenshot (Android 11+): model ko "dikhane" ke liye. */
    fun takeBitmap(): Bitmap? {
        if (Build.VERSION.SDK_INT < 30) return null
        val latch = CountDownLatch(1); val out = AtomicReference<Bitmap?>(null)
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(r: AccessibilityService.ScreenshotResult) {
                try {
                    val hb = r.hardwareBuffer
                    val b = Bitmap.wrapHardwareBuffer(hb, r.colorSpace)
                    out.set(b?.copy(Bitmap.Config.ARGB_8888, false)); b?.recycle(); hb.close()
                } catch (_: Exception) {}
                latch.countDown()
            }
            override fun onFailure(code: Int) { latch.countDown() }
        })
        latch.await(6, TimeUnit.SECONDS)
        return out.get()
    }

    // ================= v18: YouTube - pehla (ad nahi) matching video chalao =================
    private fun collectVideos(n: AccessibilityNodeInfo?, out: MutableList<Pair<AccessibilityNodeInfo, String>>, depth: Int = 0) {
        if (n == null || depth > 28) return
        val d = n.contentDescription?.toString() ?: ""
        if (d.length > 20 && (d.contains("play video", true) || (d.contains("views", true) && (d.contains("minutes", true) || d.contains("seconds", true) || d.contains("ago", true))))) out.add(n to d)
        for (i in 0 until n.childCount) collectVideos(n.getChild(i), out, depth + 1)
    }

    private fun clickableAncestor(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var c = n; var k = 0
        while (c != null && k < 7) { if (c.isClickable) return c; c = c.parent; k++ }
        return n
    }

    /** Background thread se bulao. Ad/sponsored skip, naam match dekhkar click, phir 2 baar check ke chal raha hai. */
    fun playFirstMatch(query: String): String {
        val words = query.lowercase().split(Regex("[^a-z0-9\u0600-\u06FF]+")).filter { it.length >= 2 }
        val deadline = System.currentTimeMillis() + 16_000L
        while (System.currentTimeMillis() < deadline) {
            val root = rootInActiveWindow
            if (root != null && (root.packageName?.toString() ?: "").contains("youtube")) {
                val cands = ArrayList<Pair<AccessibilityNodeInfo, String>>()
                collectVideos(root, cands)
                val ok = cands.filter { (_, d) -> val l = d.lowercase(); !(l.contains("sponsored") || l.contains("advert") || l.contains("promoted") || l.startsWith("ad ") || l.contains(" ad ·") || l.contains("ad •") || l.contains("shopping")) }
                if (ok.isNotEmpty()) {
                    val scored = ok.map { it to words.count { w -> it.second.lowercase().contains(w) } }
                    val pick = scored.firstOrNull { it.second >= maxOf(1, (words.size + 1) / 2) } ?: scored.first()
                    val node = clickableAncestor(pick.first.first)
                    if (node != null && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        Thread.sleep(2500)
                        return "Chal raha hai: " + pick.first.second.substringBefore(" - ").take(90) + (if (pick.second > 0) "" else " (naam match nahi mila, pehla non-ad liya)")
                    }
                }
            }
            Thread.sleep(500)
        }
        return "YouTube par koi video nahi mila (net band ho sakta hai)"
    }
}
