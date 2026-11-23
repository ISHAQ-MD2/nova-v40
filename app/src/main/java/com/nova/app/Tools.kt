package com.nova.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/** Gemini function-calling tools: phone control + WhatsApp + reminders + memory + files + Gemini + Termux. */
object Tools {
    private fun s(d: String) = JSONObject().put("type", "STRING").put("description", d)
    private fun n(d: String) = JSONObject().put("type", "NUMBER").put("description", d)
    private fun b(d: String) = JSONObject().put("type", "BOOLEAN").put("description", d)
    private fun props(vararg p: Pair<String, JSONObject>): JSONObject { val o = JSONObject(); p.forEach { o.put(it.first, it.second) }; return o }
    private var blocking = false
    private fun fn(name: String, desc: String, p: JSONObject = JSONObject(), req: List<String> = emptyList()): JSONObject {
        val o = JSONObject().put("name", name).put("description", desc)
        if (blocking) o.put("behavior", "BLOCKING")
        if (p.length() > 0) o.put("parameters", JSONObject().put("type", "OBJECT").put("properties", p).put("required", JSONArray(req)))
        return o
    }

    /** LiveClient yahan apna "image bhejo" function lagata hai (look_screen / look_photo ke liye). */
    @Volatile var imageSink: ((ByteArray) -> Unit)? = null

    fun declarations(blocking: Boolean = false): JSONArray { this.blocking = blocking; return build() }

    private const val WHO = "Contact name (as saved / in the user's contact book) or a phone number"
    private const val BIZ = "true if the user said WhatsApp Business"

