package com.nova.app

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * Transparent chhoti activity: Android 10+ clipboard sirf "focus wali" app ko padhne deti hai.
 * Ye ek pal ke liye focus leti hai, clipboard NOVA clipboard mein save karti hai, aur band ho jati hai.
 */
class ClipGrabActivity : Activity() {
    private var done = false
    override fun onCreate(b: Bundle?) { super.onCreate(b); Handler(Looper.getMainLooper()).postDelayed({ finishWith("clipboard padh nahi saki") }, 1800) }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || done) return
        val t = ClipStore.readNow(this)
        if (t.isNullOrBlank()) finishWith("clipboard khali hai")
        else { val saved = ClipStore.add(this, t, ClipStore.pendingAbout); ClipStore.pendingAbout = ""; finishWith(if (saved) "clip save: " + t.take(70) else "ye clip pehle se save hai: " + t.take(70)) }
    }
    private fun finishWith(msg: String) { if (done) return; done = true; ClipStore.grabResult = msg; ClipStore.grabLatch?.countDown(); finish(); overridePendingTransition(0, 0) }
}
