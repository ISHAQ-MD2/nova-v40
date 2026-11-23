package com.nova.app

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/** Reminder / scheduled kaam: "12:30 par Sajid ko message karo" - time par NOVA khud kaam karti hai. */
object TaskStore {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("nova_tasks", Context.MODE_PRIVATE)

    @Synchronized fun all(ctx: Context): List<JSONObject> {
        val a = try { JSONArray(prefs(ctx).getString("list", "[]")) } catch (_: Exception) { JSONArray() }
        return (0 until a.length()).map { a.getJSONObject(it) }
    }
    @Synchronized private fun save(ctx: Context, l: List<JSONObject>) {
        val a = JSONArray(); l.forEach { a.put(it) }
        prefs(ctx).edit().putString("list", a.toString()).apply()
        Backup.saveTasks(ctx)
    }
    fun find(ctx: Context, id: Int) = all(ctx).firstOrNull { it.optInt("id") == id }

    /** v13: waqt guzar chuke (fire ho chuke / miss) reminders khud delete. Repeat wale ko aage badha deta hai. */
    @Synchronized fun purgeExpired(ctx: Context) {
        val now = System.currentTimeMillis()
        for (t in all(ctx)) {
            val id = t.optInt("id"); val at = t.optLong("at")
            val until = t.optLong("until", 0L); val ev = t.optInt("every", 0)
            if (until > 0 && now > until) { remove(ctx, id); continue }
            if (at < now - 5 * 60_000L) {
                when {
                    ev > 0 -> setAt(ctx, id, now + 60_000L)
                    t.optBoolean("rep") -> { var a = at; while (a <= now) a += 24L * 3600 * 1000; setAt(ctx, id, a) }
                    else -> remove(ctx, id)
                }
            }
        }
    }

    @Synchronized fun add(ctx: Context, at: Long, text: String, repeat: Boolean, everyMin: Int = 0, until: Long = 0L): Int {
        // v12: ek hi reminder dobara na lage (model kabhi kabhi schedule_task do baar bulata hai)
        all(ctx).firstOrNull { it.optString("text").trim().equals(text.trim(), true) && Math.abs(it.optLong("at") - at) < 90_000L }
            ?.let { return it.optInt("id") }
        val id = prefs(ctx).getInt("next", 1)
        prefs(ctx).edit().putInt("next", id + 1).apply()
        save(ctx, all(ctx) + JSONObject().put("id", id).put("at", at).put("text", text).put("rep", repeat)
            .put("every", everyMin).put("until", until))
        schedule(ctx, id, at)
        return id
    }
    @Synchronized fun addRestored(ctx: Context, at: Long, text: String, repeat: Boolean, everyMin: Int = 0, until: Long = 0L): Int =
        add(ctx, at, text, repeat, everyMin, until)

    @Synchronized fun remove(ctx: Context, id: Int): Boolean {
        val l = all(ctx); val n = l.filter { it.optInt("id") != id }
        cancel(ctx, id)
        if (n.size == l.size) return false
        save(ctx, n); return true
    }
    @Synchronized fun setAt(ctx: Context, id: Int, at: Long) {
        save(ctx, all(ctx).map { if (it.optInt("id") == id) it.put("at", at) else it })
        schedule(ctx, id, at)
    }

    private fun pi(ctx: Context, id: Int) = PendingIntent.getBroadcast(
        ctx, id, Intent(ctx, TaskReceiver::class.java).putExtra("id", id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /**
     * v30: setAlarmClock sabse bharosemand hai (Doze mein bhi theek waqt par, exact-alarm permission ki zaroorat nahi).
     * Purane code mein permission na hone par 'inexact' alarm lagta tha - isi liye reminder late / gayab hota tha.
     */
    fun schedule(ctx: Context, id: Int, at: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        try {
            val show = PendingIntent.getActivity(ctx, 5000 + id, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi(ctx, id))
            return
        } catch (_: Exception) {}
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(ctx, id))
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(ctx, id))
    }
    fun cancel(ctx: Context, id: Int) { try { ctx.getSystemService(AlarmManager::class.java).cancel(pi(ctx, id)) } catch (_: Exception) {} }