    private fun build(): JSONArray = JSONArray()
        .put(fn("open_app", "Open an installed app by name (YouTube, WhatsApp, WhatsApp Business, Chrome, Gemini, Termux, Play Store, Settings, Camera, Gallery...).", props("name" to s("App name")), listOf("name")))
        .put(fn("youtube_search", "Open YouTube search results for a query (real search).", props("query" to s("Search text"), "browser" to b("true to open in Chrome instead of the YouTube app")), listOf("query")))
        .put(fn("web_search", "Search Google in Chrome.", props("query" to s("Search text")), listOf("query")))
        .put(fn("open_url", "Open a website/link in Chrome. Domain names like 'facebook.com' are fine.", props("url" to s("URL or domain")), listOf("url")))
        .put(fn("open_in_chrome", "Explicitly open a URL or Google search query in Google Chrome (Custom Tabs).", props("url" to s("URL or domain to open"), "query" to s("Search query if no URL is provided"))))
        .put(fn("read_screen", "Read what is on screen now: labels, [clickable] flags and x,y coordinates. Use before tapping."))
        .put(fn("read_screen_text", "Read all visible text on screen (long text such as a reply or article)."))
        .put(fn("click_text", "Click a button/item on screen by its visible text or description.", props("text" to s("Visible text")), listOf("text")))
        .put(fn("tap", "Tap the screen at pixel coordinates.", props("x" to n("x pixel"), "y" to n("y pixel")), listOf("x", "y")))
        .put(fn("swipe", "Swipe on the screen.", props("x1" to n("start x"), "y1" to n("start y"), "x2" to n("end x"), "y2" to n("end y"), "duration_ms" to n("duration, default 300")), listOf("x1", "y1", "x2", "y2")))
        .put(fn("scroll", "Scroll the current screen.", props("direction" to s("'down' or 'up'")), listOf("direction")))
        .put(fn("type_text", "Type text into the focused text field.", props("text" to s("Text to type")), listOf("text")))
        .put(fn("press", "Press a system button.", props("button" to s("back, home, recents, notifications, lock or screenshot")), listOf("button")))
        .put(fn("call", "Phone-call a contact by name or number.", props("who" to s(WHO)), listOf("who")))
        .put(fn("find_contact", "Look up a person's number in the user's contact book and phone contacts.", props("name" to s("Name")), listOf("name")))
        .put(fn("whatsapp_send", "Send a WhatsApp message to a person and press Send automatically.", props("who" to s(WHO), "message" to s("Message text"), "business" to b(BIZ)), listOf("who", "message")))
        .put(fn("whatsapp_call", "Start a WhatsApp voice or video call with a person.", props("who" to s(WHO), "video" to b("true for video call"), "business" to b(BIZ)), listOf("who")))
        .put(fn("send_media", "Send a photo/video/audio from the gallery or a file from the Downloads folder to a person on WhatsApp.",
            props("who" to s(WHO), "kind" to s("photo, video, audio or file"), "query" to s("Part of the file name; empty = latest item"), "caption" to s("Optional caption"), "business" to b(BIZ)), listOf("who", "kind")))
        .put(fn("find_files", "List recent gallery items or Download-folder files matching a name.", props("kind" to s("photo, video, audio or file"), "query" to s("Part of the file name, may be empty")), listOf("kind")))
        .put(fn("whatsapp_status", "Post a photo/video from the gallery as WhatsApp status.", props("kind" to s("photo or video"), "query" to s("Part of file name; empty = latest"), "business" to b(BIZ)), listOf("kind")))
        .put(fn("ask_gemini", "Open the Gemini app, type a prompt, wait and return Gemini's reply text.", props("prompt" to s("What to ask Gemini")), listOf("prompt")))
        .put(fn("termux_run", "Run a shell command in Termux and get its output/error.", props("command" to s("Shell command"), "visible" to b("true to show the Termux window")), listOf("command")))
        .put(fn("copy_to_clipboard", "Copy text to the phone clipboard.", props("text" to s("Text")), listOf("text")))
        .put(fn("look_screen", "Take a screenshot and show it to you so you can describe what is on screen."))
        .put(fn("look_photo", "Show you a photo from the gallery so you can describe it.", props("query" to s("Part of file name; empty = latest photo"))))
        .put(fn("remember", "Save an important fact/note to long-term memory.", props("note" to s("The note")), listOf("note")))
        .put(fn("recall", "Search long-term memory and past conversations/actions.", props("query" to s("Keywords, e.g. a person's name; empty = latest items")), listOf("query")))
        .put(fn("schedule_task", "Schedule a task/reminder to run later by itself. Provide time (HH:mm 24h) with optional date, or in_minutes.",
            props("instruction" to s("Complete self-contained instruction to perform at that time"), "time" to s("HH:mm 24-hour"), "date" to s("yyyy-MM-dd, optional"),
                "in_minutes" to n("Minutes from now (instead of time)"), "repeat_daily" to b("true ONLY if the user explicitly said every day / roz / daily; otherwise omit - a reminder fires exactly ONCE")), listOf("instruction")))
        .put(fn("list_tasks", "List scheduled tasks."))
        .put(fn("cancel_task", "Cancel a scheduled task by id.", props("id" to n("Task id")), listOf("id")))
        .put(fn("notify_user", "Show a notification and speak a short reminder text.", props("text" to s("Text")), listOf("text")))
        .put(fn("play_store_install", "Open Play Store for an app and press Install (if package known) or open the search.", props("name" to s("App name"), "package" to s("Android package id, if sure")), listOf("name")))
        .put(fn("get_time", "Get the current date and time."))
        .put(fn("volume", "Change media volume.", props("direction" to s("'up' or 'down'")), listOf("direction")))
        .put(fn("set_alarm", "Set a normal clock alarm on the phone.", props("hour" to n("0-23"), "minute" to n("0-59"), "label" to s("Alarm label")), listOf("hour", "minute")))
        .put(fn("set_timer", "Start a countdown timer in the clock app.", props("seconds" to n("Duration in seconds"), "label" to s("Label")), listOf("seconds")))
        .put(fn("open_settings", "Open a phone settings page.", props("page" to s("wifi, bluetooth, battery, display, sound, location, apps, accessibility, date, airplane, private_dns, storage or main")), listOf("page")))
        .put(fn("navigate", "Open Google Maps directions/search for a place.", props("place" to s("Destination or place to search")), listOf("place")))
        .put(fn("fetch_page", "Download a web page/article and return its plain text so you can summarize or translate it.", props("url" to s("Full URL")), listOf("url")))
        .put(fn("get_news", "Get the latest headlines (news, sports, stocks, any topic) from Google News.", props("topic" to s("Topic, e.g. cricket, PSX, technology; empty = top news"), "lang" to s("Language code like en or ur, default en")), listOf("topic")))
        .put(fn("read_clipboard", "Read the text currently in the phone clipboard."))
        .put(fn("hold_tap", "Press and HOLD one spot on the screen for N seconds (fire button, record button, slider). Give x_pct,y_pct as fractions 0..1 of screen width/height (estimate from look_screen) or pixel x,y.", props("x_pct" to n("0..1 fraction of width"), "y_pct" to n("0..1 fraction of height"), "x" to n("pixel x"), "y" to n("pixel y"), "seconds" to n("1-60, default 5")), listOf("seconds")))
        .put(fn("skip_ad", "Click Skip / Skip ad / Close ad on the ad currently on screen."))
        .put(fn("show_emotion", "Make NOVA's 3D face show an emotion (happy, angry, sad, surprised, love, neutral) when you sense the user's mood or react to what they said.", props("emotion" to s("happy|angry|sad|surprised|love|neutral")), listOf("emotion")))
        .put(fn("calendar_events", "Read the user's calendar events for the next N days.", props("days" to n("default 7"))))
        .put(fn("calendar_add", "Open the Calendar app with a new event filled in (user presses Save).", props("title" to s("Event title"), "start" to s("Start time as YYYY-MM-DD HH:mm (local)"), "minutes" to n("Duration, default 60"), "location" to s("Optional")), listOf("title", "start")))
        .put(fn("email_compose", "Open the email app with a ready draft (user presses Send).", props("to" to s("Recipient email"), "subject" to s("Subject"), "body" to s("Body")), listOf("subject", "body")))
        .put(fn("whatsapp_voice", "Send a WhatsApp VOICE MESSAGE. mode='real' (default unless user set TTS as default): the user speaks it themselves - NOVA stops her own mic/call-mode, holds WhatsApp's own mic button and the user talks for 'seconds'. mode='tts' or speak_text given: NOVA synthesizes 'speak_text' to an audio FILE in her own memory first (never touches the live mic) and sends that file as a normal WhatsApp audio attachment - always exact and reliable. Before calling mode=real, tell the user in one short sentence what will happen and wait for that to finish.",
            props("who" to s(WHO), "seconds" to n("real mode: how long to record when the user speaks, 3-55, default 10"), "speak_text" to s("tts mode: exact text NOVA should say as the voice note"), "mode" to s("'real' or 'tts'; if omitted, speak_text given -> tts, else the user's saved default voice mode"), "business" to b(BIZ)), listOf("who")))
        .put(fn("go_to_sleep", "Stop listening and go idle until the user says NOVA again (use when the user says chup ho jao / baad mein baat karte hain / sleep)."))
        .put(fn("get_battery", "Get the phone battery percent and whether it is charging."))
        .put(fn("analyze_candles", "Trading: fetch real candles (crypto pair like BTCUSDT, ETHUSDT) and analyse EMA, RSI, candle patterns, support/resistance. Returns SIGNAL UP/DOWN/WAIT with reasons. Analysis only, never guaranteed.", props("symbol" to s("Pair such as BTCUSDT"), "interval" to s("1m,5m,15m,1h,4h,1d; default 15m")), listOf("symbol")))
        .put(fn("analyze_chart", "Trading: take a screenshot of the chart currently on screen (TradingView, broker app...) and analyse its candles. Only when the user asks to look at a chart.", props("note" to s("Optional hint such as pair/timeframe"))))
        .put(fn("watch_candles", "Trading: analyse candles of a crypto pair again and again by itself (notification + spoken signal each time).", props("symbol" to s("e.g. BTCUSDT"), "interval" to s("candle size, default 15m"), "every_minutes" to n("how often, default 15"), "for_minutes" to n("how long to keep watching, default 480")), listOf("symbol")))
        .put(fn("watch_screen_chart", "Trading: every N minutes open a chart app, screenshot the chart and analyse it (notification + spoken signal).", props("app" to s("App name to open, empty = use whatever is on screen"), "note" to s("pair/timeframe hint"), "every_minutes" to n("default 15"), "for_minutes" to n("default 480"))))
        .put(fn("copy_link_send", "Fully automatic: on the current app/page/video taps Share, then Copy link, reads the copied link and sends it to the contact on WhatsApp. Use for 'ye link copy karke dost ko bhej do'. Do NOT ask the user to tap anything.", props("who" to s("Contact name")), listOf("who")))
        .put(fn("run_macro", "Run one of the user's custom macros (custom commands the user created inside the app) by name.", props("name" to s("Macro name")), listOf("name")))
        .put(fn("youtube_play", "FULLY AUTOMATIC: opens YouTube, searches the query, skips ads, checks the title matches and PLAYS the best result by itself. Use for 'youtube par X search karke play karo'. Never ask the user which result.", props("query" to s("Song / video name")), listOf("query")))
        .put(fn("code_task", "Delegate coding tasks to an external AI (DeepSeek default, ChatGPT, Claude, Gemini, or Grok) inside NOVA Browser WebView, verify login, extract generated code, and save project files + zip to Download/NOVA-Backups.", props("task" to s("What the program should do (mention deepseek/chatgpt/claude/gemini/grok if desired)"), "send_to" to s("WhatsApp contact to send the result, optional")), listOf("task")))
        .put(fn("deploy_code", "ONLY when user explicitly asks to host/deploy: opens Render, Railway, Vercel, or Netlify dashboard in NOVA Browser (or Termux GitHub push).", props("repo_name" to s("Project or GitHub repo name"), "host" to s("'render', 'railway', 'vercel', 'netlify', or 'github'"), "send_to" to s("WhatsApp contact to send the URL, optional"))))
        .put(fn("weather", "Real current weather (temperature + condition) for the user's city Dera Ghazi Khan.", JSONObject()))
        .put(fn("unlock_screen", "Wake the phone and unlock the lock screen using the saved PIN. Use before any task if the phone is locked.", JSONObject()))
        .put(fn("lock_screen", "Lock the phone screen now.", JSONObject()))
        .put(fn("prayer_times", "Today's namaz/azan times for the user's location (Dera Ghazi Khan, Punjab, Pakistan) and the next prayer.", JSONObject()))
        .put(fn("clip_list", "List what is saved in NOVA's own clipboard (copied links/texts with what they were about).", props("count" to n("how many, default 10"))))
        .put(fn("clip_save", "Save the phone's current clipboard text into NOVA's clipboard, with an optional note of what it is about.", props("about" to s("what this link/text is about"))))
        .put(fn("clip_paste", "Paste something from NOVA's clipboard into the focused text field. query = words from the text or what it was about (e.g. 'youtube', 'today's link'); empty = latest.", props("query" to s("what to paste, empty = latest"))))
        .put(fn("music_play", "Play music/video-audio from the PHONE's own memory inside NOVA (not YouTube). mode: name (search by song name in query), biggest (largest file size first), longest (longest duration first), random, all.", props("query" to s("Song name words"), "mode" to s("name|biggest|longest|random|all")), listOf("mode")))
        .put(fn("music_control", "Control NOVA's music player.", props("action" to s("pause, resume, next, previous, stop or status")), listOf("action")))
        .put(fn("playlist_add", "Add a song to a named playlist. Empty query = the song playing now.", props("name" to s("Playlist name"), "query" to s("Song name words, empty = current")), listOf("name")))
        .put(fn("playlist_show", "Show all playlists or the songs inside one playlist.", props("name" to s("Playlist name, empty = list all playlists"))))
        .put(fn("playlist_play", "Play a saved playlist.", props("name" to s("Playlist name")), listOf("name")))
        .put(fn("backup_now", "Create a full backup file (memory, notes, reminders, clipboard, playlists) in Downloads/NOVA folder.", JSONObject()))
        .put(fn("browser_open", "NOVA's OWN built-in browser (v36): open a website / search in it. Plain text (not a URL) is searched on Google. Use this instead of Chrome for anything on the web, GitHub, MediaFire, Google Drive etc.", props("url" to s("URL, domain or search text"), "new_tab" to b("true to open in a new tab")), listOf("url")))
        .put(fn("browser_read", "Read the page open in NOVA's browser: title, url, visible text and a numbered list of clickable/input elements ([3] button Login). Always call this before browser_click / browser_type.", props("max_chars" to n("default 3500, max 12000"))))
        .put(fn("browser_click", "Click something in NOVA's browser by its [number] from browser_read or by its visible text. Set real=true for file-upload buttons.", props("target" to s("[number] or visible text"), "real" to b("true = real touch tap (needed for upload/choose-file buttons)")), listOf("target")))
        .put(fn("browser_type", "Type text into an input of NOVA's browser (by [number] or placeholder/label; empty target = focused/first input). submit=true presses Enter.", props("target" to s("[number] or label, may be empty"), "text" to s("Text to type"), "submit" to b("press Enter after typing")), listOf("text")))
        .put(fn("browser_scroll", "Scroll the page in NOVA's browser.", props("direction" to s("'down' or 'up'")), listOf("direction")))
        .put(fn("browser_nav", "Browser navigation.", props("action" to s("back, forward, reload, stop or home")), listOf("action")))
        .put(fn("browser_media", "Control the video/audio playing in NOVA's browser (the YouTube song started by youtube_play).", props("action" to s("play, pause, toggle, mute, unmute, volume, seek, next, prev, restart or status"), "value" to n("volume 0-100, or seek seconds (+/-)")), listOf("action")))
        .put(fn("browser_download", "DOWNLOAD a file link (MediaFire, Google Drive, direct .zip/.apk/.mp4 link ...) using NOVA's own browser. It opens the page, presses the download button by itself and saves the file in Download/NOVA-Downloads. Use for 'ye link download kar do'.", props("url" to s("Download page or file link")), listOf("url")))
        .put(fn("browser_upload", "Prepare a phone file (name searched in NOVA-FILES / Download / NOVA-Downloads) for the next upload button of NOVA's browser (GitHub upload, Drive etc). After this call browser_click on the page's 'choose your files / upload / browse' button with real=true.", props("file" to s("File name or several names separated by |")), listOf("file")))
        .put(fn("browser_tabs", "Manage tabs of NOVA's browser.", props("action" to s("list, new, switch, close or close_all"), "index" to n("tab number from list (0-based)"))))
        .put(fn("browser_window", "Where NOVA's browser is shown. float = floating window above every app/game, hidden = runs invisibly in the background (music keeps playing over a game), full = full screen, close = close the window (login/tabs stay saved).", props("mode" to s("float, hidden, full or close")), listOf("mode")))
        .put(fn("browser_look", "Take a picture of the page in NOVA's browser and look at it (live voice mode only)."))
        .put(fn("browser_status", "Status of NOVA's browser: window mode, tabs, current page, media state, recent downloads."))
        .put(fn("browser_profile", "Switch/see which saved login-set NOVA's browser uses: 'me' (the user's own accounts) or 'nova' (NOVA's own separate accounts). Empty name = just tell which is active. Switching closes all tabs.", props("name" to s("'me', 'nova' or empty"))))
        .put(fn("wait", "Wait a few seconds for a screen to load.", props("seconds" to n("1 to 8")), listOf("seconds")))

