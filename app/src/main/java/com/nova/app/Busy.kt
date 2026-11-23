package com.nova.app

import android.content.Context

/**
 * v30: Live NOVA aur scheduled kaam (reminder) ek saath screen par na ladein.
 * Pehle NOVA YouTube search kar rahi hoti thi aur Play Store wala kaam aa jata tha - dono confuse hokar band ho jate the.
 * Ab: scheduled kaam pehle live tools ke khatam hone ka intezaar karta hai, NOVA ko sula deta hai, kaam karta hai, phir NOVA wapas.
 */
object Busy {
    fun <T> scheduled(ctx: Context, block: () -> T): T {
        val lc = LiveService.liveClient
        var suspended = false
        if (lc != null) {
            try {
                lc.waitIdleTools(25_000L)
                lc.waitQuiet(6_000L)
                lc.suspendForVoice()
                suspended = true
                WallBus.pushFeed("BUSY", "live NOVA ruki, scheduled kaam chal raha hai")
            } catch (_: Throwable) {}
        }
        try { return block() }
        finally {
            if (suspended) try { lc?.resumeAfterVoice("(system) Scheduled kaam khatam. Normal baat-cheet jaari.") } catch (_: Throwable) {}
        }
    }
}
