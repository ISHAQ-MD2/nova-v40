package com.nova.app

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager

/**
 * Screen lock / auto-unlock.
 * - PIN phone mein Keystore se encrypt hoke rehta hai (backup file mein NAHI jata).
 * - Unlock: screen jagao -> upar swipe -> PIN ke digits keypad par dabao (Accessibility se).
 * - Sirf PIN / password. Pattern ya fingerprint-only lock ko NOVA khol nahi sakti.
 */
object Unlocker {
    private const val KEY = "lockpin"
    private val lock = Any()

    fun savePin(ctx: Context, pin: String) {
        ctx.getSharedPreferences("nova", Context.MODE_PRIVATE).edit().putString(KEY, if (pin.isEmpty()) "" else Crypto.enc(pin)).apply()
    }
    fun hasPin(ctx: Context) = !(ctx.getSharedPreferences("nova", Context.MODE_PRIVATE).getString(KEY, "") ?: "").isEmpty()
    private fun pin(ctx: Context) = Crypto.dec(ctx.getSharedPreferences("nova", Context.MODE_PRIVATE).getString(KEY, "") ?: "")

    fun isLocked(ctx: Context): Boolean = try {
        (ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
    } catch (_: Exception) { false }

    fun isScreenOn(ctx: Context): Boolean = try {
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
    } catch (_: Exception) { true }

    @Suppress("DEPRECATION")
    fun wake(ctx: Context, ms: Long = 20_000L) {
        try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE, "nova:wake")
            wl.acquire(ms)
        } catch (_: Exception) {}
    }

    /** true = ab screen khuli hai. Background thread se bulao (sleep karta hai). */
    fun unlock(ctx: Context): String = synchronized(lock) { unlockInner(ctx) }

    private fun unlockInner(ctx: Context): String {
        wake(ctx)
        Thread.sleep(700)
        if (!isLocked(ctx)) return "screen pehle se unlock hai"
        val acc = NovaAccessibilityService.instance ?: return "Accessibility ON nahi hai"
        val dm = ctx.resources.displayMetrics
        val w = dm.widthPixels.toFloat(); val h = dm.heightPixels.toFloat()
        acc.swipe(w / 2, h * 0.88f, w / 2, h * 0.25f, 350)
        Thread.sleep(900)
        if (!isLocked(ctx)) return "unlock ho gaya (swipe)"
        val p = pin(ctx)
        if (p.isEmpty()) return "Screen lock par PIN hai lekin NOVA ko PIN nahi diya - menu mein 'Screen Lock PIN' set karo"
        var ok = true
        if (p.all { it.isDigit() }) {
            for (c in p) {
                var hit = false
                repeat(3) { if (!hit) { hit = acc.clickExact(listOf(c.toString())); if (!hit) Thread.sleep(250) } }
                if (!hit) { ok = false; break }
                Thread.sleep(180)
            }
            Thread.sleep(300)
            if (isLocked(ctx)) acc.clickExact(listOf("Enter", "OK", "Done", "enter"))
        } else {
            ok = acc.typeText(p)
            Thread.sleep(300)
            if (isLocked(ctx)) acc.clickExact(listOf("Enter", "OK", "Done", "enter"))
        }
        Thread.sleep(1000)
        return if (!isLocked(ctx)) "unlock ho gaya" else if (!ok) "keypad par digits nahi mile (pattern lock ya alag keypad?)" else "PIN dala lekin screen khuli nahi - PIN ghalat to nahi?"
    }

    fun lockNow(): String {
        val acc = NovaAccessibilityService.instance ?: return "Accessibility ON nahi hai"
        return if (acc.lock()) "screen lock kar di" else "lock nahi ho saki (Android 9+ chahiye)"
    }
}