    fun describe(t: JSONObject): String {
        val w = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(t.optLong("at")))
        val ev = t.optInt("every", 0)
        val rep = if (ev > 0) " (har $ev min)" else if (t.optBoolean("rep")) " (daily)" else ""
        return "#${t.optInt("id")} $w$rep: ${t.optString("text")}"
    }
}

class TaskReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val id = i.getIntExtra("id", -1)
        val t = TaskStore.find(ctx, id) ?: return
        val text = t.optString("text")
        // v12: ONE-SHOT guard - same reminder 2 minute ke andar dobara nahi bajega
        val fp = ctx.getSharedPreferences("nova_fired", Context.MODE_PRIVATE)
        val key = "$id|${text.hashCode()}"
        val nowMs = System.currentTimeMillis()
        if (nowMs - fp.getLong(key, 0L) < 120_000L) { if (!t.optBoolean("rep") && t.optInt("every", 0) <= 0) TaskStore.remove(ctx, id); return }
        fp.edit().putLong(key, nowMs).apply()
        val every = t.optInt("every", 0)
        val until = t.optLong("until", 0L)
        if (every > 0) {
            val next = System.currentTimeMillis() + every * 60_000L
            if (until > 0 && next > until) TaskStore.remove(ctx, id) else TaskStore.setAt(ctx, id, next)
        } else if (t.optBoolean("rep")) TaskStore.setAt(ctx, id, t.optLong("at") + 24L * 3600 * 1000)
        else TaskStore.remove(ctx, id)
        Memory.init(ctx); Memory.log("task", "Scheduled task shuru: $text")
        try { WallBus.alert(text.removePrefix("notify_user:").removePrefix("NOTIFY_USER:").trim().take(90)) } catch (_: Throwable) {}   // wallpaper ke upar bhi
        Unlocker.wake(ctx, 30_000L)              // screen off ho to jaga do
        if (text.startsWith("notify_user:", ignoreCase = true)) {
            val msg = text.substringAfter(":").trim()
            Notifier.show(ctx, "NOVA reminder", msg)
            RobotPet.say(ctx, "Sir, it's reminder time! $msg", 12000L)
            Thread { Notifier.speakBlocking(ctx, "Sir, it's reminder time. $msg", 20000) }.start()
            TaskStore.purgeExpired(ctx)
            return
        }
        RobotPet.say(ctx, "Sir, it's reminder time!", 6000L)
        Notifier.show(ctx, "NOVA kaam shuru", text)
        try { ctx.startForegroundService(Intent(ctx, AgentService::class.java).putExtra("instruction", text)) }
        catch (_: Exception) { Notifier.show(ctx, "NOVA reminder", text) }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        Memory.init(ctx)
        TaskStore.purgeExpired(ctx)
        Prayer.scheduleNext(ctx)
        try { ReminderStore.rescheduleAll(ctx) } catch (_: Exception) {}
        val now = System.currentTimeMillis()
        for (t in TaskStore.all(ctx)) {
            var at = t.optLong("at")
            val ev = t.optInt("every", 0)
            if (ev > 0 && at <= now) {
                val until = t.optLong("until", 0L)
                if (until > 0 && now > until) TaskStore.remove(ctx, t.optInt("id"))
                else TaskStore.setAt(ctx, t.optInt("id"), now + 60_000L)
            }
            else if (at <= now && t.optBoolean("rep")) { while (at <= now) at += 24L * 3600 * 1000; TaskStore.setAt(ctx, t.optInt("id"), at) }
            else if (at > now) TaskStore.schedule(ctx, t.optInt("id"), at)
            else if (!t.optBoolean("rep") && ev <= 0) TaskStore.schedule(ctx, t.optInt("id"), now + 5_000L)   // abhi-abhi miss hua (5 min ke andar) - jaldi chala do
        }
    }
}

