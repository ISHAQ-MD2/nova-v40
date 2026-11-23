package com.nova.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** Live call foreground service mein chalti hai, taake YouTube/doosri app khulne par bhi mic chalta rahe. */
class LiveService : Service() {
    companion object {
        @Volatile var instance: LiveService? = null
        @Volatile var lastStatus = "Ready"
        var onStatus: ((String) -> Unit)? = null
        var onSpeaking: ((Boolean) -> Unit)? = null
        var onEnd: (() -> Unit)? = null
        fun isRunning() = instance != null
        @Volatile var liveClient: LiveClient? = null
        fun sendText(t: String) { liveClient?.sendText(t) ?: onStatus?.invoke("Pehle NOVA Start karo") }
        fun sendPhoto(b: ByteArray) { liveClient?.sendPhoto(b) }
    }

    private var client: LiveClient? = null
    private var bubble: Bubble? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("nova_live", "NOVA live", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "nova_live")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("NOVA live")
            .setContentText("Sun rahi hoon - app kholne ke liye tap karo")
            .setContentIntent(pi).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, n)
        }
        instance = this
        Memory.init(this)

        if (client == null) {
            val p = getSharedPreferences("nova", MODE_PRIVATE)
            val key = Keys.active(this)
            if (key.isEmpty()) { report("API key nahi mili - Setup mein daalo"); onEnd?.invoke(); stopSelf(); return START_NOT_STICKY }
            val persona = if (p.getString("p", "girl") == "girl") Personas.girl else Personas.boy
            val user = (p.getString("u", "") ?: "").ifEmpty { "Boss" }
            val c = LiveClient(this, key, persona, user,
                { s -> lastStatus = s; onStatus?.invoke(s) },
                { v -> Vol.setSpeaking(v); onSpeaking?.invoke(v) },
                { Vol.setSpeaking(false); onEnd?.invoke(); stopSelf() },
                { name, args -> Tools.execute(applicationContext, name, args) })
            client = c
            liveClient = c
            if (p.getBoolean("bubble", true) && android.provider.Settings.canDrawOverlays(this)) {
                val items = listOf<Pair<String, () -> Unit>>(
                    "🎙 Bulao / Sulao" to { c.toggleActive() },
                    "🎤 Mic mute / unmute" to { c.setMicMuted(!Vol.micMuted) },
                    "🔇 NOVA awaaz mute / unmute" to { Vol.speakerMuted = !Vol.speakerMuted; report(if (Vol.speakerMuted) "NOVA ki awaaz mute - text se baat karo" else "NOVA ki awaaz ON") },
                    "🔉 Volume kam" to { vol(false) },
                    "🔊 Volume zyada" to { vol(true) },
                    "🎓 College mode" to { toggleCollege(c) },
                    "⏭ Ads auto-skip ON/OFF" to { toggleAds() },
                    "📋 Clipboard save" to { Thread { val r = ClipStore.grabBlocking(applicationContext); android.os.Handler(android.os.Looper.getMainLooper()).post { report(r) } }.start() },
                    "🎵 Music pause / play" to { report(if (MusicPlayer.isPlaying()) MusicPlayer.pause() else MusicPlayer.resume()) },
                    "💾 Backup abhi" to { Thread { val r = Backup.backupToDownloads(applicationContext); android.os.Handler(android.os.Looper.getMainLooper()).post { report(r) } }.start() },
                    "⛔ Off NOVA" to { c.stop() }
                )
                val b = Bubble(this, { c.toggleActive() }, {}, items)
                bubble = b; b.show()
                c.onActive = { a -> b.setActive(a) }
            }
            c.start()
        }
        return START_NOT_STICKY
    }

    private fun toggleAds() {
        val p = getSharedPreferences("nova", MODE_PRIVATE)
        val on = !p.getBoolean("adskip", true)
        p.edit().putBoolean("adskip", on).apply()
        report(if (on) "⏭ Ads auto-skip ON" else "Ads auto-skip OFF")
    }

    private fun vol(up: Boolean) {
        val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        val d = if (up) android.media.AudioManager.ADJUST_RAISE else android.media.AudioManager.ADJUST_LOWER
        try { am.adjustStreamVolume(android.media.AudioManager.STREAM_VOICE_CALL, d, 0) } catch (_: Exception) {}
        try { am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, d, android.media.AudioManager.FLAG_SHOW_UI) } catch (_: Exception) {}
    }

    /** College / dosto ke beech: sirf "NOVA" bolne par jagegi aur bahut chhota jawab degi. */
    private fun toggleCollege(c: LiveClient) {
        val p = getSharedPreferences("nova", MODE_PRIVATE)
        val on = !p.getBoolean("college", false)
        p.edit().putBoolean("college", on).putBoolean("wake", true).apply()
        report(if (on) "🎓 College mode ON - sirf 'NOVA' bolne par, chhota jawab" else "College mode OFF")
        c.enterPassive()
    }

    private fun report(s: String) { lastStatus = s; onStatus?.invoke(s) }

    override fun onDestroy() {
        bubble?.hide(); bubble = null
        client?.stop(); client = null; liveClient = null
        if (instance === this) instance = null
        super.onDestroy()
    }
}