    fun execute(ctx: Context, name: String, a: JSONObject): String =
        try { run(ctx, name, a).also { Memory.log("tool", "$name ${a.toString().take(160)} -> ${it.take(120)}") } }
        catch (e: Exception) { "error: ${e.message}" }

    // ---------- helpers ----------
    private const val NO_ACC = "Accessibility service ON nahi hai. User se kaho NOVA Setup mein ON kare."
    private const val YT = "com.google.android.youtube"

    private fun launch(ctx: Context, i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val c: Context = NovaAccessibilityService.instance ?: ctx
        c.startActivity(i)
    }

    private fun installed(ctx: Context, p: String) = try { ctx.packageManager.getPackageInfo(p, 0); true } catch (_: Exception) { false }

    /** pkg de to pehle us app mein, warna Chrome (Custom Tabs + fallback). */
    private fun viewUrl(ctx: Context, url: String, pkg: String?) {
        val uri = Uri.parse(url)
        if (pkg != null) { try { launch(ctx, Intent(Intent.ACTION_VIEW, uri).setPackage(pkg)); return } catch (_: Exception) {} }
        val launchCtx: Context = NovaAccessibilityService.instance ?: ctx
        ChromeBridge.openInChrome(launchCtx, url)
    }

    private fun pollClick(acc: NovaAccessibilityService, seconds: Int, vararg labels: String): Boolean {
        val end = System.currentTimeMillis() + seconds * 1000L
        while (System.currentTimeMillis() < end) {
            if (acc.clickExact(labels.toList())) return true
            Thread.sleep(600)
        }
        return false
    }

    private fun waPkg(ctx: Context, business: Boolean): String {
        val want = if (business) "com.whatsapp.w4b" else "com.whatsapp"
        val other = if (business) "com.whatsapp" else "com.whatsapp.w4b"
        return if (installed(ctx, want)) want else if (installed(ctx, other)) other else want
    }
    private fun waLabel(pkg: String) = if (pkg.endsWith("w4b")) "WhatsApp Business" else "WhatsApp"

    private val ALIASES = mapOf(
        "whatsapp business" to "com.whatsapp.w4b", "business whatsapp" to "com.whatsapp.w4b", "whatsapp" to "com.whatsapp",
        "chrome" to "com.android.chrome", "youtube" to YT, "gemini" to "com.google.android.apps.bard",
        "termux" to "com.termux", "pubg" to "com.tencent.ig", "pubg mobile" to "com.tencent.ig", "bgmi" to "com.pubg.imobile", "free fire" to "com.dts.freefireth", "freefire" to "com.dts.freefireth", "free fire max" to "com.dts.freefiremax", "play store" to "com.android.vending", "playstore" to "com.android.vending")

