package com.nova.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.app.AlarmManager
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.widget.*

class SetupActivity : Activity() {
    companion object {
        fun accessibilityOn(ctx: Context): Boolean {
            val en = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return en.contains("${ctx.packageName}/${NovaAccessibilityService::class.java.name}")
        }
    }

    private lateinit var box: LinearLayout
    private val rows = mutableListOf<Triple<TextView, () -> Boolean, String>>()

    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()
    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    private fun filesOk(): Boolean {
        val media = if (Build.VERSION.SDK_INT >= 33) granted(Manifest.permission.READ_MEDIA_IMAGES) else granted(Manifest.permission.READ_EXTERNAL_STORAGE)
        val all = Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()
        return media && all
    }
    private fun alarmOk() = Build.VERSION.SDK_INT < 31 || getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val sv = ScrollView(this); sv.setBackgroundColor(Color.parseColor("#05070D"))
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(32)) }
        sv.addView(box); setContentView(sv)
        val prefs = getSharedPreferences("nova", MODE_PRIVATE)

        head("1) API key aur naam")
        val key = edit("Gemini API key", password = true)
        key.hint = if ((prefs.getString("k", "") ?: "").isEmpty()) "Gemini API key" else "saved - naya daalna ho to likho"
        val name = edit("Aapka naam (Boss)"); name.setText(prefs.getString("u", "") ?: "")
        btn("💾 Save") {
            val e = prefs.edit().putString("u", name.text.toString().trim())
            val k = key.text.toString().trim()
            if (k.isNotEmpty()) e.putString("k", Crypto.enc(k))
            e.apply(); Backup.saveProfile(this)
            Toast.makeText(this, "Saved - yaaddaasht, reminders aur contacts wahin hain, kuch nahi bhoola", Toast.LENGTH_LONG).show(); key.setText("")
        }

        btn("🌐 Internet / Google server test") {
            Thread {
                val msg = try {
                    val r = Net.client().newCall(okhttp3.Request.Builder().url("https://generativelanguage.googleapis.com/").build()).execute()
                    val code = r.code; r.close()
                    "Google server mil gaya (HTTP $code) - sab theek"
                } catch (e: Exception) { Net.friendly(this, e.message ?: "error") }
                runOnUiThread { AlertDialog.Builder(this).setMessage(msg).setPositiveButton("OK", null).show() }
            }.start()
        }
        val ev = edit("Model (optional, khali chhodo)"); ev.setText(prefs.getString("model", "") ?: "")
        btn("Model save") { prefs.edit().putString("model", ev.text.toString().trim()).apply(); Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show() }

        head("2) Permissions (sab zaroori hain)")
        row("Mic, Notifications, Contacts, Call",
            { granted(Manifest.permission.RECORD_AUDIO) && granted(Manifest.permission.READ_CONTACTS) && granted(Manifest.permission.CALL_PHONE) && granted(Manifest.permission.READ_CALENDAR) &&
                (Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)) }, "Allow karo") {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE, Manifest.permission.READ_CALENDAR), 2)
        }
        row("Files / Gallery access (photo, video, Download folder bhejne ke liye)", { filesOk() }, "Allow karo") {
            val need = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
                else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            requestPermissions(need, 3)
            if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager())
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        }
        row("Exact alarm (reminder theek waqt par)", { alarmOk() }, "Allow karo") {
            if (Build.VERSION.SDK_INT >= 31) startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
        }
        row("Termux permission (command chalane ke liye)", { granted("com.termux.permission.RUN_COMMAND") }, "Allow karo") {
            requestPermissions(arrayOf("com.termux.permission.RUN_COMMAND"), 4)
        }
        row("Accessibility (screen touch, click, type)", { accessibilityOn(this) }, "Accessibility kholo") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Installed apps > NOVA Control > ON", Toast.LENGTH_LONG).show()
        }
        row("Display over other apps (YouTube/apps kholne ke liye)", { Settings.canDrawOverlays(this) }, "Overlay ON karo") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        row("Battery unrestricted (background mein chalne ke liye)",
            { getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName) }, "Allow karo") {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }

        text(if (Backup.usable()) "✅ Backup folder /sdcard/NOVA chalu hai - reinstall par bhi memory wapas aayegi" else "❌ Files access dein warna reinstall par memory ud jayegi (upar 'Files / Gallery access')")
        head("3) Description, dost ke numbers, yaaddaasht, reminders")
        btn("📒 Profile / Description / Memory") { startActivity(Intent(this, ProfileActivity::class.java)) }
        head("💾 Backup / Export / Import")
        text("Yaaddaasht (pasand, dost, notes), description, reminders aur settings ek file mein. Naye app mein Import karo to NOVA ko sab wapas yaad aa jayega. API key file mein nahi jati.")
        btn("📤 Backup banao (file save karo)") { exportBackup() }
        btn("📥 Import (backup file se wapas lao)") { importBackup() }
        head("4) Termux ek baar setup")
        text("Termux mein chalao:\nmkdir -p ~/.termux && echo \"allow-external-apps=true\" >> ~/.termux/termux.properties\nphir Termux band karke dobara kholo, aur upar Termux permission Allow karo.")
        head("5) Agar Accessibility grey/band ho")
        text("Android 13+ par sideload app ke liye: App info kholo > upar 3 dots > \"Allow restricted settings\", phir Accessibility mein NOVA Control ON karo.")
        btn("App info kholo") { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
    }

    private fun exportBackup() {
        val name = "NOVA-backup-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date()) + ".json"
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json").putExtra(Intent.EXTRA_TITLE, name), 701)
    }
    private fun importBackup() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 702)
    }
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val uri = data?.data ?: return
        if (res != RESULT_OK) return
        try {
            if (req == 701) {
                contentResolver.openOutputStream(uri)?.use { it.write(Backup.export(this).toByteArray()) }
                Toast.makeText(this, "✅ Backup save ho gaya", Toast.LENGTH_LONG).show()
            } else if (req == 702) {
                val t = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                Toast.makeText(this, "✅ " + Backup.importFrom(this, t), Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) { Toast.makeText(this, "❌ " + (e.message ?: "error"), Toast.LENGTH_LONG).show() }
    }

    override fun onResume() { super.onResume(); refresh() }
    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) { super.onRequestPermissionsResult(c, p, r); refresh() }

    private fun refresh() { rows.forEach { (tv, ok, label) -> tv.text = (if (ok()) "✅ " else "❌ ") + label } }

    private fun head(t: String) = box.addView(TextView(this).apply { text = t; textSize = 18f; setTextColor(Color.parseColor("#00E5FF")); setPadding(0, dp(20), 0, dp(6)) })
    private fun text(t: String) = box.addView(TextView(this).apply { text = t; setTextColor(Color.LTGRAY) })
    private fun btn(t: String, a: () -> Unit) = box.addView(Button(this).apply { text = t; setOnClickListener { a() } })
    private fun edit(h: String, password: Boolean = false): EditText {
        val e = EditText(this).apply {
            hint = h; setHintTextColor(Color.GRAY); setTextColor(Color.WHITE); setSingleLine()
            if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        box.addView(e); return e
    }
    private fun row(label: String, ok: () -> Boolean, btnText: String, action: () -> Unit) {
        val tv = TextView(this).apply { setTextColor(Color.WHITE); setPadding(0, dp(10), 0, 0) }
        box.addView(tv); rows.add(Triple(tv, ok, label)); btn(btnText, action)
    }
}
