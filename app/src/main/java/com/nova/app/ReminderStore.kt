package com.nova.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

data class ReminderItem(
    val id: Int,
    var title: String,
    var hour: Int,
    var minute: Int,
    var repeatDaily: Boolean = true,
    var enabled: Boolean = true,
    var toneUri: String = "",
    var preAlertMinutes: Int = 0,
    val isNamaz: Boolean = false,
    var nextTriggerMs: Long = 0L
)

/**
 * Full Reminder & Namaz-Style Alarm store.
 * - Seeds editable Fajr, Zuhr, Asr, Maghrib, Isha presets from Prayer.times()
 * - Schedules exact AlarmManager wakeups (accounting for preAlertMinutes)
 */
object ReminderStore {
    private const val PREF = "nova_reminders_v2"
    private const val KEY_LIST = "items"
    private const val KEY_NEXT = "next_id"
    private const val KEY_SEEDED = "namaz_seeded"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    @Synchronized
    fun all(ctx: Context): List<ReminderItem> {
        seedNamazIfNeeded(ctx)
        val raw = sp(ctx).getString(KEY_LIST, "[]") ?: "[]"
        val out = ArrayList<ReminderItem>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    ReminderItem(
                        id = o.getInt("id"),
                        title = o.optString("title", "Reminder"),
                        hour = o.optInt("hour", 8),
                        minute = o.optInt("minute", 0),
                        repeatDaily = o.optBoolean("repeatDaily", true),
                        enabled = o.optBoolean("enabled", true),
                        toneUri = o.optString("toneUri", ""),
                        preAlertMinutes = o.optInt("preAlertMinutes", 0),
                        isNamaz = o.optBoolean("isNamaz", false),
                        nextTriggerMs = o.optLong("nextTriggerMs", 0L)
                    )
                )
            }
        } catch (_: Exception) {}
        return out
    }

    @Synchronized
    private fun saveList(ctx: Context, list: List<ReminderItem>) {
        val arr = JSONArray()
        for (it in list) {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("title", it.title)
                    .put("hour", it.hour)
                    .put("minute", it.minute)
                    .put("repeatDaily", it.repeatDaily)
                    .put("enabled", it.enabled)
                    .put("toneUri", it.toneUri)
                    .put("preAlertMinutes", it.preAlertMinutes)
                    .put("isNamaz", it.isNamaz)
                    .put("nextTriggerMs", it.nextTriggerMs)
            )
        }
        sp(ctx).edit().putString(KEY_LIST, arr.toString()).apply()
    }

    @Synchronized
    private fun seedNamazIfNeeded(ctx: Context) {
        val prefs = sp(ctx)
        if (prefs.getBoolean(KEY_SEEDED, false)) return
        prefs.edit().putBoolean(KEY_SEEDED, true).apply()
        val times = Prayer.times()
        val list = ArrayList<ReminderItem>()
        val tz = TimeZone.getTimeZone("Asia/Karachi")
        var id = prefs.getInt(KEY_NEXT, 100)
        for (name in listOf("Fajr", "Zuhr", "Asr", "Maghrib", "Isha")) {
            val ms = times[name] ?: continue
            val c = Calendar.getInstance(tz).apply { timeInMillis = ms }
            list.add(
                ReminderItem(
                    id = id++,
                    title = "$name Namaz",
                    hour = c.get(Calendar.HOUR_OF_DAY),
                    minute = c.get(Calendar.MINUTE),
                    repeatDaily = true,
                    enabled = true,
                    toneUri = "",
                    preAlertMinutes = 5,
                    isNamaz = true
                )
            )
        }
        prefs.edit().putInt(KEY_NEXT, id).apply()
        saveList(ctx, list)
        list.forEach { schedule(ctx, it) }
    }

    fun syncNamazFromSolar(ctx: Context) {
        val list = all(ctx).toMutableList()
        val times = Prayer.times()
        val tz = TimeZone.getTimeZone("Asia/Karachi")
        for (item in list) {
            if (!item.isNamaz) continue
            val key = item.title.substringBefore(" ").trim()
            val ms = times[key] ?: continue
            val c = Calendar.getInstance(tz).apply { timeInMillis = ms }
            item.hour = c.get(Calendar.HOUR_OF_DAY)
            item.minute = c.get(Calendar.MINUTE)
            if (item.enabled) schedule(ctx, item)
        }
        saveList(ctx, list)
    }

    @Synchronized
    fun upsert(ctx: Context, item: ReminderItem): ReminderItem {
        val list = all(ctx).toMutableList()
        val finalItem = if (item.id <= 0) {
            val id = sp(ctx).getInt(KEY_NEXT, 100)
            sp(ctx).edit().putInt(KEY_NEXT, id + 1).apply()
            item.copy(id = id)
        } else item

        val idx = list.indexOfFirst { it.id == finalItem.id }
        if (idx >= 0) list[idx] = finalItem else list.add(finalItem)

        if (finalItem.enabled) {
            finalItem.nextTriggerMs = computeNextTrigger(finalItem)
            schedule(ctx, finalItem)
        } else {
            cancel(ctx, finalItem.id)
        }
        saveList(ctx, list)
        return finalItem
    }

    @Synchronized
    fun delete(ctx: Context, id: Int) {
        cancel(ctx, id)
        saveList(ctx, all(ctx).filter { it.id != id })
    }

    fun find(ctx: Context, id: Int): ReminderItem? = all(ctx).firstOrNull { it.id == id }

    fun computeNextTrigger(item: ReminderItem): Long {
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, item.hour)
            set(Calendar.MINUTE, item.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.MINUTE, -item.preAlertMinutes.coerceAtLeast(0))
        }
        val now = System.currentTimeMillis()
        if (c.timeInMillis <= now + 1000L) {
            c.add(Calendar.DAY_OF_MONTH, 1)
        }
        return c.timeInMillis
    }

    private fun pi(ctx: Context, id: Int): PendingIntent = PendingIntent.getBroadcast(
        ctx,
        30_000 + id,
        Intent(ctx, AlarmReceiver::class.java).putExtra("reminder_id", id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun schedule(ctx: Context, item: ReminderItem) {
        if (!item.enabled) return
        val at = if (item.nextTriggerMs > System.currentTimeMillis()) item.nextTriggerMs else computeNextTrigger(item)
        item.nextTriggerMs = at
        val am = ctx.getSystemService(AlarmManager::class.java)
        try {
            val showPi = PendingIntent.getActivity(
                ctx,
                40_000 + item.id,
                Intent(ctx, RemindersActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, showPi), pi(ctx, item.id))
            return
        } catch (_: Exception) {}
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(ctx, item.id))
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(ctx, item.id))
            }
        } catch (_: Exception) {}
    }

    fun scheduleSnooze(ctx: Context, item: ReminderItem, minutes: Int) {
        val at = System.currentTimeMillis() + minutes * 60_000L
        val am = ctx.getSystemService(AlarmManager::class.java)
        val p = PendingIntent.getBroadcast(
            ctx,
            50_000 + item.id,
            Intent(ctx, AlarmReceiver::class.java).putExtra("reminder_id", item.id).putExtra("snooze", true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
            }
        } catch (_: Exception) {}
    }

    fun cancel(ctx: Context, id: Int) {
        try { ctx.getSystemService(AlarmManager::class.java).cancel(pi(ctx, id)) } catch (_: Exception) {}
    }

    fun rescheduleAll(ctx: Context) {
        val list = all(ctx)
        for (it in list) {
            if (it.enabled) {
                it.nextTriggerMs = computeNextTrigger(it)
                schedule(ctx, it)
            }
        }
        saveList(ctx, list)
    }
}