    private fun openApp(ctx: Context, name: String): Boolean {
        val q = name.trim().lowercase()
        if (q.isEmpty()) return false
        val pm = ctx.packageManager
        ALIASES[q]?.let { p -> pm.getLaunchIntentForPackage(p)?.let { launch(ctx, it); return true } }
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val a = apps.firstOrNull { it.loadLabel(pm).toString().lowercase() == q }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(q) } ?: return false
        val l = pm.getLaunchIntentForPackage(a.activityInfo.packageName) ?: return false
        launch(ctx, l)
        return true
    }

    // ---------- contacts ----------
    private class Who(val name: String = "", val num: String = "", val err: String? = null)

    fun normalize(ctx: Context, raw: String): String {
        val t = raw.trim(); val d = t.filter { it.isDigit() }
        val cc = (ctx.getSharedPreferences("nova", Context.MODE_PRIVATE).getString("cc", "92") ?: "92").filter { it.isDigit() }.ifEmpty { "92" }
        return when {
            t.startsWith("+") -> d
            d.startsWith("00") -> d.drop(2)
            d.startsWith("0") -> cc + d.drop(1)
            d.length <= 10 -> cc + d
            else -> d
        }
    }

    private fun resolve(ctx: Context, who: String): Who {
        val w = who.trim()
        if (w.isEmpty()) return Who(err = "kisko? naam batao")
        val digits = w.filter { it.isDigit() }
        if (w.none { it.isLetter() } && digits.length >= 7) return Who(w, normalize(ctx, w))
        val key = w.lowercase()
        for (line in Memory.description(ctx).lines()) {
            if (line.lowercase().contains(key)) {
                val m = Regex("\\+?\\d[\\d\\s\\-]{6,}\\d").find(line)
                if (m != null) return Who(w, normalize(ctx, m.value))
            }
        }
        val hits = phoneContacts(ctx, w)
        if (hits.isEmpty()) return Who(err = "'$w' ka number contact book aur phone contacts mein nahi mila. User se number poochho.")
        val byNum = LinkedHashMap<String, String>()
        hits.forEach { (nm, num) -> byNum.putIfAbsent(normalize(ctx, num), nm) }
        if (byNum.size == 1) { val e = byNum.entries.first(); return Who(e.value, e.key) }
        val exact = byNum.entries.filter { it.value.equals(w, true) }
        if (exact.size == 1) return Who(exact[0].value, exact[0].key)
        val names = byNum.values.toSet()
        if (names.size == 1) { val e = byNum.entries.first(); return Who(e.value, e.key) }
        return Who(err = "Kai contacts mile: " + byNum.entries.take(5).joinToString("; ") { "${it.value} (${it.key})" } + ". User se poochho kaun sa.")
    }

    private fun phoneContacts(ctx: Context, name: String): List<Pair<String, String>> {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return emptyList()
        fun q(term: String): List<Pair<String, String>> {
            val out = ArrayList<Pair<String, String>>()
            ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$term%"), null)?.use { c ->
                while (c.moveToNext() && out.size < 30) out.add((c.getString(0) ?: "") to (c.getString(1) ?: ""))
            }
            return out
        }
        var r = q(name)
        if (r.isEmpty()) name.split(Regex("\\s+")).filter { it.length >= 3 }.forEach { if (r.isEmpty()) r = q(it) }
        return r
    }

    // ---------- main dispatcher ----------
    private fun run(ctx: Context, name: String, a: JSONObject): String {
        val acc = NovaAccessibilityService.instance
        return when (name) {
            "open_app" -> if (openApp(ctx, a.optString("name"))) "opened" else "app not found"
            "youtube_search" -> {
                val q = a.optString("query")
                if (NovaBrowser.ytInBrowser(ctx) && !a.optBoolean("legacy")) NovaBrowser.openVisible(ctx, "https://m.youtube.com/results?search_query=" + Uri.encode(q))
                else {
                    viewUrl(ctx, "https://www.youtube.com/results?search_query=" + Uri.encode(q), if (a.optBoolean("browser")) null else YT)
                    "youtube search results opened for: $q"
                }
            }
            "web_search" -> {
                val q = a.optString("query")
                val gu = ChromeBridge.searchUrl(q)
                if (NovaBrowser.handlesLinks(ctx)) NovaBrowser.openVisible(ctx, gu) else { viewUrl(ctx, gu, null); "google search opened in Chrome" }
            }
            "open_url" -> {
                var u = a.optString("url").trim()
                if (!u.contains("://")) u = "https://$u"
                if (NovaBrowser.handlesLinks(ctx)) NovaBrowser.openVisible(ctx, u) else { viewUrl(ctx, u, null); "opened $u" }
            }
            "open_in_chrome" -> {
                val u = a.optString("url").trim()
                val q = a.optString("query").trim()
                val launchCtx: Context = NovaAccessibilityService.instance ?: ctx
                when {
                    u.isNotEmpty() -> { ChromeBridge.openInChrome(launchCtx, u); "opened in Chrome: ${ChromeBridge.normalizeUrl(u)}" }
                    q.isNotEmpty() -> { ChromeBridge.searchInChrome(launchCtx, q); "searched in Chrome: $q" }
                    else -> { ChromeBridge.openInChrome(launchCtx, NovaBrowser.active?.url ?: "https://www.google.com"); "opened Chrome" }
                }
            }
            "read_screen" -> acc?.dump() ?: NO_ACC
            "read_screen_text" -> acc?.dumpText() ?: NO_ACC
            "click_text" -> if (acc == null) NO_ACC else if (acc.clickText(a.optString("text"))) "clicked" else "not found on screen"
            "tap" -> if (acc == null) NO_ACC else if (acc.tap(a.optDouble("x").toFloat(), a.optDouble("y").toFloat())) "tapped" else "tap failed"
            "swipe" -> if (acc == null) NO_ACC else if (acc.swipe(a.optDouble("x1").toFloat(), a.optDouble("y1").toFloat(),
                a.optDouble("x2").toFloat(), a.optDouble("y2").toFloat(), a.optLong("duration_ms", 300))) "swiped" else "swipe failed"
            "scroll" -> {
                if (acc == null) return NO_ACC
                val dm = ctx.resources.displayMetrics
                val w = dm.widthPixels / 2f; val h = dm.heightPixels.toFloat()
                val down = a.optString("direction", "down").lowercase() != "up"
                val ok = if (down) acc.swipe(w, h * 0.72f, w, h * 0.30f, 350) else acc.swipe(w, h * 0.30f, w, h * 0.72f, 350)
                if (ok) "scrolled" else "scroll failed"
            }
            "type_text" -> if (acc == null) NO_ACC else if (acc.typeText(a.optString("text"))) "typed" else "no text field found"
            "press" -> {
                if (acc == null) return NO_ACC
                val ok = when (a.optString("button").lowercase()) {
                    "back" -> acc.back(); "home" -> acc.home(); "recents" -> acc.recents()
                    "notifications" -> acc.notifications(); "lock" -> acc.lock(); "screenshot" -> acc.screenshot()
                    else -> return "unknown button"
                }
                if (ok) "done" else "failed"
            }
            "call" -> call(ctx, a.optString("who"))
            "find_contact" -> {
                val r = resolve(ctx, a.optString("name"))
                if (r.err != null) r.err else "${r.name}: +${r.num}"
            }
            "whatsapp_send" -> waSend(ctx, a)
            "whatsapp_call" -> waCall(ctx, a)
            "send_media" -> sendMedia(ctx, a)
            "find_files" -> {
                val l = Media.find(ctx, a.optString("kind", "photo"), a.optString("query"), 8)
                if (l.isEmpty()) "kuch nahi mila (Setup mein Files/Gallery access dekho)"
                else l.joinToString("\n") { "${it.name}  (${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(it.modified))})" }
            }
            "whatsapp_status" -> waStatus(ctx, a)
            "ask_gemini" -> askGemini(ctx, a.optString("prompt"))
            "termux_run" -> {
                val cmd = a.optString("command")
                Memory.log("termux", cmd)
                TermuxBridge.run(ctx, cmd, a.optBoolean("visible"))
            }
            "copy_to_clipboard" -> {
                val want = a.optString("text")
                Handler(Looper.getMainLooper()).post {
                    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("nova", want))
                }
                Thread.sleep(300)
                val back = readClipboardRetry(ctx, 2)
                if (back.startsWith("clipboard khali")) "copy verify nahi hui - dobara copy_to_clipboard chalao" else "copied (verified)"
            }
            "look_screen" -> {
                val sink = imageSink ?: return "look_screen sirf live voice mode mein chalta hai"
                if (acc == null) return NO_ACC
                val bmp = acc.takeBitmap() ?: return "screenshot nahi le paya (Android 11+ aur Accessibility ON chahiye)"
                sink(Media.jpeg(bmp, 800)); Thread.sleep(800)
                "screen ki tasveer bhej di gayi - ab usse dekh kar batao kya kya hai"
            }
            "look_photo" -> {
                val sink = imageSink ?: return "look_photo sirf live voice mode mein chalta hai"
                val pic = Media.find(ctx, "photo", a.optString("query"), 1).firstOrNull() ?: return "photo nahi mili (gallery permission dekho)"
                val jpg = Media.jpegFromUri(ctx, pic.uri) ?: return "photo khul nahi paayi"
                sink(jpg); Thread.sleep(800)
                "photo '${pic.name}' bhej di gayi - ab usse dekh kar batao"
            }
            "remember" -> { Memory.note(ctx, a.optString("note")); "yaad kar liya" }
            "recall" -> {
                val r = Memory.search(ctx, a.optString("query"))
                if (r.isEmpty()) "memory mein kuch nahi mila" else r.joinToString("\n")
            }
            "schedule_task" -> schedule(ctx, a)
            "list_tasks" -> TaskStore.all(ctx).let { l -> if (l.isEmpty()) "koi scheduled task nahi" else l.joinToString("\n") { TaskStore.describe(it) } }
            "cancel_task" -> if (TaskStore.remove(ctx, a.optInt("id"))) "cancelled" else "us id ka task nahi mila"
            "notify_user" -> { Notifier.show(ctx, "NOVA reminder", a.optString("text")); Notifier.speak(ctx, a.optString("text")); "reminder shown" }
            "play_store_install" -> playStore(ctx, a)
            "get_time" -> SimpleDateFormat("EEEE, yyyy-MM-dd HH:mm", Locale.US).format(Date()) + " (" + TimeZone.getDefault().id + ")"
            "volume" -> {
                val am = ctx.getSystemService(AudioManager::class.java)
                val up = a.optString("direction").lowercase() != "down"
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                "volume changed"
            }
            "set_alarm" -> {
                launch(ctx, Intent(android.provider.AlarmClock.ACTION_SET_ALARM)
                    .putExtra(android.provider.AlarmClock.EXTRA_HOUR, a.optInt("hour")).putExtra(android.provider.AlarmClock.EXTRA_MINUTES, a.optInt("minute"))
                    .putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, a.optString("label", "NOVA")).putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true))
                Memory.log("action", "alarm set ${a.optInt("hour")}:${a.optInt("minute")}")
                "alarm set for %02d:%02d".format(a.optInt("hour"), a.optInt("minute"))
            }
            "set_timer" -> {
                launch(ctx, Intent(android.provider.AlarmClock.ACTION_SET_TIMER)
                    .putExtra(android.provider.AlarmClock.EXTRA_LENGTH, a.optInt("seconds", 60)).putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, a.optString("label", "NOVA"))
                    .putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true))
                "timer started"
            }
            "open_settings" -> {
                val act = when (a.optString("page").lowercase()) {
                    "wifi" -> android.provider.Settings.ACTION_WIFI_SETTINGS
                    "bluetooth" -> android.provider.Settings.ACTION_BLUETOOTH_SETTINGS
                    "battery" -> android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS
                    "display" -> android.provider.Settings.ACTION_DISPLAY_SETTINGS
                    "sound" -> android.provider.Settings.ACTION_SOUND_SETTINGS
                    "location" -> android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS
                    "apps" -> android.provider.Settings.ACTION_APPLICATION_SETTINGS
                    "accessibility" -> android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS
                    "date" -> android.provider.Settings.ACTION_DATE_SETTINGS
                    "airplane" -> android.provider.Settings.ACTION_AIRPLANE_MODE_SETTINGS
                    "private_dns", "network" -> android.provider.Settings.ACTION_WIRELESS_SETTINGS
                    "storage" -> android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS
                    else -> android.provider.Settings.ACTION_SETTINGS
                }
                launch(ctx, Intent(act)); "settings opened"
            }
            "navigate" -> { viewUrl(ctx, "https://www.google.com/maps/dir/?api=1&destination=" + Uri.encode(a.optString("place")), "com.google.android.apps.maps"); "maps opened for ${a.optString("place")}" }
            "fetch_page" -> fetchPage(a.optString("url"))
            "get_news" -> getNews(a.optString("topic"), a.optString("lang", "en"))
            "hold_tap" -> {
                if (acc == null) NO_ACC else {
                    val dm = ctx.resources.displayMetrics
                    val x = if (a.has("x_pct")) (a.optDouble("x_pct") * dm.widthPixels).toFloat() else a.optDouble("x").toFloat()
                    val y = if (a.has("y_pct")) (a.optDouble("y_pct") * dm.heightPixels).toFloat() else a.optDouble("y").toFloat()
                    val secs = a.optDouble("seconds", 5.0).coerceIn(0.5, 60.0)
                    if (acc.holdAt(x, y, (secs * 1000).toLong())) "pakda rakha $secs sec" else "hold nahi ho paya"
                }
            }
            "skip_ad" -> if (acc == null) NO_ACC else if (acc.skipAd()) "ad skip/close kar diya" else "skip/close button nahi mila - look_screen se dekho"
            "show_emotion" -> { Vol.emote(a.optString("emotion")); "chehre par ${a.optString("emotion")} dikha diya" }
            "calendar_events" -> {
                if (ctx.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                    "Calendar permission nahi hai - Setup mein Permissions dabakar Calendar allow karo"
                else {
                    val days = a.optInt("days", 7).coerceIn(1, 60)
                    val from = System.currentTimeMillis(); val to = from + days * 24L * 3600 * 1000
                    val bu = android.provider.CalendarContract.Instances.CONTENT_URI.buildUpon()
                    android.content.ContentUris.appendId(bu, from); android.content.ContentUris.appendId(bu, to)
                    val out = StringBuilder()
                    val fmt = java.text.SimpleDateFormat("EEE d MMM HH:mm", java.util.Locale.getDefault())
                    try {
                        ctx.contentResolver.query(bu.build(), arrayOf("title", "begin", "end", "eventLocation"), null, null, "begin ASC")?.use { c ->
                            var cnt = 0
                            while (c.moveToNext() && cnt < 25) {
                                out.append(fmt.format(java.util.Date(c.getLong(1)))).append(" - ").append(c.getString(0) ?: "(no title)")
                                c.getString(3)?.takeIf { it.isNotBlank() }?.let { out.append(" @ ").append(it) }
                                out.append("\n"); cnt++
                            }
                        }
                    } catch (e: Exception) { out.append("error: ").append(e.message) }
                    if (out.isEmpty()) "Agle $days din mein koi event nahi" else out.toString()
                }
            }
            "calendar_add" -> {
                val st = try { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).parse(a.optString("start"))?.time } catch (_: Exception) { null }
                if (st == null) "start 'YYYY-MM-DD HH:mm' format mein do"
                else {
                    val mins = a.optInt("minutes", 60).coerceIn(5, 24 * 60)
                    val i = Intent(Intent.ACTION_INSERT).setData(android.provider.CalendarContract.Events.CONTENT_URI)
                        .putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, st)
                        .putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, st + mins * 60_000L)
                        .putExtra(android.provider.CalendarContract.Events.TITLE, a.optString("title"))
                        .putExtra(android.provider.CalendarContract.Events.EVENT_LOCATION, a.optString("location"))
                    launch(ctx, i); "Calendar khul gaya, event bhara hua hai - Save dabana baaki hai (click_text 'Save')"
                }
            }
            "email_compose" -> {
                val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + a.optString("to")))
                    .putExtra(Intent.EXTRA_SUBJECT, a.optString("subject")).putExtra(Intent.EXTRA_TEXT, a.optString("body"))
                launch(ctx, i); "Email app khul gaya, draft tayyar - Send dabana baaki hai (click_text 'Send')"
            }
            "read_clipboard" -> readClipboardRetry(ctx)
            "whatsapp_voice" -> waVoice(ctx, a)
            "go_to_sleep" -> {
                val c = LiveService.liveClient
                Handler(Looper.getMainLooper()).postDelayed({ c?.enterPassive() }, 4000)
                "theek hai, 4 second mein so jaungi - 'NOVA' bolo to jaag jaungi"
            }
            "get_battery" -> {
                val i = ctx.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val lvl = (i?.getIntExtra("level", -1) ?: -1) * 100 / (i?.getIntExtra("scale", 100) ?: 100).coerceAtLeast(1)
                val st = i?.getIntExtra("status", -1) ?: -1
                "battery $lvl%, ${if (st == 2 || st == 5) "charging" else "charge nahi ho raha"}"
            }
            "analyze_candles" -> Trading.analyzeCandles(ctx, a.optString("symbol"), a.optString("interval", "15m"))
            "analyze_chart" -> Trading.analyzeScreen(ctx, a.optString("note"))
            "watch_candles" -> Trading.schedule(ctx, "candles_watch:${a.optString("symbol")}|${a.optString("interval", "15m")}", a.optInt("every_minutes", 15), a.optInt("for_minutes", 480))
            "watch_screen_chart" -> Trading.schedule(ctx, "screen_watch:${a.optString("app")}|${a.optString("note")}", a.optInt("every_minutes", 15), a.optInt("for_minutes", 480))
            "copy_link_send" -> {
                val accs = NovaAccessibilityService.instance
                if (accs == null) "Accessibility OFF hai"
                else {
                    // v30: PEHLE purana clipboard note kar lo - taake Copy ke baad hum pakka keh sakein ke NAYA link aaya, purana resend na ho
                    val before = (ClipStore.readNow(ctx) ?: ClipStore.all(ctx).lastOrNull()?.optString("text") ?: "").trim()
                    var ok = false
                    for (i in 0 until 10) { ok = accs.clickExact(listOf("Share", "share", "Share video", "SHARE", "Send", "Teilen", "Share to...")) || accs.clickText("Share"); if (ok) break; Thread.sleep(250) }
                    if (!ok) "Share button nahi mila"
                    else {
                        Thread.sleep(450)
                        var cp = false
                        for (i in 0 until 14) {
                            cp = accs.clickExact(listOf("Copy link", "Copy Link", "Copy URL", "Copy", "COPY LINK", "Copy to clipboard", "Link kopieren")) || accs.clickText("Copy link") || accs.clickText("Copy")
                            if (cp) break; Thread.sleep(250)
                        }
                        if (!cp) "Copy link button nahi mila"
                        else {
                            // v30: ab tak jo copy hua wo NAYA hai ya nahi, 3 second tak check karo - purana cached link kabhi resend nahi hota
                            var link = ""
                            val end = System.currentTimeMillis() + 3000
                            while (System.currentTimeMillis() < end) {
                                val cur = (ClipStore.readNow(ctx) ?: "").trim()
                                if (cur.isNotBlank() && cur != before && cur.startsWith("http", true)) { link = cur; break }
                                Thread.sleep(200)
                            }
                            if (link.isBlank()) { ClipStore.grabBlocking(ctx, "shared link"); val g = (ClipStore.readNow(ctx) ?: "").trim(); if (g.isNotBlank() && g != before) link = g }
                            if (link.isBlank()) "FAIL: naya link clipboard mein nahi aaya (ya purana hi tha) - purana link dobara nahi bheja. Dobara Share/Copy try karo."
                            else { ClipStore.add(ctx, link, "shared link"); execute(ctx, "whatsapp_send", JSONObject().put("who", a.optString("who")).put("message", link)) }
                        }
                    }
                }
            }
            "run_macro" -> Macros.run(ctx, a.optString("name"))
            "youtube_play" -> {
                val q = a.optString("query")
                // v36: pehle NOVA ka APNA browser (YouTube app / Chrome nahi) - background mein bhi chalta hai; fail ho to purana tareeqa
                val rb = if (NovaBrowser.ytInBrowser(ctx)) NovaBrowser.youtubePlay(ctx, q) else ""
                if (rb.isNotEmpty() && !rb.startsWith("ERR")) rb
                else {
                    execute(ctx, "youtube_search", JSONObject().put("query", q).put("legacy", true))
                    Thread.sleep(2200)
                    val accY = NovaAccessibilityService.instance
                    val old = if (accY == null) "YouTube search khuli lekin Accessibility OFF hai - chala nahi sakti" else accY.playFirstMatch(q)
                    (if (rb.isNotEmpty()) "(NOVA browser: $rb) " else "") + old
                }
            }
            "code_task" -> {
                val res = AiRouter.routeCodeTask(ctx, a.optString("task")) { Memory.log("code", it) }
                val summary = if (res.ok) {
                    "Code ready via ${res.provider}. Backup zip: ${res.zipPath}\n\n${res.code.take(600)}"
                } else {
                    res.message
                }
                val who = a.optString("send_to")
                if (who.isNotBlank() && res.ok) {
                    execute(ctx, "whatsapp_send", JSONObject().put("who", who).put("message", res.code.take(1000))) + " | " + summary
                } else summary
            }
            "deploy_code" -> {
                val repo = a.optString("repo_name").trim().ifEmpty { "nova-" + (System.currentTimeMillis() % 100000) }
                val host = a.optString("host", "render").trim().lowercase()
                val out = when (host) {
                    "github" -> DeployHub.githubPush(ctx, repo)
                    else -> AiRouter.openHosting(ctx, host)
                }
                val who = a.optString("send_to")
                val url = Regex("https://\\S+").find(out)?.value
                if (who.isNotBlank() && url != null) execute(ctx, "whatsapp_send", JSONObject().put("who", who).put("message", url)) + " | " + out else out
            }
            "weather" -> Weather.now(ctx)
            "unlock_screen" -> Unlocker.unlock(ctx)
            "lock_screen" -> Unlocker.lockNow()
            "prayer_times" -> Prayer.todayText()
            "clip_list" -> ClipStore.list(ctx, a.optInt("count", 10).coerceIn(1, 30))
            "clip_save" -> ClipStore.grabBlocking(ctx, a.optString("about"))
            "clip_paste" -> {
                val j = ClipStore.find(ctx, a.optString("query"))
                if (j == null) "NOVA clipboard mein aisa kuch nahi mila"
                else {
                    val txt = j.optString("text"); ClipStore.putOnClipboard(ctx, txt)
                    if (acc != null && acc.typeText(txt)) "paste kar diya: ${txt.take(80)}" else "clipboard par rakh diya, text field mein lamba dabakar Paste karo: ${txt.take(80)}"
                }
            }
            "music_play" -> MusicPlayer.playQuery(ctx, a.optString("query"), a.optString("mode", "name"))
            "music_control" -> when (a.optString("action").lowercase()) {
                "pause" -> MusicPlayer.pause(); "resume", "play" -> MusicPlayer.resume(); "next" -> MusicPlayer.next()
                "previous", "prev" -> MusicPlayer.prev(); "stop" -> MusicPlayer.stop(); else -> MusicPlayer.status()
            }
            "playlist_add" -> MusicPlayer.playlistAdd(ctx, a.optString("name"), a.optString("query"))
            "playlist_show" -> MusicPlayer.playlistShow(ctx, a.optString("name"))
            "playlist_play" -> MusicPlayer.playlistPlay(ctx, a.optString("name"))
            "backup_now" -> Backup.backupToDownloads(ctx)
            "wait" -> { Thread.sleep((a.optDouble("seconds", 2.0).coerceIn(0.5, 8.0) * 1000).toLong()); "waited" }
            else -> if (name.startsWith("browser_")) NovaBrowser.tool(ctx, name, a) else "unknown tool: $name"
        }
    }

    private fun call(ctx: Context, who: String): String {
        val r = resolve(ctx, who)
        if (r.err != null) return r.err
        if (ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) return "call permission missing"
        launch(ctx, Intent(Intent.ACTION_CALL, Uri.parse("tel:+" + r.num)))
        return "calling ${r.name.ifEmpty { who }}"
    }

    /**
     * v30: Group ya WhatsApp-only naam (phone contacts mein nahi, sirf WhatsApp mein) - wa.me se nahi khulta (number nahi hota).
     * WhatsApp list mein Search se dhoondh kar TITLE (row text) par click karta hai, phir confirm karta hai ke
     * "Group Info / Contact Info" screen par nahi (us case mein back jakar dobara try karta hai) - yehi
     * "chat ki jagah photo/info khul jati hai" wale bug ka asli fix hai.
     */
    private fun openChatByName(ctx: Context, name: String, business: Boolean): String {
        val acc = NovaAccessibilityService.instance ?: return NO_ACC
        val pkg = waPkg(ctx, business)
        val li = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return "${waLabel(pkg)} install nahi hai"
        launch(ctx, li)
        Thread.sleep(1800)
        val searched = pollClick(acc, 6, "Search", "Search icon", "Search WhatsApp", "search")
        if (!searched) {
            val dm = ctx.resources.displayMetrics
            acc.tap(dm.widthPixels - 40 * dm.density, 56 * dm.density)
        }
        Thread.sleep(600)
        acc.typeText(name)
        Thread.sleep(1600)
        if (!acc.clickRowByText(name)) return "'$name' WhatsApp mein kahin nahi mila (naam sahi likha? ya saved contact/group nahi hai)"
        Thread.sleep(1300)
        if (acc.looksLikeInfoScreen()) {
            // chat ki jagah info khul gayi: wapas jaake dobara (search list mein doosri entry try karo)
            acc.back(); Thread.sleep(900)
            acc.typeText(name); Thread.sleep(1200)
            acc.clickRowByText(name); Thread.sleep(1200)
            if (acc.looksLikeInfoScreen()) acc.back()
        }
        return "OK"
    }

    // ---------- WhatsApp ----------
    @Volatile private var lastWaKey = ""
    @Volatile private var lastWaAt = 0L
    private fun waSend(ctx: Context, a: JSONObject): String {
        val acc = NovaAccessibilityService.instance ?: return NO_ACC
        val who = a.optString("who"); val msg = a.optString("message")
        val r = resolve(ctx, who)
        if (r.err != null) {
            // v30: phone contacts mein nahi mila - WhatsApp group/naam ho sakta hai, list mein dhoondo
            val pkg0 = waPkg(ctx, a.optBoolean("business"))
            val oc = openChatByName(ctx, who, a.optBoolean("business"))
            if (oc != "OK") return r.err
            Thread.sleep(400)
            if (!acc.typeText(msg)) return "'$who' ki chat khul gayi par message box nahi mila - type_text se likho"
            Thread.sleep(400)
            val ok = pollClick(acc, 10, "Send")
            Memory.log("action", "${waLabel(pkg0)} message to $who (group/chat naam se): $msg")
            return if (ok) "message sent to $who on ${waLabel(pkg0)}" else "message likha hua hai, Send button nahi dab paya - read_screen karke dabao"
        }
        // v13: wahi message wahi bande ko 45 second ke andar dobara nahi jayega
        val dk = "${r.num}|${msg.trim()}"; val nowMs = System.currentTimeMillis()
        if (dk == lastWaKey && nowMs - lastWaAt < 45_000L) return "message sent to ${r.name.ifEmpty { who }} (pehle hi bhej chuki hoon, dobara nahi bheja)"
        lastWaKey = dk; lastWaAt = nowMs
        val pkg = waPkg(ctx, a.optBoolean("business"))
        launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/${r.num}?text=" + Uri.encode(msg))).setPackage(pkg))
        Thread.sleep(2200)
        val ok = pollClick(acc, 15, "Send")
        val label = r.name.ifEmpty { who }
        Memory.log("action", "${waLabel(pkg)} message to $label (+${r.num}): $msg")
        return if (ok) "message sent to $label on ${waLabel(pkg)}"
        else "chat khul gayi (${waLabel(pkg)}) message likha hua hai lekin Send button nahi dab paya - read_screen karke Send dabao"
    }

    @Volatile var lastVoice = ""

    private fun voiceModeDefault(ctx: Context) = ctx.getSharedPreferences("nova", Context.MODE_PRIVATE).getString("voice_mode", "real") ?: "real"

    /** TTS mode: file WhatsApp ki apni "share to" list se bheji jati hai - contact aur group dono ke liye kaam karta hai. */
    private fun sendFileToChat(ctx: Context, acc: NovaAccessibilityService, targetName: String, file: java.io.File, mime: String, pkg: String): String {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fp", file)
        launch(ctx, Intent(Intent.ACTION_SEND).apply { type = mime; putExtra(Intent.EXTRA_STREAM, uri); setPackage(pkg); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) })
        Thread.sleep(2300)
        if (!acc.clickRowByText(targetName)) return "FAIL: WhatsApp ki list mein '$targetName' nahi mila"
        Thread.sleep(1500)
        val ok = pollClick(acc, 12, "Send")
        return if (ok) "OK" else "FAIL: file attach ho gayi par Send nahi dab paya - read_screen karke dabao"
    }

    private fun waVoice(ctx: Context, a: JSONObject): String {
        val acc = NovaAccessibilityService.instance ?: return NO_ACC
        val who = a.optString("who")
        val r = resolve(ctx, who)
        val label = if (r.err == null) r.name.ifEmpty { who } else who
        val say = a.optString("speak_text").trim()
        val mode = a.optString("mode").trim().lowercase().ifEmpty { if (say.isNotEmpty()) "tts" else voiceModeDefault(ctx) }
        val pkg = waPkg(ctx, a.optBoolean("business"))

        // ===== TTS mode: NOVA apni awaaz file mein banati hai, mic ko chhoo'ti nahi, file normal attachment ki tarah jaati hai =====
        if (mode == "tts") {
            if (say.isEmpty()) return "FAIL: tts mode ke liye speak_text chahiye (bilkul wahi jumla jo bolna hai)"
            val f = TtsFile.synth(ctx, say, 30000L) ?: return "FAIL: awaaz ki file nahi ban saki (TTS/AI awaaz check karo)"
            lastVoice = "→ $label (TTS file): $say"
            val res = sendFileToChat(ctx, acc, label, f, "audio/mpeg", pkg)
            Memory.log("action", "${waLabel(pkg)} TTS voice file to $label: $say")
            lastVoice = if (res == "OK") "✅ $label ko bheji (TTS file): $say" else "❌ $label: $res"
            return if (res == "OK") "OK: apni awaaz mein file bana kar $label ko bhej di: \"$say\"" else res
        }

        // ===== REAL mode: user khud bolta hai, WhatsApp ka apna mic button pakad kar record hota hai =====
        val secs = a.optDouble("seconds", 10.0).coerceIn(3.0, 55.0)
        val shown = "(aap bolo, ${secs.toInt()} sec)"
        lastVoice = "→ $label: $shown"
        val lc = LiveService.liveClient
        val note = "WhatsApp voice message ka kaam khatam. Tool result ke mutabiq SACH-SACH ek chhoti line mein batao aur poochho ab kya karna hai."
        lc?.waitQuiet(6000)
        lc?.suspendForVoice()          // NOVA poori band: connection, mic, speaker, call-mode, wake-word
        try {
            Thread.sleep(500)
            if (r.err == null) launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/${r.num}")).setPackage(pkg))
            else { val oc = openChatByName(ctx, who, a.optBoolean("business")); if (oc != "OK") return r.err ?: oc }
            Thread.sleep(2500)
            for (i in 0 until 10) { if (acc.voiceReady()) break; Thread.sleep(700) }
            Thread.sleep(500)
            // v30: sirf neeche ke compose-bar ka mic button (findMic region-filter) - Sajid ki pehle se aayi hui voice par tap nahi hota ab
            val labels = listOf("Voice message", "Record voice message", "Microphone")
            val holdMs = (secs * 1000).toLong()
            var recording = false
            for (attempt in 0 until 3) {
                lc?.forceNormalAudio()
                if (!acc.holdStart(labels, holdMs)) { Thread.sleep(1000); continue }
                Thread.sleep(1400)
                val txt = acc.dumpText(2500)
                if (txt.contains("phone call", true)) { acc.clickExact(listOf("OK")); acc.cancelHold(); Thread.sleep(900); continue }
                if (txt.contains("cancel", true)) { recording = true; break }   // "Slide to cancel" = record chal rahi hai
                acc.cancelHold(); Thread.sleep(800)
            }
            if (!recording) {
                lastVoice = "❌ $label: voice record shuru nahi hui (3 koshish)"
                return "FAIL: voice record shuru nahi ho payi (3 baar try). Message BHEJA NAHI GAYA. look_screen se dekho aur sir ko sach batao."
            }
            Thread.sleep((secs * 1000).toLong())
            val ok = acc.holdWait(holdMs + 5000)   // pakad khatam = WhatsApp khud bhej deti hai
            Thread.sleep(700)
            Memory.log("action", "${waLabel(pkg)} voice message to $label (${secs.toInt()}s, user ki apni awaaz)")
            lastVoice = if (ok) "✅ $label ko bheji: $shown" else "⚠️ $label: pakad beech mein tooti"
            return if (ok) "OK: recording chali aur voice message $label ko bhej diya (${secs.toInt()} sec)."
            else "WARNING: pakad beech mein toot gayi - message kat gaya ya nahi gaya. look_screen se chat check karo."
        } finally { lc?.resumeAfterVoice(note) }
    }

    private fun waCall(ctx: Context, a: JSONObject): String {
        val acc = NovaAccessibilityService.instance ?: return NO_ACC
        val who = a.optString("who")
        val r = resolve(ctx, who)
        val label = if (r.err == null) r.name.ifEmpty { who } else who
        val pkg = waPkg(ctx, a.optBoolean("business"))
        if (r.err == null) launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/${r.num}")).setPackage(pkg))
        else { val oc = openChatByName(ctx, who, a.optBoolean("business")); if (oc != "OK") return r.err ?: oc }
        Thread.sleep(2500)
        val video = a.optBoolean("video")
        val ok = pollClick(acc, 12, if (video) "Video call" else "Voice call")
        if (ok) { Thread.sleep(1200); acc.clickExact(listOf("Call", "CALL")) }
        Memory.log("action", "${waLabel(pkg)} ${if (video) "video" else "voice"} call to $label")
        return if (ok) "calling $label on ${waLabel(pkg)}" else "chat khul gayi par call button nahi mila - read_screen karo"
    }

    private fun sendMedia(ctx: Context, a: JSONObject): String {
        val acc = NovaAccessibilityService.instance ?: return NO_ACC
        val who = a.optString("who")
        val r = resolve(ctx, who); if (r.err != null) return r.err
        val kind = a.optString("kind", "photo"); val q = a.optString("query")
        val item = Media.find(ctx, kind, q, 1).firstOrNull()
            ?: return "$kind nahi mila${if (q.isNotBlank()) " ('$q')" else ""} - Setup mein Files/Gallery access check karo"
        val pkg = waPkg(ctx, a.optBoolean("business"))
        val i = Intent(Intent.ACTION_SEND).apply {
            type = item.mime
            putExtra(Intent.EXTRA_STREAM, item.uri)
            a.optString("caption").takeIf { it.isNotBlank() }?.let { putExtra(Intent.EXTRA_TEXT, it) }
            putExtra("jid", "${r.num}@s.whatsapp.net")
            setPackage(pkg)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        launch(ctx, i)
        Thread.sleep(2500)
        val ok = pollClick(acc, 15, "Send")
        if (ok && !kind.lowercase().startsWith("photo") && !kind.lowercase().startsWith("video")) { Thread.sleep(1500); acc.clickExact(listOf("Send")) }
        Memory.log("action", "${waLabel(pkg)} $kind '${item.name}' to ${r.name.ifEmpty { who }}")
        val label = r.name.ifEmpty { who }
        return if (ok) "'${item.name}' bhej diya $label ko (${waLabel(pkg)})"
        else "'${item.name}' chat mein attach ho gaya par Send nahi dab paya - read_screen karke Send dabao"
    }

    private fun waStatus(ctx: Context, a: JSONObject): String {
        val acc = NovaAccessibilityService.instance ?: return NO_ACC
        val kind = a.optString("kind", "photo")
        val item = Media.find(ctx, kind, a.optString("query"), 1).firstOrNull() ?: return "$kind nahi mila"
        val pkg = waPkg(ctx, a.optBoolean("business"))
        launch(ctx, Intent(Intent.ACTION_SEND).apply {
            type = item.mime; putExtra(Intent.EXTRA_STREAM, item.uri); setPackage(pkg); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
        Thread.sleep(2500)
        val picked = pollClick(acc, 12, "My status", "My Status", "Status")
        if (!picked) return "WhatsApp share list khuli par 'My status' nahi mila - read_screen karke khud click karo"
        Thread.sleep(1500)
        acc.clickExact(listOf("Send", "Done", "Share"))
        Thread.sleep(1500)
        pollClick(acc, 6, "Send")
        Memory.log("action", "${waLabel(pkg)} status posted: ${item.name}")
        return "status lagane ki koshish ki: '${item.name}' - read_screen se confirm karo"
    }

    // ---------- Gemini app ----------
    private fun askGemini(ctx: Context, prompt: String): String {
        val acc = NovaAccessibilityService.instance ?: return NO_ACC
        val li = ctx.packageManager.getLaunchIntentForPackage("com.google.android.apps.bard") ?: return "Gemini app install nahi hai"
        launch(ctx, li)
        Thread.sleep(3500)
        var typed = false
        for (k in 0 until 8) { if (acc.typeText(prompt)) { typed = true; break }; Thread.sleep(800) }
        if (!typed) return "Gemini ka text box nahi mila - read_screen se dekho"
        Thread.sleep(700)
        if (!pollClick(acc, 6, "Send", "Send prompt", "Send message", "Submit"))
            return "prompt likh diya par Send button nahi mila - read_screen karke dabao"
        Thread.sleep(4000)
        var prev = ""; var stable = 0
        val end = System.currentTimeMillis() + 45_000
        while (System.currentTimeMillis() < end && stable < 2) {
            Thread.sleep(2500)
            val cur = acc.dumpText()
            if (cur == prev) stable++ else { stable = 0; prev = cur }
        }
        Memory.log("gemini", "asked: $prompt")
        return "Gemini screen ka text:\n" + prev.take(3000)
    }

    // ---------- Play Store ----------
    private fun playStore(ctx: Context, a: JSONObject): String {
        val name = a.optString("name"); val pkg = a.optString("package").trim()
        val vend = "com.android.vending"
        fun go(uri: String) {
            try { launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(vend)) }
            catch (_: Exception) { launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(uri.replace("market://", "https://play.google.com/store/apps/")))) }
        }
        if (pkg.isEmpty()) {
            go("market://search?q=" + Uri.encode(name) + "&c=apps")
            return "Play Store search khul gayi: $name - ab wait(3), read_screen, sahi app par click_text, phir Install"
        }
        go("market://details?id=$pkg")
        val acc = NovaAccessibilityService.instance ?: return "Play Store page khul gaya, par $NO_ACC"
        Thread.sleep(3000)
        val ok = pollClick(acc, 12, "Install", "Update")
        Memory.log("action", "Play Store install: $name ($pkg)")
        return if (ok) "Install dabaya: $name" else "page khul gaya par Install button nahi mila (shayad pehle se installed) - read_screen karo"
    }

    // ---------- Reminders ----------
    /** v12: clipboard 3 baar koshish (main thread par), khali ho to saaf wajah. */
    private fun readClipboardRetry(ctx: Context, tries: Int = 3): String {
        repeat(tries) { n ->
            var out: String? = null
            val latch = java.util.concurrent.CountDownLatch(1)
            Handler(Looper.getMainLooper()).post {
                try {
                    val cm = ctx.getSystemService(ClipboardManager::class.java)
                    out = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()?.take(6000)
                } catch (_: Exception) {}
                latch.countDown()
            }
            try { latch.await(2, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
            if (!out.isNullOrBlank()) return out!!
            if (n < tries - 1) Thread.sleep(400)
        }
        return "clipboard khali hai - Share/Copy link dobara dabao (look_screen se button dhoondo), phir copy ke baad turant read_clipboard chalao"
    }

    private fun schedule(ctx: Context, a: JSONObject): String {
        val text = a.optString("instruction").trim()
        if (text.isEmpty()) return "instruction khali hai"
        val cal = Calendar.getInstance()
        val mins = a.optInt("in_minutes", 0)
        if (mins > 0) cal.add(Calendar.MINUTE, mins)
        else {
            val m = Regex("^(\\d{1,2})[:.](\\d{2})").find(a.optString("time").trim()) ?: return "time HH:mm format mein do (jaise 12:30)"
            val d = a.optString("date").trim()
            if (d.isNotEmpty()) {
                val p = d.split("-"); if (p.size != 3) return "date yyyy-MM-dd format mein do"
                cal.set(p[0].toInt(), p[1].toInt() - 1, p[2].toInt())
            }
            cal.set(Calendar.HOUR_OF_DAY, m.groupValues[1].toInt()); cal.set(Calendar.MINUTE, m.groupValues[2].toInt())
            cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
            if (d.isEmpty() && cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_MONTH, 1)
            if (cal.timeInMillis <= System.currentTimeMillis()) return "ye waqt guzar chuka hai"
        }
        val id = TaskStore.add(ctx, cal.timeInMillis, text, a.optBoolean("repeat_daily"))
        Memory.log("schedule", "task #$id at ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(cal.time)}: $text")
        return "scheduled #$id for " + SimpleDateFormat("EEEE yyyy-MM-dd HH:mm", Locale.US).format(cal.time)
    }

    // ---------- web text / news ----------
    private fun fetchPage(url: String): String {
        var u = url.trim(); if (u.isEmpty()) return "url khali hai"
        if (!u.contains("://")) u = "https://$u"
        return try {
            val r = Net.client(readTimeoutSec = 30).newCall(okhttp3.Request.Builder().url(u).header("User-Agent", "Mozilla/5.0 (Linux; Android 14) NOVA").build()).execute()
            val html = r.body?.string() ?: ""; r.close()
            val txt = html.replace(Regex("(?is)<(script|style|noscript)[^>]*>.*?</\\1>"), " ")
                .replace(Regex("(?s)<[^>]+>"), " ").replace(Regex("&nbsp;|&amp;|&quot;|&#39;"), " ").replace(Regex("\\s+"), " ").trim()
            if (txt.isEmpty()) "page khali mila" else txt.take(9000)
        } catch (e: Exception) { "page nahi khul saka: ${e.message}" }
    }

    private fun getNews(topic: String, lang: String): String {
        val l = lang.ifBlank { "en" }
        val url = if (topic.isBlank()) "https://news.google.com/rss?hl=$l" else "https://news.google.com/rss/search?q=" + Uri.encode(topic) + "&hl=$l"
        return try {
            val r = Net.client(readTimeoutSec = 20).newCall(okhttp3.Request.Builder().url(url).build()).execute()
            val xml = r.body?.string() ?: ""; r.close()
            val items = Regex("(?s)<item>.*?<title>(.*?)</title>.*?<pubDate>(.*?)</pubDate>").findAll(xml).take(8)
                .map { it.groupValues[1].replace("<![CDATA[", "").replace("]]>", "").replace("&amp;", "&").replace("&quot;", "\"") + " (" + it.groupValues[2].take(22) + ")" }.toList()
            if (items.isEmpty()) "koi khabar nahi mili" else items.joinToString("\n")
        } catch (e: Exception) { "news nahi mili: ${e.message}" }
    }
}
