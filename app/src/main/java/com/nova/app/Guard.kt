package com.nova.app

import android.content.Context
import android.content.Intent

/**
 * v30: Pehredaar. Process zinda ho to har 15 second dekhta hai ke koi reminder ka waqt guzar gaya par bajaa nahi
 * (AlarmManager late ho / phone ne rok diya) - to khud bajata hai. TaskReceiver ka guard dobara bajne nahi deta.
 */
object Guard {
    @Volatile private var started = false

    fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        Thread {
            while (true) {
                try { Thread.sleep(15_000) } catch (_: InterruptedException) { break }
                try {
                    val now = System.currentTimeMillis()
                    for (t in TaskStore.all(app)) {
                        val at = t.optLong("at")
                        if (at <= now - 3_000L && at > now - 4 * 60_000L) {
                            app.sendBroadcast(Intent(app, TaskReceiver::class.java).putExtra("id", t.optInt("id")))
                        }
                    }
                } catch (_: Throwable) {}
            }
        }.also { it.isDaemon = true; it.name = "nova-guard"; it.start() }
    }
}