object Notifier {
    fun show(ctx: Context, title: String, text: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("nova_alerts", "NOVA alerts", NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify((System.currentTimeMillis() % 100000).toInt(),
            android.app.Notification.Builder(ctx, "nova_alerts").setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(title).setContentText(text.take(200)).setStyle(android.app.Notification.BigTextStyle().bigText(text.take(1000)))
                .setContentIntent(pi).setAutoCancel(true).build())
    }
    fun speak(ctx: Context, text: String) { Thread { speakBlocking(ctx, text, 20000) }.start() }

    /** Bolna shuru karke khatam hone tak ruka rehta hai (background thread se bulao). */
    fun speakBlocking(ctx: Context, text: String, timeoutMs: Long = 15000) {
        VoiceEngine.speakBlocking(ctx, text, timeoutMs)
    }
}

/** Alarm baje to yeh service background mein "agent" chalati hai (Gemini + wahi tools). */
class AgentService : Service() {
    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        val text = i?.getStringExtra("instruction")
        if (text == null) { stopSelf(); return START_NOT_STICKY }
        Memory.init(this)
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("nova_agent", "NOVA tasks", NotificationManager.IMPORTANCE_LOW))
        val n = android.app.Notification.Builder(this, "nova_agent").setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("NOVA kaam kar rahi hai").setContentText(text.take(100)).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(2, n)
        @Suppress("DEPRECATION")
        val wl = getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE, "nova:agent")
        wl.acquire(10 * 60 * 1000L)
        Thread {
            try {
                val app = applicationContext
                Unlocker.wake(app, 60_000L)
                if (Unlocker.isLocked(app)) Memory.log("unlock", Unlocker.unlock(app))
                if (Unlocker.isLocked(app)) {
                    // phone khul nahi saka (PIN nahi / pattern) - kaam rok kar unlock ka intezaar, phir khud chalega
                    PendingTasks.add(app, text); FailedTasks.notifyWaiting(app, text)
                    RobotPet.say(app, "Sir, phone unlock karo - kaam intezaar mein hai", 9000L)
                    return@Thread
                }
                try { NovaAccessibilityService.instance?.home(); Thread.sleep(800) } catch (_: Exception) {}
                val res: String
                if (text.startsWith("candles_watch:") || text.startsWith("screen_watch:")) {
                    res = Trading.runWatch(app, text)
                    Memory.log("task-result", res)
                    Notifier.show(app, "NOVA trading analysis", res)
                    Notifier.speakBlocking(app, Trading.spokenLine(res), 25000)
                } else if (text.startsWith("do:")) {
                    // v13: offline-safe seedha kaam (Gemini ke bina): do:tool|who|message
                    val parts = text.removePrefix("do:").split("|", limit = 3)
                    val args = org.json.JSONObject()
                    if (parts[0] == "whatsapp_send") { args.put("who", parts.getOrElse(1) { "" }); args.put("message", parts.getOrElse(2) { "" }) }
                    else if (parts.size > 1) args.put("who", parts[1])
                    RobotPet.say(app, "Sir, it's reminder time!", 6000L)
                    res = Tools.execute(app, parts[0], args)
                    Memory.log("task-result", res)
                    Notifier.show(app, "NOVA kaam ho gaya", res)
                    Notifier.speakBlocking(app, "Sir, kaam ho gaya.", 10000)
                } else {
                    Notifier.speakBlocking(app, "Sir, it's reminder time.", 8000)
                    // v30: live NOVA ke saath takrao se bachne ke liye Busy.scheduled() live session ko roke rakhta hai
                    res = Busy.scheduled(app) { AgentRunner.run(app, text) }
                    Memory.log("task-result", res)
                    if (res.startsWith("FAIL:")) {
                        val why = res.removePrefix("FAIL:").trim()
                        val fid = FailedTasks.add(app, text, why)
                        FailedTasks.notifyFailed(app, fid, text, why)
                        RobotPet.say(app, "Sir, task nahi ho saka. Retry button notification mein hai.", 9000L)
                        Notifier.speakBlocking(app, "Sir, task nahi ho saka. $why", 20000)
                    } else {
                        val okText = res.removePrefix("OK:").trim()
                        Notifier.show(app, "NOVA kaam ho gaya", okText)
                        Notifier.speakBlocking(app, "Sir, kaam ho gaya. " + okText.take(160), 25000)
                    }
                }
            } catch (e: Exception) {
                Notifier.show(applicationContext, "NOVA task error", e.message ?: "error")
            } finally {
                try { if (wl.isHeld) wl.release() } catch (_: Exception) {}
                TaskStore.purgeExpired(applicationContext)
                stopSelf()
                try { PendingTasks.runNext(applicationContext) } catch (_: Exception) {}
            }
        }.start()
        return START_NOT_STICKY
    }
}
