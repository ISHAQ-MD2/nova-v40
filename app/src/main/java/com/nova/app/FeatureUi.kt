package com.nova.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Handler
import android.text.InputType
import android.widget.EditText
import android.widget.TextView

/**
 * Settings Panel feature rows for Browser, Offline Voices, Reminders/Alarms, Downloads, and Code Backups.
 */
object FeatureUi {
    private fun prefs(ctx: Activity) = ctx.getSharedPreferences("nova", android.content.Context.MODE_PRIVATE)
    private fun flag(ctx: Activity, key: String, def: Boolean) = prefs(ctx).getBoolean(key, def)
    private fun flip(ctx: Activity, key: String, def: Boolean, refresh: () -> Unit) {
        prefs(ctx).edit().putBoolean(key, !flag(ctx, key, def)).apply()
        refresh()
    }

    fun items(ctx: Activity, status: TextView, h: Handler, refresh: () -> Unit): List<SItem> = listOf(
        SItem("vol_up", "Offline Voice Selector", {
            val v = VoiceEngine.selected(ctx)
            "${v.name} (${v.gender}, offline ready)"
        }, null, { offlineVoiceDialog(ctx, status, refresh) }),

        SItem("download", "Voice Pack Manager (5F + 1M)", {
            val inst = VoiceEngine.all(ctx).count { it.installed }
            "$inst / ${VoiceEngine.all(ctx).size} voices installed in filesDir/voices/"
        }, null, { ctx.startActivity(Intent(ctx, VoicePackActivity::class.java)) }),

        SItem("bell", "Reminders & Namaz Alarms", {
            val count = ReminderStore.all(ctx).count { it.enabled }
            "$count active alarms & customizable Namaz alerts"
        }, null, { ctx.startActivity(Intent(ctx, RemindersActivity::class.java)) }),

        SItem("download", "Downloads Manager (MP4/HLS)", {
            "${DownloadVault.tasks.size} items • ${DownloadVault.outputDir(ctx).path}"
        }, null, { ctx.startActivity(Intent(ctx, DownloadsActivity::class.java)) }),

        SItem("folder", "Code Backups & Export Zips", {
            "View AI-generated code projects & export .zip files"
        }, null, { ctx.startActivity(Intent(ctx, BackupsActivity::class.java)) }),

        SItem("grid", "Open NOVA Browser", {
            NovaBrowser.profileLabel() + " | " + NovaBrowser.tabs.size + " tab(s) | Persistent logins"
        }, null, { NovaBrowser.openUi(ctx) }),

        SItem("bolt", "Browser AdBlock", { "Block ad/tracker hosts in NOVA Browser" },
            { flag(ctx, "nb_adblock", true) }, { flip(ctx, "nb_adblock", true, refresh) }),

        SItem("moon", "Browser Night Mode", { "Dark theme rendering for web pages" },
            { flag(ctx, "nb_night", false) }, {
                NovaBrowser.init(ctx)
                NovaBrowser.setNightMode(!flag(ctx, "nb_night", false))
                refresh()
            }),

        SItem("cloud", "Open links in NOVA Browser", { "Route open_url & web_search inside NOVA Browser" },
            { flag(ctx, "nb_open", true) }, { flip(ctx, "nb_open", true, refresh) }),

        SItem("music", "YouTube inside NOVA Browser", { "Play YouTube web with background/floating window" },
            { NovaBrowser.ytInBrowser(ctx) }, { flip(ctx, "nb_yt", true, refresh) }),

        SItem("bolt", "Auto-skip video ads", { "Skip player ad button automatically" },
            { flag(ctx, "adskip", true) }, { flip(ctx, "adskip", true, refresh) }),

        SItem("grid", "Floating browser window", {
            if (!NovaBrowser.overlayOk()) "Enable 'Display over other apps' first" else "Show browser overlay above other apps"
        }, { flag(ctx, "nb_float", false) }, { status.text = NovaBrowser.setFloatPref(ctx, !flag(ctx, "nb_float", false)); refresh() }),

        SItem("lock", "Display over other apps", {
            if (NovaBrowser.overlayOk()) "ON ✅" else "OFF — tap to allow overlay"
        }, null, {
            try { ctx.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + ctx.packageName))) } catch (_: Exception) {}
        }),

        SItem("person", "Browser profile", { NovaBrowser.profileLabel() }, null, { browserProfileDialog(ctx, status, refresh) }),

        SItem("image", "Show browser mirror on wallpaper", { "Live miniature browser preview on live wallpaper" },
            { HackerWall.on(ctx, "browser") }, { prefs(ctx).edit().putBoolean("wpw_browser", !HackerWall.on(ctx, "browser")).apply(); refresh() }),

        SItem("lock", "Clear browser logins & cookies", { "Manual logout & wipe all saved passwords/history" }, null, {
            AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert).setMessage("Clear all browser cookies, logins, passwords, and history?")
                .setPositiveButton("Clear") { _, _ -> NovaBrowser.init(ctx); NovaBrowser.clearAllData(); status.text = "Browser data cleared" }
                .setNegativeButton("Cancel", null).show()
        }),

        SItem("grid", "Live HUD wallpaper layers", {
            val on = HackerWall.KEYS.count { HackerWall.on(ctx, it.first) }
            "$on / ${HackerWall.KEYS.size} HUD elements active"
        }, null, { wallpaperWidgetsDialog(ctx, refresh) }),

        SItem("clock", "Wallpaper clock style", {
            arrayOf("Grid", "Large Center", "Top-Left", "Top-Right", "Off")[HackerWall.clockStyle(ctx)]
        }, null, { clockStyleDialog(ctx, refresh) }),

        SItem("mic", "WhatsApp voice mode", {
            if (voiceMode(ctx) == "tts") "AI voice note file" else "User speaks via mic hold"
        }, null, { voiceModeDialog(ctx, status, refresh) }),

        SItem("bolt", "Default Code AI Provider", {
            "Default: " + (if (prefs(ctx).getString("ai_default", "deepseek") == "claude") "Claude" else "DeepSeek") + " (DeepSeek, ChatGPT, Claude, Gemini, Grok supported)"
        }, null, { aiDefaultDialog(ctx, refresh) }),

        SItem("cloud", "DeepSeek API key (optional)", {
            if (DeepSeekRest.hasKey(ctx)) "Configured ✅" else "Not set (uses WebView routing)"
        }, null, { deepSeekKeyDialog(ctx, status, refresh) }),

        SItem("cloud", "2nd Gemini API key (fallback)", {
            if (Keys.hasSecond(ctx)) "Configured ✅" else "Optional fallback on quota 429"
        }, null, { secondKeyDialog(ctx, status, refresh) }),

        SItem("download", "Clipboard link watcher", { "Detect copied TikTok/Instagram/MP4/HLS links" },
            { prefs(ctx).getBoolean("clipwatch", true) },
            { prefs(ctx).edit().putBoolean("clipwatch", !prefs(ctx).getBoolean("clipwatch", true)).apply(); ClipWatch.autoStart(ctx); refresh() })
    )

    private fun offlineVoiceDialog(ctx: Activity, status: TextView, refresh: () -> Unit) {
        val voices = VoiceEngine.all(ctx).filter { it.installed }
        val curId = VoiceEngine.selectedId(ctx)
        val curIdx = voices.indexOfFirst { it.id == curId }.coerceAtLeast(0)
        val labels = voices.map { "${it.name} (${it.gender}) — ${it.description.take(38)}" }.toTypedArray()
        AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Select Offline Voice")
            .setSingleChoiceItems(labels, curIdx) { d, which ->
                val v = voices[which]
                VoiceEngine.select(ctx, v.id)
                status.text = "Voice selected: ${v.name}"
                d.dismiss()
                refresh()
            }
            .setPositiveButton("Manage Packs") { _, _ -> ctx.startActivity(Intent(ctx, VoicePackActivity::class.java)) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun browserProfileDialog(ctx: Activity, status: TextView, refresh: () -> Unit) {
        NovaBrowser.init(ctx)
        val opts = arrayOf("Personal Profile (your accounts)", "Nova Profile (separate accounts)")
        AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Browser Profile")
            .setSingleChoiceItems(opts, if (NovaBrowser.profileName() == "nova") 1 else 0) { d, which ->
                status.text = NovaBrowser.setProfile(if (which == 1) "nova" else "me")
                refresh(); d.dismiss()
            }.setNegativeButton("Close", null).show()
    }

    private fun voiceMode(ctx: Activity) = prefs(ctx).getString("voice_mode", "real") ?: "real"

    private fun clockStyleDialog(ctx: Activity, refresh: () -> Unit) {
        val opts = arrayOf("Grid", "Large Center", "Top-Left", "Top-Right", "Off")
        AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Wallpaper Clock Style")
            .setSingleChoiceItems(opts, HackerWall.clockStyle(ctx)) { d, which ->
                prefs(ctx).edit().putInt("wp_clockstyle", which).apply()
                refresh(); d.dismiss()
            }.setNegativeButton("Close", null).show()
    }

    private fun wallpaperWidgetsDialog(ctx: Activity, refresh: () -> Unit) {
        val keys = HackerWall.KEYS
        val labels = keys.map { it.second }.toTypedArray()
        val checked = keys.map { HackerWall.on(ctx, it.first) }.toBooleanArray()
        AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Live Wallpaper HUD Elements")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                prefs(ctx).edit().putBoolean("wpw_${keys[which].first}", isChecked).apply()
            }
            .setPositiveButton("Save") { _, _ -> refresh() }
            .show()
    }

    private fun voiceModeDialog(ctx: Activity, status: TextView, refresh: () -> Unit) {
        val opts = arrayOf("Real voice (mic hold)", "AI voice file (TTS)")
        val cur = if (voiceMode(ctx) == "tts") 1 else 0
        AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("WhatsApp Voice Message Mode")
            .setSingleChoiceItems(opts, cur) { d, which ->
                prefs(ctx).edit().putString("voice_mode", if (which == 1) "tts" else "real").apply()
                status.text = "Voice mode updated"
                refresh(); d.dismiss()
            }.setNegativeButton("Close", null).show()
    }

    private fun aiDefaultDialog(ctx: Activity, refresh: () -> Unit) {
        val opts = arrayOf("DeepSeek (default)", "Claude")
        val cur = if (prefs(ctx).getString("ai_default", "deepseek") == "claude") 1 else 0
        AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Default Code AI Provider")
            .setSingleChoiceItems(opts, cur) { d, which ->
                prefs(ctx).edit().putString("ai_default", if (which == 1) "claude" else "deepseek").apply()
                refresh(); d.dismiss()
            }.setNegativeButton("Close", null).show()
    }

    private fun deepSeekKeyDialog(ctx: Activity, status: TextView, refresh: () -> Unit) {
        val box = EditText(ctx).apply { hint = "DeepSeek API key"; setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("DeepSeek API Key")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val k = box.text.toString().trim()
                DeepSeekRest.saveKey(ctx, k)
                status.text = if (k.isNotEmpty()) "DeepSeek API key saved" else "DeepSeek API key cleared"
                refresh()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun secondKeyDialog(ctx: Activity, status: TextView, refresh: () -> Unit) {
        val box = EditText(ctx).apply { hint = "2nd Gemini API key"; setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("2nd Gemini API Key")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val k = box.text.toString().trim()
                if (k.isNotEmpty()) { Keys.saveSecond(ctx, k); status.text = "2nd API key saved" }
                refresh()
            }.setNegativeButton("Cancel", null).show()
    }
}
