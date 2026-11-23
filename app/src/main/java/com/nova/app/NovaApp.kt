package com.nova.app

import android.app.Application
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/** Crash hone par wajah file mein likh deta hai (crash.log) aur app khud band hone se pehle memory mein note karta hai. */
class NovaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Memory.init(this)
        Memory.onLog = { k, t -> try { WallBus.onLog(k, t) } catch (_: Throwable) {} }      // wallpaper ko asli events
        try { VoiceEngine.init(this) } catch (_: Throwable) {}
        try { Guard.start(this) } catch (_: Throwable) {}
        try { NovaBrowser.init(this) } catch (_: Throwable) {}      // v36: NOVA ka apna browser (settings/wallpaper ko pehle se tayyar)
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val msg = "$ts [${t.name}] ${e.stackTraceToString().take(3000)}\n"
                File(filesDir, "crash.log").appendText(msg)
                Backup.appendLine("crash.log", msg)
                Memory.log("crash", e.toString())
            } catch (_: Throwable) {}
            // background thread ka crash app ko band na kare (sirf log); main thread ka crash normal
            if (t === android.os.Looper.getMainLooper().thread) old?.uncaughtException(t, e)
        }
    }
}
