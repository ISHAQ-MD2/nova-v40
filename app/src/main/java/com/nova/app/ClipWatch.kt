package com.nova.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/**
 * Clipboard link watcher for supported direct media / TikTok / Instagram links.
 * Routes approved downloads into DownloadVault + DownloadsActivity.
 * Explicitly rejects YouTube URLs (unsupported).
 */
object ClipWatch {
    @Volatile private var started = false
    private var lastSeen = ""

    private val HOSTS = listOf(
        "tiktok.com" to "TikTok",
        "vt.tiktok.com" to "TikTok",
        "vm.tiktok.com" to "TikTok",
        "instagram.com" to "Instagram",
        "facebook.com" to "Facebook",
        "fb.watch" to "Facebook"
    )

    private fun site(url: String): String? {
        if (DownloadVault.isYouTubeUrl(url)) return null
        val lower = url.lowercase()
        if (lower.contains(".mp4") || lower.contains(".webm") || lower.contains(".m3u8")) return "Video"
        val host = Regex("https?://([^/]+)").find(url)?.groupValues?.get(1)?.lowercase() ?: return null
        return HOSTS.firstOrNull { host.contains(it.first) }?.second
    }

    fun autoStart(ctx: Context) {
        if (started) return
        if (!ctx.getSharedPreferences("nova", Context.MODE_PRIVATE).getBoolean("clipwatch", true)) return
        started = true
        val app = ctx.applicationContext
        Handler(Looper.getMainLooper()).post {
            try {
                val cm = app.getSystemService(ClipboardManager::class.java)
                cm.addPrimaryClipChangedListener {
                    try {
                        val t = ClipStore.readNow(app)?.trim() ?: return@addPrimaryClipChangedListener
                        if (t.isEmpty() || t == lastSeen) return@addPrimaryClipChangedListener
                        lastSeen = t
                        if (!t.startsWith("http")) return@addPrimaryClipChangedListener
                        val s = site(t) ?: return@addPrimaryClipChangedListener
                        if (!app.getSharedPreferences("nova", Context.MODE_PRIVATE).getBoolean("clipwatch", true)) return@addPrimaryClipChangedListener
                        notifyDownload(app, s, t)
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
    }

    private fun notifyDownload(ctx: Context, site: String, url: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("nova_clip", "NOVA clipboard links", NotificationManager.IMPORTANCE_DEFAULT))
        val dl = PendingIntent.getBroadcast(
            ctx,
            url.hashCode(),
            Intent(ctx, ClipDownloadReceiver::class.java).putExtra("url", url),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        nm.notify(
            (url.hashCode() and 0x7fffffff) % 90000 + 5000,
            Notification.Builder(ctx, "nova_clip")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("$site link detected")
                .setContentText("Tap Download to save to Download/NOVA-Downloads")
                .addAction(0, "Download", dl)
                .setAutoCancel(true)
                .build()
        )
        ClipStore.add(ctx, url, "$site link")
        WallBus.pushFeed("CLIP", "$site link detected")
    }
}

class ClipDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val url = i.getStringExtra("url") ?: return
        if (DownloadVault.isYouTubeUrl(url)) {
            Notifier.show(ctx, "NOVA Downloads", "YouTube downloads are unsupported in this app.")
            return
        }
        DownloadVault.enqueue(ctx, url)
        Notifier.show(ctx, "NOVA Downloads", "Download added: $url")
        try {
            ctx.startActivity(Intent(ctx, DownloadsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
    }
}
