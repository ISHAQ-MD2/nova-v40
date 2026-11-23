package com.nova.app

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * Reminders & Editable Namaz Alarms Manager:
 * - Add, edit, toggle, delete custom reminders (title, time, daily repeat, tone, pre-alert)
 * - Edit individual Namaz alarms (Fajr, Zuhr, Asr, Maghrib, Isha) + Sync DG Khan Solar times
 */
class RemindersActivity : Activity() {
    private lateinit var listLayout: LinearLayout
    private var accent = Color.parseColor("#00E5FF")
    private var pendingToneCallback: ((String) -> Unit)? = null
    private val REQ_TONE = 77

    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()
    private fun lp(w: Int, h: Int, wt: Float = 0f) = LinearLayout.LayoutParams(w, h, wt)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        accent = Themes.current(this).accent
        window.statusBarColor = Color.parseColor("#0B1220")
        window.navigationBarColor = Color.parseColor("#0B1220")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B1220"))
            fitsSystemWindows = true
        }
        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#141E33"))
            setPadding(dp(10), dp(10), dp(12), dp(10))
        }
        val back = Button(this).apply {
            text = "←"; setTextColor(Color.WHITE); setBackgroundColor(Color.TRANSPARENT)
            minimumWidth = dp(48); minimumHeight = dp(48); contentDescription = "Back"
            setOnClickListener { finish() }
        }
        val title = TextView(this).apply {
            text = "Reminders & Alarms"
            setTextColor(Color.WHITE); textSize = 19f; typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(6), 0, 0, 0)
        }
        val addBtn = Button(this).apply {
            text = "+ Add"; setTextColor(Color.BLACK); typeface = Typeface.DEFAULT_BOLD; textSize = 13f
            minimumHeight = dp(48)
            background = GradientDrawable().apply { setColor(accent); cornerRadius = dp(18).toFloat() }
            setOnClickListener { openEditDialog(null) }
        }
        top.addView(back, lp(dp(48), dp(48)))
        top.addView(title, lp(0, -2, 1f))
        top.addView(addBtn, lp(-2, dp(48)))
        root.addView(top, lp(-1, -2))

        listLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(24)) }
        root.addView(ScrollView(this).apply { addView(listLayout) }, lp(-1, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun fmtTime(h: Int, m: Int): String {
        val ampm = if (h >= 12) "PM" else "AM"
        val h12 = if (h % 12 == 0) 12 else h % 12
        return String.format(Locale.US, "%d:%02d %s", h12, m, ampm)
    }

    private fun render() {
        listLayout.removeAllViews()
        val all = ReminderStore.all(this)
        val custom = all.filter { !it.isNamaz }
        val namaz = all.filter { it.isNamaz }

        fun sectionHeader(txt: String) {
            listLayout.addView(TextView(this).apply {
                text = txt; setTextColor(accent); textSize = 13f; letterSpacing = 0.14f; typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(4), dp(10), dp(4), dp(6))
            })
        }

        sectionHeader("MY REMINDERS (${custom.size})")
        if (custom.isEmpty()) {
            listLayout.addView(TextView(this).apply {
                text = "No custom reminders. Tap + Add to create one."
                setTextColor(Color.parseColor("#8FA3BF")); textSize = 13f; setPadding(dp(6), dp(6), dp(6), dp(12))
            })
        } else {
            for (item in custom) addCard(item)
        }

        val namazRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        namazRow.addView(TextView(this).apply {
            text = "NAMAZ ALARMS (EDITABLE)"; setTextColor(accent); textSize = 13f; letterSpacing = 0.14f; typeface = Typeface.DEFAULT_BOLD
        }, lp(0, -2, 1f))
        namazRow.addView(Button(this).apply {
            text = "Sync Solar Times"; textSize = 11.5f; setTextColor(Color.WHITE); minimumHeight = dp(48)
            background = GradientDrawable().apply { setColor(Color.parseColor("#1E2D4D")); cornerRadius = dp(12).toFloat() }
            setOnClickListener {
                ReminderStore.syncNamazFromSolar(this@RemindersActivity)
                Toast.makeText(this@RemindersActivity, "Synced DG Khan prayer times", Toast.LENGTH_SHORT).show()
                render()
            }
        }, lp(-2, dp(48)))
        listLayout.addView(namazRow, lp(-1, -2).apply { topMargin = dp(10); bottomMargin = dp(6) })

        for (item in namaz) addCard(item)
    }

    private fun addCard(item: ReminderItem) {
        val card = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#141E33")); cornerRadius = dp(16).toFloat()
                setStroke(dp(1), if (item.enabled) accent else Color.parseColor("#2A3B5C"))
            }
            setOnClickListener { openEditDialog(item) }
        }
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info.addView(TextView(this).apply {
            text = fmtTime(item.hour, item.minute) + "  •  " + item.title
            setTextColor(Color.WHITE); textSize = 15.5f; typeface = Typeface.DEFAULT_BOLD
        })
        val sub = buildString {
            append(if (item.repeatDaily) "Daily" else "One-time")
            if (item.preAlertMinutes > 0) append(" • ${item.preAlertMinutes}m pre-alert")
            append(if (item.toneUri.isNotBlank()) " • Custom tone" else " • Default tone")
        }
        info.addView(TextView(this).apply {
            text = sub; setTextColor(Color.parseColor("#8FA3BF")); textSize = 12f; setPadding(0, dp(3), 0, 0)
        })
        val sw = Switch(this).apply {
            isChecked = item.enabled
            minimumWidth = dp(48); minimumHeight = dp(48)
            contentDescription = "Toggle ${item.title}"
            setOnCheckedChangeListener { _, checked ->
                item.enabled = checked
                ReminderStore.upsert(this@RemindersActivity, item)
                render()
            }
        }
        card.addView(info, lp(0, -2, 1f))
        card.addView(sw)
        listLayout.addView(card, lp(-1, -2).apply { bottomMargin = dp(8) })
    }

    private fun openEditDialog(existing: ReminderItem?) {
        var h = existing?.hour ?: 7
        var m = existing?.minute ?: 0
        var tone = existing?.toneUri ?: ""
        var preAlert = existing?.preAlertMinutes ?: 0

        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(10), dp(18), 0) }
        val titleEt = EditText(this).apply {
            hint = "Reminder Title"; setText(existing?.title ?: ""); setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
        }
        val timeBtn = Button(this).apply {
            text = "Time: ${fmtTime(h, m)} (Tap to change)"; setTextColor(Color.WHITE); minimumHeight = dp(48)
            background = GradientDrawable().apply { setColor(Color.parseColor("#1F2B45")); cornerRadius = dp(12).toFloat() }
            setOnClickListener {
                TimePickerDialog(this@RemindersActivity, { _, hh, mm ->
                    h = hh; m = mm; text = "Time: ${fmtTime(h, m)} (Tap to change)"
                }, h, m, false).show()
            }
        }
        val repCb = CheckBox(this).apply {
            text = "Repeat Daily"; setTextColor(Color.WHITE); isChecked = existing?.repeatDaily ?: true
            minimumHeight = dp(48)
        }
        val preBtn = Button(this).apply {
            text = "Pre-alert: ${preAlert} min before"; setTextColor(Color.WHITE); minimumHeight = dp(48)
            background = GradientDrawable().apply { setColor(Color.parseColor("#1F2B45")); cornerRadius = dp(12).toFloat() }
            setOnClickListener {
                val opts = arrayOf("0 min", "5 min", "10 min", "15 min", "30 min")
                val vals = intArrayOf(0, 5, 10, 15, 30)
                AlertDialog.Builder(this@RemindersActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Pre-Alert").setItems(opts) { _, i ->
                        preAlert = vals[i]; text = "Pre-alert: ${preAlert} min before"
                    }.show()
            }
        }
        val toneBtn = Button(this).apply {
            text = if (tone.isBlank()) "Tone: Default Alarm (Tap to pick)" else "Tone: Custom Selected (Tap to pick)"
            setTextColor(Color.WHITE); minimumHeight = dp(48)
            background = GradientDrawable().apply { setColor(Color.parseColor("#1F2B45")); cornerRadius = dp(12).toFloat() }
            setOnClickListener {
                pendingToneCallback = { uri ->
                    tone = uri
                    text = if (tone.isBlank()) "Tone: Default Alarm" else "Tone: Custom Selected"
                }
                val i = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                if (tone.isNotBlank()) i.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(tone))
                try { startActivityForResult(i, REQ_TONE) } catch (_: Exception) {}
            }
        }

        box.addView(titleEt)
        box.addView(timeBtn, lp(-1, dp(48)).apply { topMargin = dp(8) })
        box.addView(repCb, lp(-1, dp(48)).apply { topMargin = dp(6) })
        box.addView(preBtn, lp(-1, dp(48)).apply { topMargin = dp(6) })
        box.addView(toneBtn, lp(-1, dp(48)).apply { topMargin = dp(8) })

        val dlg = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(if (existing == null) "New Reminder" else "Edit ${existing.title}")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val item = (existing ?: ReminderItem(0, "", h, m)).copy(
                    title = titleEt.text.toString().trim().ifBlank { "Reminder" },
                    hour = h,
                    minute = m,
                    repeatDaily = repCb.isChecked,
                    enabled = true,
                    toneUri = tone,
                    preAlertMinutes = preAlert
                )
                ReminderStore.upsert(this, item)
                render()
            }
            .setNegativeButton("Cancel", null)
        if (existing != null && !existing.isNamaz) {
            dlg.setNeutralButton("Delete") { _, _ ->
                ReminderStore.delete(this, existing.id)
                render()
            }
        }
        dlg.show()
    }

    @Deprecated("simple")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_TONE && resultCode == RESULT_OK) {
            val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) {
                data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            }
            pendingToneCallback?.invoke(uri?.toString() ?: "")
            pendingToneCallback = null
        }
    }
}
