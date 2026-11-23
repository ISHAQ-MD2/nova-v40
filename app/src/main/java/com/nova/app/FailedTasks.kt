package com.nova.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

/** Jo kaam poora nahi hua: list + notification mein "Retry" button. Aur lock screen ki wajah se ruke hue kaam. */
object FailedTasks {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("nova", Context.MODE_PRIVATE)
    fun all(ctx: Context): List<JSONObject> = try { val a = JSONArray(prefs(ctx).getString("failed_tasks", "[]")); (0 until a.length()).map { a.getJSONObject(it) } } catch (_: Exception) { emptyList() }
    private fun save(ctx: Context, l: List<JSONObject>) { val a = JSONArray(); l.takeLast(30).forEach { a.put(it) }; prefs(ctx).edit().putString("failed_tasks", a.toString()).apply() }

    @Synchronized fun add(ctx: Context, text: String, reason: String): Int {
        val id = (System.currentTimeMillis() % 1_000_000L).toInt()
        save(ctx, all(ctx) + JSONObject().put("id", id).put("text", text).put("reason", reason).put("t", System.currentTimeMillis()))
        return id
    }
    @Synchronized fun remove(ctx: Context, id: Int) { save(ctx, all(ctx).filter { it.optInt("id") != id }) }

    fun retry(ctx: Context, id: Int) {
        val t = all(ctx).firstOrNull { it.optInt("id") == id } ?: return
        remove(ctx, id)
        try { ctx.startForegroundService(Intent(ctx, AgentService::class.java).putExtra("instruction", t.optString("text"))) } catch (_: Exception) {}
    }

    private fun channel(ctx: Context): NotificationManager {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("nova_fail", "NOVA task alerts", NotificationManager.IMPORTANCE_HIGH))
        return nm
    }

    fun notifyFailed(ctx: Context, id: Int, text: String, reason: String) {
        val nm = channel(ctx)
        fun pi(a: String, code: Int) = PendingIntent.getBroadcast(ctx, id * 3 + code, Intent(ctx, TaskRetryReceiver::class.java).setAction(a).putExtra("id", id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        @Suppress("DEPRECATION")
        val n = android.app.Notification.Builder(ctx, "nova_fail").setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Sir, task nahi ho saka").setContentText(reason.take(120)).setStyle(android.app.Notification.BigTextStyle().bigText("$text\n\nWajah: $reason"))
            .addAction(android.R.drawable.ic_menu_rotate, "Retry", pi("retry", 1)).addAction(android.R.drawable.ic_menu_delete, "Hatao", pi("dismiss", 2)).setAutoCancel(true).build()
        nm.notify(id, n)
    }

    fun notifyWaiting(ctx: Context, text: String) {
        val nm = channel(ctx)
        val n = android.app.Notification.Builder(ctx, "nova_fail").setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("NOVA ka kaam intezaar mein").setContentText("Phone unlock karo - kaam khud shuru ho jayega (ya Settings mein lock PIN do)")
            .setStyle(android.app.Notification.BigTextStyle().bigText("Phone lock tha, isliye ye kaam ruka hai:\n$text\n\nUnlock karte hi NOVA khud kar degi. PIN: NOVA Settings > Screen Lock PIN.")).setAutoCancel(true).build()
        nm.notify(7777, n)
    }
}

class TaskRetryReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val id = i.getIntExtra("id", 0)
        try { ctx.getSystemService(NotificationManager::class.java).cancel(id) } catch (_: Exception) {}
        if (i.action == "retry") FailedTasks.retry(ctx, id) else FailedTasks.remove(ctx, id)
    }
}

/** Phone lock hone ki wajah se jo kaam ruke - unlock hote hi (USER_PRESENT) ek ek karke chalte hain. */
object PendingTasks {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("nova", Context.MODE_PRIVATE)
    @Synchronized fun add(ctx: Context, text: String) {
        val a = try { JSONArray(prefs(ctx).getString("pending_tasks", "[]")) } catch (_: Exception) { JSONArray() }
        a.put(text); prefs(ctx).edit().putString("pending_tasks", a.toString()).apply()
    }
    @Synchronized fun pop(ctx: Context): String? {
        val a = try { JSONArray(prefs(ctx).getString("pending_tasks", "[]")) } catch (_: Exception) { JSONArray() }
        if (a.length() == 0) return null
        val first = a.getString(0); val rest = JSONArray(); for (i in 1 until a.length()) rest.put(a.getString(i))
        prefs(ctx).edit().putString("pending_tasks", rest.toString()).apply(); return first
    }
    fun count(ctx: Context): Int = try { JSONArray(prefs(ctx).getString("pending_tasks", "[]")).length() } catch (_: Exception) { 0 }
    fun runNext(ctx: Context) {
        if (Unlocker.isLocked(ctx)) return
        val t = pop(ctx) ?: return
        try { ctx.startForegroundService(Intent(ctx, AgentService::class.java).putExtra("instruction", t)) } catch (_: Exception) {}
    }
}
