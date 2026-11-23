package com.nova.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val id = i.getIntExtra("reminder_id", -1)
        val isSnooze = i.getBooleanExtra("snooze", false)
        val item = ReminderStore.find(ctx, id) ?: return

        if (!isSnooze) {
            if (item.repeatDaily) {
                item.nextTriggerMs = ReminderStore.computeNextTrigger(item)
                ReminderStore.upsert(ctx, item)
            } else {
                item.enabled = false
                ReminderStore.upsert(ctx, item)
            }
        }

        Unlocker.wake(ctx, 45_000L)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("nova_alarm_full", "NOVA Alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )

        val actIntent = Intent(ctx, AlarmActivity::class.java)
            .putExtra("reminder_id", item.id)
            .putExtra("title", item.title)
            .putExtra("toneUri", item.toneUri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        val fsPi = PendingIntent.getActivity(ctx, 60_000 + item.id, actIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notif = Notification.Builder(ctx, "nova_alarm_full")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(if (item.isNamaz) "🕌 ${item.title}" else "⏰ ${item.title}")
            .setContentText(if (item.preAlertMinutes > 0) "${item.preAlertMinutes}m pre-alert • Tap to open alarm" else "Time reached • Tap to open")
            .setFullScreenIntent(fsPi, true)
            .setContentIntent(fsPi)
            .setAutoCancel(true)
            .build()
        nm.notify(70_000 + item.id, notif)

        try { ctx.startActivity(actIntent) } catch (_: Exception) {}
        RobotPet.say(ctx, "Sir, ${item.title} reminder!", 10_000L)
    }
}

/**
 * Full-screen lockscreen-capable Alarm & Namaz Alert Activity with Stop, Snooze (5/10/15m), and Dismiss.
 */
class AlarmActivity : Activity() {
    private var mp: MediaPlayer? = null
    private var vib: Vibrator? = null
    private var reminderId = -1
    private val h = Handler(Looper.getMainLooper())
    private lateinit var clockTv: TextView
    private val tick = object : Runnable {
        override fun run() {
            clockTv.text = SimpleDateFormat("hh:mm a", Locale.US).format(Date())
            h.postDelayed(this, 1000L)
        }
    }

    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()
    private fun lp(w: Int, h: Int) = LinearLayout.LayoutParams(w, h)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.parseColor("#060914")
        window.navigationBarColor = Color.parseColor("#060914")

        reminderId = intent.getIntExtra("reminder_id", -1)
        val title = intent.getStringExtra("title") ?: "NOVA Reminder"
        val toneUri = intent.getStringExtra("toneUri") ?: ""
        val accent = Themes.current(this).accent

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#060914"))
            setPadding(dp(24), dp(40), dp(24), dp(40))
        }

        root.addView(TextView(this).apply {
            text = "NOVA ALARM"
            setTextColor(accent); textSize = 14f; letterSpacing = 0.25f; gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
        })

        clockTv = TextView(this).apply {
            text = SimpleDateFormat("hh:mm a", Locale.US).format(Date())
            setTextColor(Color.WHITE); textSize = 54f; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(12), 0, dp(10))
        }
        root.addView(clockTv)

        root.addView(TextView(this).apply {
            text = title
            setTextColor(Color.parseColor("#E3E9F2")); textSize = 24f; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(36))
        })

        fun actionBtn(label: String, bgHex: String, fgColor: Int = Color.BLACK, onClick: () -> Unit): Button = Button(this).apply {
            text = label; textSize = 15f; setTextColor(fgColor); typeface = Typeface.DEFAULT_BOLD
            minimumHeight = dp(52)
            background = GradientDrawable().apply { setColor(Color.parseColor(bgHex)); cornerRadius = dp(24).toFloat() }
            setOnClickListener { onClick() }
        }

        root.addView(actionBtn("STOP ALARM", "#FF3B56", Color.WHITE) {
            stopMedia()
            finish()
        }, lp(-1, dp(54)).apply { bottomMargin = dp(16) })

        val snoozeRow = LinearLayout(this).apply { gravity = Gravity.CENTER }
        for (mins in intArrayOf(5, 10, 15)) {
            val b = actionBtn("Snooze ${mins}m", "#1E2D4D", Color.WHITE) {
                stopMedia()
                ReminderStore.find(this, reminderId)?.let { ReminderStore.scheduleSnooze(this, it, mins) }
                Toast.makeText(this, "Snoozed for $mins minutes", Toast.LENGTH_SHORT).show()
                finish()
            }
            snoozeRow.addView(b, LinearLayout.LayoutParams(0, dp(50), 1f).apply {
                if (mins != 15) rightMargin = dp(8)
            })
        }
        root.addView(snoozeRow, lp(-1, -2).apply { bottomMargin = dp(16) })

        root.addView(actionBtn("DISMISS", "#00E5FF", Color.BLACK) {
            stopMedia()
            finish()
        }, lp(-1, dp(52)))

        setContentView(root)
        h.post(tick)
        startToneAndVibrate(toneUri)
    }

    private fun startToneAndVibrate(toneStr: String) {
        try {
            val uri = if (toneStr.isNotBlank()) Uri.parse(toneStr)
            else RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            if (uri != null) {
                mp = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    setDataSource(this@AlarmActivity, uri)
                    isLooping = true
                    prepare()
                    start()
                }
            }
        } catch (_: Exception) {}

        try {
            @Suppress("DEPRECATION")
            val v = getSystemService(VIBRATOR_SERVICE) as? Vibrator
            vib = v
            val pattern = longArrayOf(0, 600, 400, 600, 800)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                v?.vibrate(pattern, 0)
            }
        } catch (_: Exception) {}
    }

    private fun stopMedia() {
        try { mp?.stop(); mp?.release() } catch (_: Exception) {}
        mp = null
        try { vib?.cancel() } catch (_: Exception) {}
        try { getSystemService(NotificationManager::class.java).cancel(70_000 + reminderId) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        h.removeCallbacks(tick)
        stopMedia()
        super.onDestroy()
    }
}
