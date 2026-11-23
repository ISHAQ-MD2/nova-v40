package com.nova.app

import android.Manifest
import android.animation.ObjectAnimator
import android.app.Activity
import android.app.AlertDialog
import android.app.WallpaperManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.core.content.FileProvider
import com.airbnb.lottie.LottieAnimationView
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/** v14: reference jaisa poora native UI. Character + 5 asli info cards + neeche camera/mic/chat + side drawer. HTML/WebView nahi. */
class MainActivity : Activity() {
    private lateinit var ch: CharacterView
    private lateinit var status: TextView
    private lateinit var titleTv: TextView
    private lateinit var micLabel: TextView
    private lateinit var prefs: SharedPreferences
    private lateinit var input: EditText
    private lateinit var inputRow: LinearLayout
    private lateinit var bottomCol: LinearLayout
    private lateinit var menuBtn: GlowCircle
    private lateinit var paletteBtn: GlowCircle
    private lateinit var camBtn: GlowCircle
    private lateinit var chatBtn: GlowCircle
    private lateinit var micBtn: GlowCircle
    private lateinit var muteNovaBtn: GlowCircle
    private lateinit var muteMicBtn: GlowCircle
    private lateinit var cWeather: InfoCard
    private lateinit var cTime: InfoCard
    private lateinit var cAzan: InfoCard
    private lateinit var cMusic: InfoCard
    private lateinit var cRem: InfoCard
    private lateinit var rootFrame: FrameLayout
    private lateinit var cardsScroll: HorizontalScrollView
    private lateinit var drawer: FrameLayout
    private lateinit var drawerPanel: LinearLayout
    private lateinit var drawerList: LinearLayout
    private var inAppRobot: LottieAnimationView? = null
    private lateinit var modeToggleBtn: Button
    private lateinit var textChatPanel: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var chatList: LinearLayout
    private lateinit var speakReplyChip: TextView
    private var textMode = false
    private var speakReplies = false
    private var activeAudioPlayer: MediaPlayer? = null
    private val textTurns = ArrayList<Pair<String, String>>()
    private val robotSpeakListener: (Boolean) -> Unit = { speaking ->
        h.post {
            inAppRobot?.let { r ->
                r.speed = if (speaking) 2.2f else 1.0f
                val sc = if (speaking) 1.14f else 1.0f
                r.animate().scaleX(sc).scaleY(sc).setDuration(180).start()
            }
        }
    }
    private val h = Handler(Looper.getMainLooper())
    private var active = false
    private var autoTried = false
    private var accent = Color.parseColor("#FF8FC7")
    private var tickRun: Runnable? = null
    private var tickN = 0
    private val REQ_FILE = 25; private val REQ_WALL = 24; private val REQ_CAM = 21; private val REQ_PICK = 22; private val REQ_CAM_PERM = 23; private val REQ_MEDIA = 40

    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()
    private fun sysDim(name: String): Int { val id = resources.getIdentifier(name, "dimen", "android"); return if (id > 0) resources.getDimensionPixelSize(id) else 0 }
    private fun statusBarH() = sysDim("status_bar_height")
    private fun navBarH() = sysDim("navigation_bar_height")
    private fun lp(w: Int, hh: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, hh, weight)
    private fun dlg() = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
    private fun grad(c1: String, c2: String, r: Int = 24, stroke: Int? = null) = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
        intArrayOf(Color.parseColor(c1), Color.parseColor(c2))).apply { cornerRadius = dp(r).toFloat(); if (stroke != null) setStroke(dp(1), stroke) }
    private fun fmtTime(ms: Long, pattern: String = "h:mm a"): String = SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("Asia/Karachi") }.format(Date(ms))

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // poori screen: wallpaper status bar ke peeche tak
        window.statusBarColor = Color.TRANSPARENT; window.navigationBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        prefs = getSharedPreferences("nova", MODE_PRIVATE)
        Memory.init(this); ChatLog.init(this); ClipStore.start(this); TaskStore.purgeExpired(this); Prayer.scheduleNext(this)
        Thread { try { Memory.pruneChat(applicationContext, 3) } catch (_: Exception) {} }.start()   // faltu chat 3 din baad khud saaf, notes mehfooz

        val root = FrameLayout(this)
        rootFrame = root
        ch = CharacterView(this)
        ch.fxMode = prefs.getInt("fx", -1)
        root.addView(ch, FrameLayout.LayoutParams(-1, -1))

        // ---- upar: menu | NOVA + Text/Voice mode | theme ----
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), statusBarH() + dp(4), dp(8), 0) }
        menuBtn = GlowCircle(this).apply { icon = "menu"; contentDescription = "Open navigation menu"; setOnClickListener { openDrawer() } }
        paletteBtn = GlowCircle(this).apply { icon = "palette"; contentDescription = "Wallpaper and theme selector"; setOnClickListener { themeDialog() } }
        titleTv = TextView(this).apply { text = "NOVA"; textSize = 26f; letterSpacing = 0.28f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }
        modeToggleBtn = Button(this).apply {
            text = "Voice Mode"
            textSize = 11.5f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            minimumHeight = dp(48)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            contentDescription = "Switch between Voice mode and Text Chat mode"
            setOnClickListener { setTextMode(!textMode) }
        }
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        titleCol.addView(titleTv, lp(-2, -2))
        titleCol.addView(modeToggleBtn, lp(-2, dp(36)).apply { topMargin = dp(2) })
        top.addView(menuBtn, lp(dp(56), dp(56))); top.addView(titleCol, lp(0, -2, 1f)); top.addView(paletteBtn, lp(dp(56), dp(56)))
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        // ---- Optional in-app 3D/4D Lottie Robot companion ----
        val robot = RobotPet.createInAppCompanion(this).apply {
            visibility = if (prefs.getBoolean("robot", true)) View.VISIBLE else View.GONE
            setOnClickListener {
                status.text = "NOVA Companion ready (${VoiceEngine.selected(this@MainActivity).name})"
            }
        }
        inAppRobot = robot
        Vol.addSpeakListener(robotSpeakListener)
        val robotLp = FrameLayout.LayoutParams(dp(74), dp(74), Gravity.TOP or Gravity.END).apply {
            topMargin = statusBarH() + dp(72)
            marginEnd = dp(10)
        }
        root.addView(robot, robotLp)

        // ---- Text-Only Mode Panel (Gemini-style chat without mic/LiveService) ----
        textChatPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(10), dp(8), dp(10), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.argb(220, 8, 12, 24))
                setStroke(dp(1), Color.parseColor("#33FFFFFF"))
            }
        }
        val chatHeader = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(2), dp(6), dp(6)) }
        chatHeader.addView(TextView(this).apply { text = "💬 Text Mode (No Mic)"; setTextColor(Color.WHITE); textSize = 14f; typeface = Typeface.DEFAULT_BOLD }, lp(0, -2, 1f))
        speakReplyChip = chip("🔊 Speak: OFF", false) {
            speakReplies = !speakReplies
            speakReplyChip.text = if (speakReplies) "🔊 Speak: ON" else "🔊 Speak: OFF"
        }.apply { minimumHeight = dp(48); gravity = Gravity.CENTER }
        chatHeader.addView(speakReplyChip, lp(-2, dp(48)).apply { rightMargin = dp(6) })
        chatHeader.addView(chip("Clear", false) {
            textTurns.clear(); chatList.removeAllViews()
            addTextBubble("nova", "Text Mode ready hai. Sawaal likho ya WhatsApp se text/voice note share karo.")
        }.apply { minimumHeight = dp(48); gravity = Gravity.CENTER }, lp(-2, dp(48)))
        textChatPanel.addView(chatHeader, lp(-1, -2))
        chatList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(8)) }
        chatScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = true; addView(chatList) }
        textChatPanel.addView(chatScroll, lp(-1, 0, 1f))
        val panelLp = FrameLayout.LayoutParams(-1, -1).apply {
            topMargin = statusBarH() + dp(102)
            bottomMargin = dp(156) + navBarH()
            leftMargin = dp(10)
            rightMargin = dp(10)
        }
        root.addView(textChatPanel, panelLp)
        addTextBubble("nova", "Salam! Main NOVA hoon. Likhen ya Voice Mode istemaal karein.")

        // ---- neeche: status, chat, cards, buttons ----
        bottomCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(10) + navBarH()) }
        status = TextView(this).apply { text = "Ready"; setTextColor(Color.parseColor("#E3E9F2")); textSize = 12.5f; gravity = Gravity.CENTER; setPadding(dp(18), 0, dp(18), dp(4)); maxLines = 2; setShadowLayer(8f, 0f, 0f, Color.BLACK) }
        bottomCol.addView(status, lp(-1, -2))

        input = EditText(this).apply {
            hint = "NOVA ko likh kar bhejo..."; setHintTextColor(Color.parseColor("#6C7F95")); setTextColor(Color.WHITE); textSize = 14f
            setSingleLine(); imeOptions = EditorInfo.IME_ACTION_SEND; setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_SEND) { sendTyped(); true } else false }
        }
        val sendBtn = GlowCircle(this).apply { icon = "chat"; iconScale = 0.4f; contentDescription = "Send text message"; setOnClickListener { sendTyped() } }
        inputRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE; setPadding(dp(14), 0, dp(8), dp(4)) }
        inputRow.addView(input, lp(0, dp(48), 1f)); inputRow.addView(sendBtn, lp(dp(54), dp(54)))
        bottomCol.addView(inputRow, lp(-1, -2))

        cWeather = InfoCard(this).apply { set("cloud", "Mausam", "--"); setOnClickListener { weatherTap() } }
        cTime = InfoCard(this).apply { set("clock", "Time", "--") }
        cAzan = InfoCard(this).apply { set("moon", "Azan", "--"); setOnClickListener { azanDialog() } }
        cMusic = InfoCard(this).apply { set("music", "Music", "Play"); setOnClickListener { musicTap() }; setOnLongClickListener { musicDialog(); true } }
        cRem = InfoCard(this).apply { set("bell", "Reminder", "--"); setOnClickListener { startActivity(Intent(this@MainActivity, RemindersActivity::class.java)) } }
        val cardRow = LinearLayout(this).apply { setPadding(dp(8), 0, dp(8), 0) }
        for (cd in listOf(cWeather, cTime, cAzan, cMusic, cRem)) cardRow.addView(cd, lp(dp(112), dp(106)))
        cardsScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(cardRow) }
        bottomCol.addView(cardsScroll, lp(-1, -2))

        camBtn = GlowCircle(this).apply { icon = "camera"; contentDescription = "Camera vision"; setOnClickListener { takePhoto() } }
        chatBtn = GlowCircle(this).apply { icon = "chat"; contentDescription = "Toggle text chat mode"; setOnClickListener { toggleChat() } }
        micBtn = GlowCircle(this).apply { icon = "mic"; iconScale = 0.4f; contentDescription = "Start or stop voice assistant"; setOnClickListener { toggle() } }
        muteNovaBtn = GlowCircle(this).apply { icon = "vol_up"; iconScale = 0.46f; contentDescription = "Mute NOVA speaker"; setOnClickListener { Vol.speakerMuted = !Vol.speakerMuted; refreshMute() } }
        muteMicBtn = GlowCircle(this).apply { icon = "mic"; iconScale = 0.46f; contentDescription = "Mute microphone"; setOnClickListener {
            val m = !Vol.micMuted; val c = LiveService.liveClient; if (c != null) c.setMicMuted(m) else Vol.micMuted = m; refreshMute() } }
        val btns = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(dp(6), dp(2), dp(6), 0) }
        btns.addView(camBtn, lp(dp(70), dp(70))); btns.addView(muteNovaBtn, lp(dp(60), dp(60)))
        btns.addView(micBtn, lp(dp(96), dp(96)))
        btns.addView(muteMicBtn, lp(dp(60), dp(60))); btns.addView(chatBtn, lp(dp(70), dp(70)))
        bottomCol.addView(btns, lp(-1, -2))
        micLabel = TextView(this).apply { text = "Tap to speak"; textSize = 12f; gravity = Gravity.CENTER; setTextColor(Color.parseColor("#FFB4D4")); setPadding(0, 0, 0, dp(2)) }
        bottomCol.addView(micLabel, lp(-1, -2))
        root.addView(bottomCol, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        bottomCol.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> ch.setInsets(dp(96), bottomCol.height) }

        // ---- drawer ----
        drawer = FrameLayout(this).apply { visibility = View.GONE }
        val scrim = View(this).apply { setBackgroundColor(Color.parseColor("#99000000")); setOnClickListener { closeDrawer() } }
        drawer.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        drawerPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#F00B0610")); isClickable = true }
        drawerList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pw = minOf(dp(320), (resources.displayMetrics.widthPixels * 0.82f).toInt())
        drawer.addView(drawerPanel, FrameLayout.LayoutParams(pw, -1, Gravity.START))
        root.addView(drawer, FrameLayout.LayoutParams(-1, -1))

        setContentView(root)
        editables = linkedMapOf("menu" to menuBtn, "palette" to paletteBtn, "title" to titleTv, "cards" to cardsScroll, "cam" to camBtn, "mutenova" to muteNovaBtn,
            "mic" to micBtn, "mutemic" to muteMicBtn, "chat" to chatBtn, "status" to status)
        applyTheme()

        val saved = prefs.getInt("vol", 100)
        Vol.boost = if (saved > 100) saved / 100f else 1f
        ClipWatch.autoStart(this)
        askStartupPerms()
        if (prefs.getBoolean("robot", true) && Settings.canDrawOverlays(this)) RobotPet.show(this)
        handleDeepLink(intent)
    }

    // ================= theme =================
    private fun applyTheme() {
        val th = Themes.current(this); accent = th.accent
        ch.setTheme(th); ch.applyPrefs()
        for (g in listOf(menuBtn, paletteBtn, camBtn, chatBtn, micBtn, muteNovaBtn, muteMicBtn)) g.accent = accent
        for (c in listOf(cWeather, cTime, cAzan, cMusic, cRem)) { c.accent = accent; c.invalidate() }
        titleTv.setShadowLayer(28f, 0f, 0f, accent)
        micLabel.setTextColor(accent)
        modeToggleBtn.background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(Color.argb(180, 12, 16, 28)); setStroke(dp(1), accent) }
        input.background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(Color.argb(190, 8, 5, 12)); setStroke(dp(1), accent) }
        buildDrawer(); refreshMute(); paint(); updateCards(true); applyUiPrefs()
    }

    private fun circleBitmap(src: Bitmap?, ring: Int, selected: Boolean): Bitmap {
        val s = 160; val out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888); val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (src != null) {
            val path = Path(); path.addCircle(s / 2f, s / 2f, s / 2f - 6, Path.Direction.CW)
            c.save(); c.clipPath(path); c.drawBitmap(src, null, RectF(0f, 0f, s.toFloat(), s.toFloat()), p); c.restore()
        }
        p.style = Paint.Style.STROKE; p.strokeWidth = if (selected) 8f else 4f; p.color = ring
        c.drawCircle(s / 2f, s / 2f, s / 2f - 5, p)
        return out
    }

    private fun themeDialog() {
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(6), dp(12), dp(6)) }
        list.addView(TextView(this).apply { text = "Background Blur"; setTextColor(Color.WHITE); textSize = 14f; setPadding(dp(6), dp(6), 0, 0) })
        list.addView(SeekBar(this).apply {
            max = 100; progress = prefs.getInt("bgblur", 30)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, pr: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    prefs.edit().putInt("bgblur", pr).apply(); ch.setBlur(pr, 0)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }, lp(-1, -2).apply { bottomMargin = dp(6) })

        val homeLockSwitch = Switch(this).apply {
            text = "Apply to Home + Lock Screen"
            setTextColor(Color.WHITE)
            textSize = 14.5f
            isChecked = WallpaperWidgets.applyHomeLockPref(this@MainActivity)
            minimumHeight = dp(48)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setOnCheckedChangeListener { _, checked -> WallpaperWidgets.setHomeLockPref(this@MainActivity, checked) }
        }
        list.addView(homeLockSwitch, lp(-1, -2).apply { bottomMargin = dp(6) })

        val actionRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        actionRow.addView(chip("Apply Wallpaper Now", true) {
            val both = WallpaperWidgets.applyHomeLockPref(this)
            setStaticWallpaper(if (both) 3 else 1)
        }.apply { minimumHeight = dp(48); gravity = Gravity.CENTER }, lp(-2, dp(48)).apply { rightMargin = dp(8) })
        actionRow.addView(chip("Live Wallpaper", false) { setLiveWallpaper() }.apply { minimumHeight = dp(48); gravity = Gravity.CENTER }, lp(-2, dp(48)))
        list.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(actionRow) }, lp(-1, -2).apply { bottomMargin = dp(10) })

        val allThemes = Themes.all(this)
        val dialog = dlg().setTitle("Wallpaper Widgets (${allThemes.size})").setView(ScrollView(this).apply { addView(list) }).setNegativeButton("Close", null).create()
        val add = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(Color.parseColor("#14101C")); setStroke(dp(2), accent) }
            setOnClickListener { dialog.dismiss(); pickWallpaper() }
        }
        add.addView(IconView(this).apply { icon = "plus"; tint = accent }, lp(dp(28), dp(28)))
        add.addView(TextView(this).apply { text = "Add Custom Background from Gallery"; setTextColor(Color.WHITE); textSize = 14.5f; typeface = Typeface.DEFAULT_BOLD; setPadding(dp(14), 0, 0, 0) }, lp(0, -2, 1f))
        list.addView(add, lp(-1, -2).apply { topMargin = dp(4); bottomMargin = dp(10) })

        val cur = Themes.current(this).id
        for (th in allThemes) {
            val sel = th.id == cur
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(Color.parseColor("#14101C")); setStroke(dp(if (sel) 2 else 1), if (sel) th.accent else Color.parseColor("#33FFFFFF")) }
                setOnClickListener {
                    Themes.set(this@MainActivity, th.id); applyTheme()
                    val both = WallpaperWidgets.applyHomeLockPref(this@MainActivity)
                    setStaticWallpaper(if (both) 3 else 1)
                    dialog.dismiss()
                }
                if (th.custom) setOnLongClickListener {
                    dlg().setMessage("Delete '${th.name}'?").setPositiveButton("Delete") { _, _ -> Themes.deleteCustom(this@MainActivity, th.id); applyTheme(); dialog.dismiss() }.setNegativeButton("No", null).show(); true
                }
            }
            val iv = ImageView(this).apply {
                setImageBitmap(circleBitmap(Themes.thumb(this@MainActivity, th), th.accent, sel))
                contentDescription = "${th.name} wallpaper widget preview"
            }
            val tx = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
            tx.addView(TextView(this).apply { text = th.name; setTextColor(Color.WHITE); textSize = 15.5f; typeface = Typeface.DEFAULT_BOLD })
            tx.addView(TextView(this).apply { text = "${th.category} · " + if (sel) "Selected ✓" else if (th.custom) "Tap = apply · Long-press = delete" else "Tap to preview & apply"; setTextColor(if (sel) th.accent else Color.parseColor("#8C9AB0")); textSize = 12f })
            row.addView(iv, lp(dp(58), dp(58))); row.addView(tx, lp(0, -2, 1f))
            list.addView(row, lp(-1, -2).apply { bottomMargin = dp(8) })
        }
        dialog.show()
    }

    private fun pickWallpaper() {
        try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), REQ_WALL) }
        catch (_: Exception) { status.text = "Gallery nahi khuli" }
    }

    private fun setLiveWallpaper() {
        try {
            startActivity(Intent(android.app.WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                .putExtra(android.app.WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, android.content.ComponentName(this, NovaWallpaperService::class.java)))
        } catch (_: Exception) {
            try { startActivity(Intent(android.app.WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)) } catch (_: Exception) { status.text = "Live wallpaper chooser nahi khula" }
        }
    }

    private fun effectDialog() {
        val names = arrayOf("Auto (theme ke hisaab se)", "🌸 Petals", "🌿 Leaves", "✨ Sparks", "Band")
        val vals = intArrayOf(-1, 0, 1, 2, 3)
        dlg().setTitle("Background effect").setItems(names) { _, i -> prefs.edit().putInt("fx", vals[i]).apply(); Anim.setCurrent(this, null); ch.applyPrefs() }.show()
    }

    // ================= drawer =================
    private fun buildDrawer() {
        drawerPanel.removeAllViews(); drawerList.removeAllViews()
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(44), dp(14), dp(18)) }
        val av = GlowCircle(this).apply { icon = "person"; accent = this@MainActivity.accent; iconScale = 0.42f }
        val nm = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, 0, 0) }
        nm.addView(TextView(this).apply { text = "Nova"; textSize = 24f; setTextColor(Color.WHITE); typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD) })
        nm.addView(TextView(this).apply { text = "YOUR AI COMPANION"; textSize = 10.5f; letterSpacing = 0.15f; setTextColor(Color.parseColor("#9AA6B8")) })
        nm.addView(TextView(this).apply { text = "v14"; textSize = 11f; setTextColor(Color.parseColor("#9AA6B8")) })
        val close = IconView(this).apply { icon = "close"; tint = Color.WHITE; setOnClickListener { closeDrawer() } }
        head.addView(av, lp(dp(70), dp(70))); head.addView(nm, lp(0, -2, 1f)); head.addView(close, lp(dp(26), dp(26)))
        drawerPanel.addView(head, lp(-1, -2))
        val rows = listOf<Triple<String, String, () -> Unit>>(
            Triple("camera", "Camera Vision", { closeDrawer(); takePhoto() }),
            Triple("chat", "Text Mode (No Mic)", { closeDrawer(); setTextMode(true) }),
            Triple("vol_up", "Offline Voice Packs", { closeDrawer(); startActivity(Intent(this, VoicePackActivity::class.java)) }),
            Triple("bell", "Reminders & Namaz Alarms", { closeDrawer(); startActivity(Intent(this, RemindersActivity::class.java)) }),
            Triple("download", "Downloads Vault", { closeDrawer(); startActivity(Intent(this, DownloadsActivity::class.java)) }),
            Triple("bolt", "Code Backups (Zip)", { closeDrawer(); startActivity(Intent(this, BackupsActivity::class.java)) }),
            Triple("music", "Video Player (URL)", { closeDrawer(); videoUrlDialog() }),
            Triple("grid", "Memories / Sikhao", { closeDrawer(); memoriesDialog() }),
            Triple("person", "Profile", { closeDrawer(); startActivity(Intent(this, ProfileActivity::class.java)) }),
            Triple("chat", "Chat History", { closeDrawer(); chatHistoryDialog() }),
            Triple("close", "Failed / ruke hue kaam", { closeDrawer(); failedTasksDialog() }),
            Triple("sun", "Animation Studio", { closeDrawer(); animStudio() }),
            Triple("grid", "Macros (naye features)", { closeDrawer(); macrosDialog() }),
            Triple("tune", "Buttons edit karo", { uiEditToggle() }),
            Triple("tune", "Terminal (Termux)", { closeDrawer(); terminalDialog() }),
            Triple("bolt", "Code Studio", { closeDrawer(); codeStudioDialog() }),
            Triple("lock", "Decode / Encrypt", { closeDrawer(); vaultMenu() }),
            Triple("music", "Music / Playlist", { closeDrawer(); musicDialog() }),
            Triple("cloud", "NOVA Browser", { closeDrawer(); NovaBrowser.openUi(this) }),
            Triple("moon", "Azan / Namaz times", { closeDrawer(); azanDialog() }),
            Triple("palette", "Wallpaper Widgets (32)", { closeDrawer(); themeDialog() }),
            Triple("tune", "Settings", { closeDrawer(); settingsDialog() })
        )
        for ((ic, label, act) in rows) {
            val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(22), dp(16), dp(18), dp(16)); setOnClickListener { act() } }
            r.addView(IconView(this).apply { icon = ic; tint = accent }, lp(dp(24), dp(24)))
            r.addView(TextView(this).apply { text = label; textSize = 16f; setTextColor(Color.WHITE); setPadding(dp(18), 0, 0, 0) }, lp(0, -2, 1f))
            r.addView(IconView(this).apply { icon = "chevron"; tint = Color.parseColor("#8C97A8") }, lp(dp(22), dp(22)))
            drawerList.addView(r, lp(-1, -2))
            drawerList.addView(View(this).apply { setBackgroundColor(Color.parseColor("#22FFFFFF")) }, lp(-1, 1).apply { leftMargin = dp(20); rightMargin = dp(20) })
        }
        drawerPanel.addView(ScrollView(this).apply { addView(drawerList) }, lp(-1, 0, 1f))
    }

    private fun openDrawer() {
        drawer.visibility = View.VISIBLE; drawerPanel.translationX = -dp(340).toFloat()
        ObjectAnimator.ofFloat(drawerPanel, "translationX", -dp(340).toFloat(), 0f).setDuration(220).start()
    }
    private fun closeDrawer() {
        if (drawer.visibility != View.VISIBLE) return
        ObjectAnimator.ofFloat(drawerPanel, "translationX", 0f, -dp(340).toFloat()).setDuration(180).start()
        h.postDelayed({ drawer.visibility = View.GONE }, 190)
    }
    @Deprecated("simple")
    override fun onBackPressed() { if (drawer.visibility == View.VISIBLE) closeDrawer() else super.onBackPressed() }

    // ================= info cards =================
    private fun updateHud(now: Long) {
        val bat = try { (getSystemService(BATTERY_SERVICE) as android.os.BatteryManager).getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) } catch (_: Exception) { 0 }
        val online = Net.online(this)
        ch.hudBat = bat
        ch.hudLeft = listOf("SYS  " + if (online) "ONLINE" else "NO NET", "PWR  $bat%", "MIC  " + if (Vol.micMuted) "MUTED" else "LIVE")
        val mode = if (active) "LISTENING" else "STANDBY"
        ch.hudRight = listOf(fmtTime(now, "HH:mm:ss"), "NOVA  $mode", "LANG  URDU")
    }

    private fun updateCards(all: Boolean = false) {
        val now = System.currentTimeMillis()
        updateHud(now)
        cTime.set("clock", "Time", fmtTime(now))
        val mp = MusicPlayer.current
        cMusic.set("music", if (mp != null) mp.title else "Music", if (MusicPlayer.isPlaying()) "Pause" else "Play")
        if (all || tickN % 15 == 0) {
            val nx = Prayer.nextPrayer(now)
            cAzan.set("moon", if (nx != null) nx.first else "Azan", if (nx != null) fmtTime(nx.second) else "--")
            val ts = TaskStore.all(this).map { it.optLong("at") }.filter { it > now } +
                ReminderStore.all(this).filter { it.enabled && it.nextTriggerMs > now }.map { it.nextTriggerMs }
            cRem.set("bell", "Reminder", if (ts.isEmpty()) "None" else fmtTime(ts.minOrNull() ?: 0L))
        }
        val w = Weather.cached(this)
        if (w != null) cWeather.set(if (w.third <= 1) "sun" else "cloud", w.second, w.first) else cWeather.set("cloud", "Mausam", "--")
    }

    private fun weatherTap() {
        status.text = "Mausam la rahi hoon..."
        Weather.refresh(this, true) { updateCards(true); status.text = Weather.cached(this)?.let { "Dera Ghazi Khan: ${it.first}, ${it.second}" } ?: "Mausam nahi mila (net band?)" }
    }

    private fun musicTap() {
        when {
            MusicPlayer.isPlaying() -> status.text = MusicPlayer.pause()
            MusicPlayer.current != null -> status.text = MusicPlayer.resume()
            else -> { status.text = "Gaane dhoond rahi hoon..."; Thread { val r = MusicPlayer.playQuery(this, "", "random"); h.post { status.text = r } }.start() }
        }
        updateCards()
    }

    private fun musicDialog() {
        val items = listOf<Pair<String, () -> Unit>>(
            "▶ Sab gaane (shuffle)" to { Thread { val r = MusicPlayer.playQuery(this, "", "random"); h.post { status.text = r } }.start() },
            "📦 Sab se bara (MB) pehle" to { Thread { val r = MusicPlayer.playQuery(this, "", "biggest"); h.post { status.text = r } }.start() },
            "⏱ Sab se lamba pehle" to { Thread { val r = MusicPlayer.playQuery(this, "", "longest"); h.post { status.text = r } }.start() },
            "⏯ Pause / Resume" to { status.text = if (MusicPlayer.isPlaying()) MusicPlayer.pause() else MusicPlayer.resume() },
            "⏭ Next" to { status.text = MusicPlayer.next() },
            "⏮ Previous" to { status.text = MusicPlayer.prev() },
            "⏹ Stop" to { status.text = MusicPlayer.stop() },
            "📃 Playlists" to { playlistDialog() }
        )
        dlg().setTitle("🎵 Music").setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }.show()
    }

    private fun playlistDialog() {
        val txt = MusicPlayer.playlistShow(this, "")
        val names = if (txt.startsWith("koi")) emptyList() else txt.lines().map { it.substringBefore(" (") }
        if (names.isEmpty()) { dlg().setTitle("📃 Playlists").setMessage("Abhi koi playlist nahi.\nNOVA se kaho: 'is gaane ko favourite playlist mein daal do'").setPositiveButton("OK", null).show(); return }
        dlg().setTitle("📃 Playlist chalao").setItems(names.toTypedArray()) { _, i -> Thread { val r = MusicPlayer.playlistPlay(this, names[i]); h.post { status.text = r } }.start() }.show()
    }

    private fun reminderDialog() {
        val tasks = TaskStore.all(this).sortedBy { it.optLong("at") }
        if (tasks.isEmpty()) { dlg().setTitle("⏰ Reminders").setMessage("Koi reminder nahi.\nNOVA se kaho: '10 minute baad yaad dilao chai'").setPositiveButton("OK", null).show(); return }
        dlg().setTitle("⏰ Reminders (tap = delete)").setItems(tasks.map { TaskStore.describe(it) }.toTypedArray()) { _, i ->
            val t = tasks[i]
            dlg().setMessage("Ye reminder delete karun?\n${TaskStore.describe(t)}").setPositiveButton("Delete") { _, _ -> TaskStore.cancel(this, t.optInt("id")); TaskStore.remove(this, t.optInt("id")); updateCards(true) }.setNegativeButton("Nahi", null).show()
        }.setNegativeButton("Close", null).show()
    }

    // ================= memories / teach =================
    private fun memoriesDialog() {
        val notes = Memory.notes(this).map { it.substringAfter("|").trim() }
        val msg = if (notes.isEmpty()) "Abhi koi yaad ki hui baat nahi.\n\n'Sikhao' dabao, ya NOVA se bolo:\n'yaad rakho ki duniya ka sab se bara YouTuber MrBeast hai'\nPhir kabhi bhi poocho 'duniya ka sab se bara YouTuber kaun hai' - wo bata degi."
        else notes.takeLast(40).reversed().joinToString("\n\n") { "• $it" }
        dlg().setTitle("🧠 Memories").setMessage(msg)
            .setPositiveButton("Sikhao") { _, _ -> teachDialog() }
            .setNeutralButton("Chat saaf") { _, _ -> Thread { val n = Memory.pruneChat(applicationContext, 0); h.post { status.text = "$n chat lines saaf (yaad ki baatein mehfooz)" } }.start() }
            .setNegativeButton("Close", null).show()
    }

    private fun teachDialog() {
        val et = EditText(this).apply { hint = "Jo yaad rakhwana hai likho"; minLines = 2; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY) }
        val box = LinearLayout(this).apply { setPadding(dp(20), dp(10), dp(20), 0); addView(et, lp(-1, -2)) }
        dlg().setTitle("NOVA ko sikhao").setView(box).setPositiveButton("Yaad rakho") { _, _ ->
            val t = et.text.toString().trim()
            if (t.isNotEmpty()) { Memory.note(this, t); status.text = "Yaad kar liya: $t" }
        }.setNegativeButton("Cancel", null).show()
    }



    // ================= v17: Animation Studio / Depth clock =================
    private fun chip(text: String, selected: Boolean, onClick: () -> Unit) = TextView(this).apply {
        this.text = text; textSize = 13f; setTextColor(if (selected) Color.BLACK else Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); if (selected) setColor(accent) else { setColor(Color.parseColor("#1A1422")); setStroke(dp(1), accent) } }
        setOnClickListener { onClick() }
    }

    private fun animStudio() {
        val studio = android.app.Dialog(this, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#0A0710")) }
        val prev = CharacterView(this).apply { wallpaperMode = true; setTheme(Themes.current(this@MainActivity)); applyPrefs() }
        root.addView(prev, lp(-1, (resources.displayMetrics.heightPixels * 0.38f).toInt()))
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(8), dp(14), dp(24)) }
        root.addView(ScrollView(this).apply { addView(body) }, lp(-1, 0, 1f))
        fun refresh() { prev.applyPrefs(); ch.applyPrefs() }
        fun head(t: String) = body.addView(TextView(this).apply { text = t; setTextColor(accent); textSize = 13f; letterSpacing = 0.2f; typeface = Typeface.MONOSPACE; setPadding(0, dp(14), 0, dp(4)) })
        fun slider(label: String, start: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
            val tv = TextView(this).apply { text = "$label: $start"; setTextColor(Color.WHITE); textSize = 13f }
            body.addView(tv)
            body.addView(SeekBar(this).apply {
                this.max = max - min; progress = start - min
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, pr: Int, fromUser: Boolean) { if (fromUser) { tv.text = "$label: ${pr + min}"; onChange(pr + min) } }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            })
        }
        fun choice(title: String, opts: List<String>, cur: Int, onPick: (Int) -> Unit) {
            body.addView(TextView(this).apply { text = title; setTextColor(Color.WHITE); textSize = 13f; setPadding(0, dp(6), 0, dp(2)) })
            val row = LinearLayout(this)
            val chips = ArrayList<TextView>()
            fun paintAll(sel: Int) { chips.forEachIndexed { i, c -> c.setTextColor(if (i == sel) Color.BLACK else Color.WHITE); c.background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); if (i == sel) setColor(accent) else { setColor(Color.parseColor("#1A1422")); setStroke(dp(1), accent) } } } }
            opts.forEachIndexed { i, o -> val c = chip(o, i == cur) { paintAll(i); onPick(i); refresh() }; chips.add(c); row.addView(c, lp(-2, -2).apply { rightMargin = dp(6) }) }
            body.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) })
        }

        // ---- presets ----
        head("PRESETS")
        val presetRow = LinearLayout(this)
        presetRow.addView(chip("Theme default", Anim.current(this) == null) { Anim.setCurrent(this, null); studio.dismiss(); animStudio() }, lp(-2, -2).apply { rightMargin = dp(6) })
        for ((n, l) in Anim.BUILTIN + Anim.userPresets(this)) presetRow.addView(chip(n, false) { Anim.setCurrent(this, l); studio.dismiss(); animStudio() }, lp(-2, -2).apply { rightMargin = dp(6) })
        body.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(presetRow) })

        // ---- layers ----
        val base = Anim.current(this) ?: Anim.BUILTIN.values.first()
        val ls = ArrayList<Layer>(); for (i in 0 until 3) ls.add(base.getOrNull(i) ?: Layer(0, 0, 100, 100, 100, 0))
        fun commit() { Anim.setCurrent(this, ls.filter { it.count > 0 }); refresh() }
        for (i in 0 until 3) {
            head("LAYER ${i + 1}")
            choice("Type", Anim.KINDS, ls[i].kind) { k -> ls[i] = Layer(k, maxOf(ls[i].count, 12), ls[i].speed, ls[i].size, ls[i].alpha, ls[i].color); commit() }
            slider("Count (0 = band)", ls[i].count, 0, 90) { v -> ls[i] = Layer(ls[i].kind, v, ls[i].speed, ls[i].size, ls[i].alpha, ls[i].color); commit() }
            slider("Speed %", ls[i].speed, 20, 250) { v -> ls[i] = Layer(ls[i].kind, ls[i].count, v, ls[i].size, ls[i].alpha, ls[i].color); commit() }
            slider("Size %", ls[i].size, 40, 250) { v -> ls[i] = Layer(ls[i].kind, ls[i].count, ls[i].speed, v, ls[i].alpha, ls[i].color); commit() }
            slider("Alpha %", ls[i].alpha, 20, 100) { v -> ls[i] = Layer(ls[i].kind, ls[i].count, ls[i].speed, ls[i].size, v, ls[i].color); commit() }
            val hue0 = if (ls[i].color == 0) 0 else { val hsv = FloatArray(3); Color.colorToHSV(ls[i].color, hsv); hsv[0].toInt().coerceIn(1, 360) }
            slider("Rang (0 = theme ka rang)", hue0, 0, 360) { v -> ls[i] = Layer(ls[i].kind, ls[i].count, ls[i].speed, ls[i].size, ls[i].alpha, if (v == 0) 0 else Color.HSVToColor(floatArrayOf(v.toFloat(), 0.8f, 1f))); commit() }
        }

        // ---- depth clock ----
        head("DEPTH CLOCK (time girl ke peeche)")
        choice("Style", listOf("Off", "Stacked", "Outline", "Wide"), prefs.getInt("clock_style", 1)) { prefs.edit().putInt("clock_style", it).apply() }
        choice("Jagah", listOf("Left", "Center", "Right"), prefs.getInt("clock_pos", 1)) { prefs.edit().putInt("clock_pos", it).apply() }
        choice("Rang", listOf("Theme", "White"), if (prefs.getBoolean("clock_white", false)) 1 else 0) { prefs.edit().putBoolean("clock_white", it == 1).apply() }
        slider("Size %", prefs.getInt("clock_size", 100), 50, 140) { prefs.edit().putInt("clock_size", it).apply(); refresh() }
        head("HUD")
        choice("Jarvis rings + gauges (app)", listOf("ON", "OFF"), if (prefs.getBoolean("hud_on", true)) 0 else 1) { prefs.edit().putBoolean("hud_on", it == 0).apply() }
        choice("Live wallpaper par rings", listOf("ON", "OFF"), if (prefs.getBoolean("wp_hud", true)) 0 else 1) { prefs.edit().putBoolean("wp_hud", it == 0).apply() }

        // ---- save / close ----
        head("SAVE")
        val btn = LinearLayout(this)
        btn.addView(chip("💾 Preset save karo", false) {
            val et = EditText(this).apply { hint = "Preset ka naam"; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY) }
            dlg().setTitle("Preset save").setView(LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(et, lp(-1, -2)) })
                .setPositiveButton("Save") { _, _ -> val n = et.text.toString().trim(); if (n.isNotEmpty()) { Anim.savePreset(this, n, ls.filter { it.count > 0 }); status.text = "Preset '$n' save" } }.setNegativeButton("Cancel", null).show()
        }, lp(-2, -2).apply { rightMargin = dp(8) })
        btn.addView(chip("✅ Close", true) { studio.dismiss(); ch.applyPrefs() }, lp(-2, -2))
        body.addView(btn)
        studio.setContentView(root); studio.show()
    }

    // ================= v17: Macros (naye features APK ke andar) =================
    private fun macrosDialog() {
        val ms = Macros.all(this)
        val items = ArrayList<String>(); ms.forEach { items.add("▶ ${it.name}   (${it.triggers.joinToString(", ")})") }
        items.add("➕ Naya macro / feature banao"); items.add("❓ Commands ki list (help)")
        dlg().setTitle("🧩 Macros - apne commands").setItems(items.toTypedArray()) { _, i ->
            when { i < ms.size -> macroEdit(ms[i]); i == ms.size -> macroEdit(null); else -> commandsHelp() }
        }.setNegativeButton("Close", null).show()
    }

    private fun macroEdit(m: Macros.M?) {
        val name = EditText(this).apply { hint = "Macro ka naam (jaise subah_routine)"; setText(m?.name ?: ""); setTextColor(Color.WHITE); setHintTextColor(Color.GRAY) }
        val trig = EditText(this).apply { hint = "Bolne ke alfaaz (comma se): subah ka kaam, good morning routine"; setText(m?.triggers?.joinToString(", ") ?: ""); setTextColor(Color.WHITE); setHintTextColor(Color.GRAY) }
        val steps = EditText(this).apply { hint = "Har line ek kaam:\nopen_app WhatsApp\nwait 2\nspeak Salam boss"; setText(m?.steps?.joinToString("\n") ?: ""); minLines = 6; gravity = Gravity.TOP; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); typeface = Typeface.MONOSPACE }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), 0); addView(name); addView(trig); addView(steps) }
        val b = dlg().setTitle(if (m == null) "Naya macro" else "Macro edit").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                val n = name.text.toString().trim()
                if (n.isEmpty()) status.text = "Naam chahiye" else { Macros.save(this, m?.name, n, trig.text.toString(), steps.text.toString()); status.text = "Macro '$n' save ho gaya" }
            }
            .setNeutralButton("Test chalao") { _, _ ->
                val n = name.text.toString().trim()
                Macros.save(this, m?.name, n, trig.text.toString(), steps.text.toString())
                Thread { val r = Macros.run(applicationContext, n); h.post { status.text = r } }.start()
            }
        if (m != null) b.setNegativeButton("Delete") { _, _ -> Macros.delete(this, m.name); status.text = "Macro hata diya" } else b.setNegativeButton("Cancel", null)
        b.show()
    }

    private fun commandsHelp() {
        val txt = "OFFLINE/ONLINE VOICE COMMANDS (NOVA bolo phir):\n" +
            "• 'torch on/off', 'volume kam/zyada', 'time kya hai', 'battery'\n• 'X ko call karo', 'X ko message karo: ...', 'X ko voice message karo'\n• '10 minute baad yaad dilao ...', 'kal 5 baje calendar mein meeting'\n" +
            "• 'palpal gaana chalao', 'agla gaana', 'gaana band'\n• 'youtube par ... search karo', 'WhatsApp kholo'\n• 'back karo', 'home jao', 'recents', 'screenshot lo', 'notifications kholo', 'neeche scroll karo'\n" +
            "• '5 minute ka timer', 'alarm 6 baje', '12 plus 7', 'sikka uchalo', 'joke sunao', 'shayari sunao', 'phone dhoondo'\n• 'wifi / bluetooth / display / sound settings kholo'\n• 'yaad rakho ki ...', 'mausam', 'namaz ka time'\n• 'ye link copy karke Ali ko bhej do' (Share + Copy khud)\n\n" + Macros.help(this)
        val tv = TextView(this).apply { text = txt; setTextColor(Color.parseColor("#E8EEF7")); textSize = 12f; typeface = Typeface.MONOSPACE; setPadding(dp(16), dp(10), dp(16), dp(10)); setTextIsSelectable(true) }
        dlg().setTitle("❓ Commands & Macro help").setView(ScrollView(this).apply { addView(tv) }).setPositiveButton("OK", null).show()
    }

    // ================= v17: Failed / pending tasks =================
    private fun failedTasksDialog() {
        val fl = FailedTasks.all(this).reversed()
        val items = ArrayList<String>()
        if (PendingTasks.count(this) > 0) items.add("⏳ ${PendingTasks.count(this)} kaam unlock ka intezaar kar rahe - ab chalao")
        fl.forEach { items.add("❌ " + it.optString("text").take(60) + "\n    wajah: " + it.optString("reason").take(70)) }
        if (items.isEmpty()) { dlg().setTitle("Failed tasks").setMessage("Koi failed ya ruka hua kaam nahi. 👍").setPositiveButton("OK", null).show(); return }
        val offset = if (PendingTasks.count(this) > 0) 1 else 0
        dlg().setTitle("❌ Failed / ruke hue kaam").setItems(items.toTypedArray()) { _, i ->
            if (offset == 1 && i == 0) { PendingTasks.runNext(this); status.text = "Ruka hua kaam shuru kar diya"; return@setItems }
            val t = fl[i - offset]
            dlg().setMessage(t.optString("text") + "\n\nWajah: " + t.optString("reason")).setPositiveButton("🔁 Retry") { _, _ -> FailedTasks.retry(this, t.optInt("id")); status.text = "Dobara try kar rahi hoon..." }
                .setNegativeButton("Delete") { _, _ -> FailedTasks.remove(this, t.optInt("id")) }.setNeutralButton("Close", null).show()
        }.setNegativeButton("Close", null).show()
    }

    // ================= v17: Home / Lock screen wallpaper sync =================
    private fun wallpaperTargetDialog() {
        val names = arrayOf("Home + Lock Screen (Recommended)", "Home Screen only", "Lock Screen only", "Live Wallpaper (matches active style)")
        dlg().setTitle("Apply Wallpaper").setItems(names) { _, i ->
            when (i) {
                0 -> { WallpaperWidgets.setHomeLockPref(this, true); setStaticWallpaper(3) }
                1 -> { WallpaperWidgets.setHomeLockPref(this, false); setStaticWallpaper(1) }
                2 -> { WallpaperWidgets.setHomeLockPref(this, false); setStaticWallpaper(2) }
                else -> setLiveWallpaper()
            }
        }.setNegativeButton("Close", null).show()
    }

    private fun setStaticWallpaper(flag: Int) {
        status.text = "Applying wallpaper..."
        try {
            val dm = resources.displayMetrics; val w = dm.widthPixels; val hh = dm.heightPixels
            val v = CharacterView(this).apply { wallpaperMode = true; setTheme(Themes.current(this@MainActivity)); applyPrefs(); forceNoClock = true }
            v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(hh, View.MeasureSpec.EXACTLY)); v.layout(0, 0, w, hh)
            val bmp = Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(bmp))
            val both = (flag == 3)
            val lockOnly = (flag == 2)
            Thread {
                val msg = WallpaperWidgets.applySystemWallpaper(applicationContext, bmp, both, lockOnly)
                h.post { status.text = msg }
            }.start()
        } catch (e: Throwable) { status.text = "Wallpaper error: ${e.message}" }
    }

    private fun videoUrlDialog() {
        val et = EditText(this).apply {
            hint = "Enter MP4, WebM, or .m3u8 URL"
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); setSingleLine()
        }
        val box = LinearLayout(this).apply { setPadding(dp(20), dp(10), dp(20), 0); addView(et, lp(-1, -2)) }
        dlg().setTitle("Custom Video Player").setView(box).setPositiveButton("Play") { _, _ ->
            val u = et.text.toString().trim()
            if (u.isNotBlank()) startActivity(Intent(this, VideoPlayerActivity::class.java).putExtra("url", u))
        }.setNegativeButton("Cancel", null).show()
    }

    // ================= v17: buttons edit (size / jagah / aage-peeche) =================
    private var editing = false
    private var selName = "mic"
    private var editBar: LinearLayout? = null
    private lateinit var editables: LinkedHashMap<String, View>
    private val labels = mapOf("menu" to "Menu ☰", "palette" to "Palette", "title" to "NOVA title", "cards" to "Cards row", "cam" to "Camera", "mutenova" to "Mute NOVA", "mic" to "Mic (bara)", "mutemic" to "Mute Mic", "chat" to "Chat", "status" to "Status text")

    private fun applyUiPrefs() {
        if (!::editables.isInitialized) return
        for ((n, v) in editables) {
            val vis = prefs.getBoolean("ui_${n}_v", true)
            val s = prefs.getInt("ui_${n}_s", 100) / 100f
            v.scaleX = s; v.scaleY = s
            v.translationX = prefs.getFloat("ui_${n}_x", 0f); v.translationY = prefs.getFloat("ui_${n}_y", 0f); v.translationZ = prefs.getFloat("ui_${n}_z", 0f)
            v.visibility = if (vis || editing) View.VISIBLE else View.GONE
            v.alpha = if (!vis && editing) 0.3f else 1f
        }
    }

    private fun resetUi() { val e = prefs.edit(); for (n in editables.keys) { e.remove("ui_${n}_s"); e.remove("ui_${n}_x"); e.remove("ui_${n}_y"); e.remove("ui_${n}_z"); e.remove("ui_${n}_v") }; e.apply(); applyUiPrefs() }

    private fun uiEditToggle() {
        if (editing) { // Done
            editing = false; editBar?.let { rootFrame.removeView(it) }; editBar = null
            for (v in editables.values) { v.setOnTouchListener(null); v.foreground = null }
            applyUiPrefs(); status.text = "Layout save ho gaya"; return
        }
        editing = true; applyUiPrefs()
        closeDrawer()
        val bar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), statusBarH() + dp(6), dp(12), dp(10)); background = GradientDrawable().apply { setColor(Color.argb(235, 10, 7, 16)); setStroke(dp(1), accent) } }
        val title = TextView(this).apply { setTextColor(Color.WHITE); textSize = 14f; typeface = Typeface.DEFAULT_BOLD }
        val sizeTv = TextView(this).apply { setTextColor(accent); textSize = 12f }
        val sizeBar = SeekBar(this).apply { max = 160 }
        fun refreshBar() { title.text = "✏ EDIT MODE - ${labels[selName]}  (ungli se kheencho)"; val s = prefs.getInt("ui_${selName}_s", 100); sizeBar.progress = s - 40; sizeTv.text = "Size: $s%" }
        fun nudge(dx: Float, dy: Float) { val v = editables[selName] ?: return; v.translationX += dx; v.translationY += dy; prefs.edit().putFloat("ui_${selName}_x", v.translationX).putFloat("ui_${selName}_y", v.translationY).apply() }
        sizeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, pr: Int, fromUser: Boolean) { if (fromUser) { prefs.edit().putInt("ui_${selName}_s", pr + 40).apply(); applyUiPrefs(); sizeTv.text = "Size: ${pr + 40}%" } }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        val r1 = LinearLayout(this); val r2 = LinearLayout(this)
        fun btn(l: LinearLayout, t: String, a: () -> Unit) = l.addView(chip(t, false) { a(); refreshBar() }, lp(-2, -2).apply { rightMargin = dp(6); topMargin = dp(6) })
        btn(r1, "⬅") { nudge(-dp(10).toFloat(), 0f) }; btn(r1, "➡") { nudge(dp(10).toFloat(), 0f) }; btn(r1, "⬆") { nudge(0f, -dp(10).toFloat()) }; btn(r1, "⬇") { nudge(0f, dp(10).toFloat()) }
        btn(r1, "Aage") { prefs.edit().putFloat("ui_${selName}_z", 40f).apply(); editables[selName]?.bringToFront(); applyUiPrefs() }
        btn(r1, "Peeche") { prefs.edit().putFloat("ui_${selName}_z", 0f).apply(); applyUiPrefs() }
        btn(r2, "👁 Hide/Show") { prefs.edit().putBoolean("ui_${selName}_v", !prefs.getBoolean("ui_${selName}_v", true)).apply(); applyUiPrefs() }
        btn(r2, "↺ Isko reset") { val e = prefs.edit(); e.remove("ui_${selName}_s"); e.remove("ui_${selName}_x"); e.remove("ui_${selName}_y"); e.remove("ui_${selName}_z"); e.remove("ui_${selName}_v"); e.apply(); applyUiPrefs() }
        btn(r2, "↺ Sab reset") { resetUi() }
        btn(r2, "✅ Done") { uiEditToggle() }
        bar.addView(title); bar.addView(sizeTv); bar.addView(sizeBar); bar.addView(HorizontalScrollView(this).apply { addView(r1) }); bar.addView(HorizontalScrollView(this).apply { addView(r2) })
        rootFrame.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP)); editBar = bar
        for ((n, v) in editables) {
            var lx = 0f; var ly = 0f
            v.setOnTouchListener { vv, e ->
                when (e.action) {
                    android.view.MotionEvent.ACTION_DOWN -> { selName = n; lx = e.rawX; ly = e.rawY
                        for ((k, o) in editables) o.foreground = if (k == n) GradientDrawable().apply { setStroke(dp(2), Color.YELLOW); cornerRadius = dp(14).toFloat() } else null
                        refreshBar() }
                    android.view.MotionEvent.ACTION_MOVE -> { vv.translationX += e.rawX - lx; vv.translationY += e.rawY - ly; lx = e.rawX; ly = e.rawY }
                    android.view.MotionEvent.ACTION_UP -> prefs.edit().putFloat("ui_${n}_x", vv.translationX).putFloat("ui_${n}_y", vv.translationY).apply()
                }
                true
            }
        }
        refreshBar()
    }


    // ================= v18: Terminal / Code Studio / Decode+Encrypt / Voice =================
    private fun terminalDialog() {
        val d = android.app.Dialog(this, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK); setPadding(dp(8), statusBarH() + dp(6), dp(8), dp(8)) }
        val log = TextView(this).apply { setTextColor(Color.parseColor("#7CFF9B")); typeface = Typeface.MONOSPACE; textSize = 12f; setTextIsSelectable(true); text = "NOVA Terminal (Termux)\nPehli baar: Termux mein  echo \"allow-external-apps=true\" >> ~/.termux/termux.properties  phir Termux dobara kholo.\n" }
        val sv = ScrollView(this).apply { addView(log) }
        root.addView(sv, lp(-1, 0, 1f))
        val input = EditText(this).apply { hint = "command, jaise: pkg update -y"; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); setSingleLine(); typeface = Typeface.MONOSPACE }
        fun append(t: String) { log.append(t); sv.post { sv.fullScroll(View.FOCUS_DOWN) } }
        fun runCmd(cmd: String, visible: Boolean) {
            if (cmd.isBlank()) return
            append("\n\$ $cmd\n⏳ chal raha hai (khatam hone par poora output yahin aayega)...\n")
            Thread { val r = try { TermuxBridge.run(applicationContext, cmd, visible) } catch (e: Throwable) { "error: ${e.message}" }; runOnUiThread { append(r + "\n") } }.start()
        }
        val chips = LinearLayout(this)
        for (c in listOf("pkg update -y", "pkg upgrade -y", "python3 --version", "ls", "pwd", "df -h")) chips.addView(chip(c, false) { input.setText(c) }, lp(-2, -2).apply { rightMargin = dp(6) })
        root.addView(HorizontalScrollView(this).apply { addView(chips) }, lp(-1, -2))
        root.addView(input, lp(-1, -2))
        val row = LinearLayout(this)
        row.addView(chip("▶ Yahan chalao", true) { runCmd(input.text.toString().trim(), false); input.setText("") }, lp(-2, -2).apply { rightMargin = dp(6) })
        row.addView(chip("🪟 Termux window", false) { runCmd(input.text.toString().trim(), true) }, lp(-2, -2).apply { rightMargin = dp(6) })
        row.addView(chip("🧹 Clear", false) { log.text = "" }, lp(-2, -2).apply { rightMargin = dp(6) })
        row.addView(chip("✖ Close", false) { d.dismiss() }, lp(-2, -2))
        root.addView(HorizontalScrollView(this).apply { addView(row) }, lp(-1, -2))
        d.setContentView(root); d.show()
    }

    private fun codeStudioDialog() {
        val et = EditText(this).apply { hint = "Kya banana hai? jaise: ek stylish calculator web page ya python se prime numbers"; minLines = 3; gravity = Gravity.TOP; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY) }
        val who = EditText(this).apply { hint = "Link/output kisko bhejna hai (WhatsApp naam, optional)"; setSingleLine(); setTextColor(Color.WHITE); setHintTextColor(Color.GRAY) }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), 0); addView(et); addView(who) }
        dlg().setTitle("💻 Code Studio (AI likhe, chalaye, error khud fix kare)").setView(box)
            .setPositiveButton("Banao + Chalao") { _, _ ->
                val task = et.text.toString().trim(); val to = who.text.toString().trim()
                if (task.isEmpty()) { status.text = "Pehle likho kya banana hai"; return@setPositiveButton }
                status.text = "Code Studio shuru..."
                Thread {
                    val o = try { CodeStudio.auto(applicationContext, task) { p -> h.post { status.text = p } } } catch (e: Throwable) { CodeStudio.Out(false, "", "", "error: ${e.message}", "") }
                    var sent = ""
                    if (to.isNotEmpty() && o.ok) sent = Tools.execute(applicationContext, "whatsapp_send", JSONObject().put("who", to).put("message", if (o.link.isNotEmpty()) o.link else o.log.take(500)))
                    h.post { status.text = if (o.ok) "Code chal gaya ✅" else "Code fail ❌"; showCodeResult(o, sent) }
                }.start()
            }.setNegativeButton("Close", null).show()
    }

    private fun showCodeResult(o: CodeStudio.Out, sent: String) {
        val tv = TextView(this).apply {
            text = (if (o.link.isNotEmpty()) "LINK (isi Wi-Fi par sab khol sakte hain):\n${o.link}\n\n" else "") + (if (sent.isNotEmpty()) "Bheja: $sent\n\n" else "") + "OUTPUT:\n${o.log.take(3000)}\n\nCODE (${o.lang}):\n${o.code.take(6000)}"
            setTextColor(Color.parseColor("#E8EEF7")); textSize = 12f; typeface = Typeface.MONOSPACE; setPadding(dp(14), dp(10), dp(14), dp(10)); setTextIsSelectable(true)
        }
        val b = dlg().setTitle(if (o.ok) "✅ Code chal gaya" else "❌ Code fail").setView(ScrollView(this).apply { addView(tv) }).setNegativeButton("Close", null)
        if (o.link.isNotEmpty()) b.setPositiveButton("Open link") { _, _ -> try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(o.link.replace(Regex("//[^:/]+:"), "//127.0.0.1:")))) } catch (_: Exception) {} }
        b.setNeutralButton("Code copy") { _, _ -> ClipStore.putOnClipboard(this, o.code); status.text = "Code copy ho gaya" }
        b.show()
    }

    private var fileMode = "decode"
    private fun vaultMenu() {
        val items = arrayOf("📋 Code paste karke kholo (decode)", "📁 File / zip se decode", "🔒 File encrypt (password se)", "🔓 File decrypt (.nova)")
        dlg().setTitle("🔐 Decode / Encrypt").setItems(items) { _, i ->
            when (i) {
                0 -> pasteDecode()
                else -> { fileMode = arrayOf("", "decode", "enc", "dec")[i]; try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_FILE) } catch (_: Exception) { status.text = "File picker nahi khula" } }
            }
        }.setNegativeButton("Close", null).show()
    }

    private fun pasteDecode() {
        val et = EditText(this).apply { hint = "Yahan code paste karo (base64/zlib/hex wali parton wala)"; minLines = 8; gravity = Gravity.TOP; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); typeface = Typeface.MONOSPACE; textSize = 11f }
        dlg().setTitle("📋 Code decode").setMessage("Sirf apni ya jin files ki ijazat ho unhi par. Asli encryption (key ke bina) nahi khulti.").setView(ScrollView(this).apply { addView(et) })
            .setPositiveButton("Decode") { _, _ ->
                val src = et.text.toString()
                Thread { val r = try { Decoder.unwrap(src) } catch (e: Throwable) { Decoder.Result("", emptyList(), "error: ${e.message}") }; h.post { showDecoded(r) } }.start()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showDecoded(r: Decoder.Result) {
        val head = if (r.steps.isEmpty()) "Kuch decode nahi hua.\n" else "Layers khuli: " + r.steps.joinToString(" > ") + "\n"
        val tv = TextView(this).apply { text = head + (if (r.note.isNotBlank()) r.note + "\n" else "") + "\n" + r.text.take(20000); setTextColor(Color.parseColor("#E8EEF7")); textSize = 11.5f; typeface = Typeface.MONOSPACE; setPadding(dp(14), dp(10), dp(14), dp(10)); setTextIsSelectable(true) }
        dlg().setTitle("🔓 Decoded").setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("NOVA-FILES mein save") { _, _ ->
                try { val f = java.io.File(Backup.sub("decoded") ?: filesDir, "decoded_" + System.currentTimeMillis() % 100000 + ".txt"); f.writeText(r.text); status.text = "Save: " + f.path } catch (e: Exception) { status.text = "Save nahi hua: ${e.message}" }
            }.setNeutralButton("Copy") { _, _ -> ClipStore.putOnClipboard(this, r.text); status.text = "Copy ho gaya" }.setNegativeButton("Close", null).show()
    }

    private fun handlePickedFile(uri: Uri) {
        val name = try { contentResolver.query(uri, null, null, null, null)?.use { c -> val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME); if (c.moveToFirst() && i >= 0) c.getString(i) else null } } catch (_: Exception) { null } ?: "file"
        if (fileMode == "decode") {
            status.text = "Decode ho rahi hai..."
            Thread {
                val msg = try {
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                    val (outName, data, note) = Decoder.processFile(name, bytes)
                    if (data.isNotEmpty()) { val f = java.io.File(Backup.sub("decoded") ?: filesDir, outName); f.writeBytes(data); "$note Save: ${f.path}" } else note
                } catch (e: Throwable) { "Decode nahi hui: ${e.message}" }
                h.post { status.text = msg; dlg().setTitle("Decode result").setMessage(msg).setPositiveButton("OK", null).show() }
            }.start(); return
        }
        val et = EditText(this).apply { hint = "Password"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY) }
        dlg().setTitle(if (fileMode == "enc") "🔒 Encrypt: $name" else "🔓 Decrypt: $name").setMessage("Password bhoolo to file wapas nahi aayegi.").setView(LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(et, lp(-1, -2)) })
            .setPositiveButton("OK") { _, _ ->
                val pw = et.text.toString()
                if (pw.length < 4) { status.text = "Password kam az kam 4 harf"; return@setPositiveButton }
                status.text = "Kaam ho raha hai..."
                Thread {
                    val msg = try {
                        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                        val dir = Backup.sub("vault") ?: filesDir
                        if (fileMode == "enc") { val f = java.io.File(dir, "$name.nova"); f.writeBytes(Vault.encrypt(bytes, pw)); "Encrypt ho gaya: " + f.path }
                        else { val out = Vault.decrypt(bytes, pw); if (out == null) "Password ghalat hai ya ye NOVA ki file nahi" else { val f = java.io.File(dir, name.removeSuffix(".nova").ifEmpty { "decrypted" }); f.writeBytes(out); "Decrypt ho gaya: " + f.path } }
                    } catch (e: Throwable) { "Nahi ho saka: ${e.message}" }
                    h.post { status.text = msg; dlg().setTitle("Vault").setMessage(msg).setPositiveButton("OK", null).show() }
                }.start()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun voiceStudioDialog() {
        val key = EditText(this).apply { hint = "ElevenLabs API key"; setSingleLine(); setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val vid = EditText(this).apply { hint = "Voice ID (aapki clone ki hui awaaz ka)"; setText(CloudVoice.savedVoice(this@MainActivity)); setSingleLine(); setTextColor(Color.WHITE); setHintTextColor(Color.GRAY) }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), 0); addView(key); addView(vid) }
        dlg().setTitle("🎙 Apni AI awaaz").setMessage("1) elevenlabs.io par video/audio se awaaz clone karo ya koi awaaz chuno.\n2) Wahan se API key aur Voice ID copy karke yahan daalo.\nYe reminder, offline mode aur likhe jawab bolne mein chalegi (net ho to). Seedhi Gemini baat-cheet ki awaaz Google ki hi rehti hai.").setView(box)
            .setPositiveButton("Save + ON") { _, _ ->
                val k = key.text.toString().trim().ifEmpty { "" }
                if (k.isEmpty() && !CloudVoice.enabled(this)) { status.text = "API key chahiye"; return@setPositiveButton }
                CloudVoice.save(this, if (k.isEmpty()) Crypto.dec(prefs.getString("cv_key", "") ?: "") else k, vid.text.toString(), true)
                status.text = "AI awaaz ON - test kar rahi hoon..."
                Thread { val ok = CloudVoice.speak(applicationContext, "Salam, main NOVA hoon. Ye meri nayi awaaz hai."); h.post { status.text = if (ok) "AI awaaz chal gayi ✅" else "Awaaz nahi chali - key/Voice ID/net check karo" } }.start()
            }.setNeutralButton("OFF") { _, _ -> prefs.edit().putBoolean("cv_on", false).apply(); status.text = "AI awaaz OFF" }.setNegativeButton("Cancel", null).show()
    }

    // ================= chat history / offline zuban / awaaz =================
    private fun chatHistoryDialog() {
        val lines = ChatLog.last(this, 300)
        val tv = TextView(this).apply {
            text = if (lines.isEmpty()) "Abhi koi chat nahi." else lines.joinToString("\n\n")
            setTextColor(Color.parseColor("#E8EEF7")); textSize = 12f; typeface = Typeface.MONOSPACE; setPadding(dp(16), dp(10), dp(16), dp(10)); setTextIsSelectable(true)
        }
        dlg().setTitle("💬 Chat History (" + ChatLog.count(this) + ")").setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("Export (NOVA-FILES)") { _, _ -> Thread { val r = Backup.backupToDownloads(applicationContext); h.post { status.text = r } }.start() }
            .setNegativeButton("Close", null).show()
    }

    // ================= settings (purane saare features) =================
    private fun onoff(b: Boolean) = if (b) "ON ✅" else "OFF"

    private fun settingsDialog() {
        fun flip(k: String, def: Boolean) { prefs.edit().putBoolean(k, !prefs.getBoolean(k, def)).apply() }
        val sections = listOf(
            SSection("NOVA CONTROL", listOf(
                SItem("mic", "Only NOVA mode", { "Sirf 'NOVA' bolne par jawab" }, null, { startMode(true) }),
                SItem("bolt", "Always listen mode", { "Hamesha sunti rahe" }, null, { startMode(false) }),
                SItem("close", "Off NOVA", { "NOVA band karo" }, null, { offNova() }),
                SItem("vol_up", "Volume", { "100 ke upar = extra boost" }, null, { volumeDialog() })
            )),
            SSection("NOVA V30 FEATURES", FeatureUi.items(this, status, h) { settingsDialog() }),
            SSection("LOOK & FEEL", listOf(
                SItem("palette", "Wallpaper Widgets", { Themes.current(this).name + " · " + Themes.all(this).size + " widgets" }, null, { themeDialog() }),
                SItem("plus", "Add Custom Background", { "Gallery image saved locally" }, null, { pickWallpaper() }),
                SItem("sun", "Background effect", { when (prefs.getInt("fx", -1)) { 0 -> "Petals"; 1 -> "Leaves"; 2 -> "Sparks"; 3 -> "Band"; else -> "Auto" } }, null, { effectDialog() }),
                SItem("image", "Apply Wallpaper (Home + Lock)", { if (WallpaperWidgets.applyHomeLockPref(this)) "Home + Lock synced" else "Manual target" }, null, { wallpaperTargetDialog() }),
                SItem("tune", "Live wallpaper mein Jarvis rings", { "Wallpaper par animation rings" }, { prefs.getBoolean("wp_hud", true) }, { flip("wp_hud", true) }),
                SItem("person", "Robot Companion", { "Lottie companion in-app & overlay" }, { prefs.getBoolean("robot", true) }, { toggleRobot() }),
                SItem("chat", "Floating button", { "Hamesha dikhne wala NOVA button" }, { prefs.getBoolean("bubble", true) }, { toggleBubble() })
            )),
            SSection("TOOLS", listOf(
                SItem("tune", "Terminal (Termux)", { "Command chalao, output yahin dekho" }, null, { terminalDialog() }),
                SItem("bolt", "Code Studio", { "AI code likhe, chalaye, error fix kare, link bheje" }, null, { codeStudioDialog() }),
                SItem("lock", "Decode / Encrypt", { "Code kholo, file encrypt/decrypt" }, null, { vaultMenu() }),
                SItem("vol_up", "Apni AI awaaz", { if (CloudVoice.enabled(this)) "ON - ElevenLabs" else "OFF - video wali awaaz lagao" }, { CloudVoice.enabled(this) }, { voiceStudioDialog() }),
                SItem("vol_up", "Awaaz ka raasta", { if (prefs.getInt("audio_route", 0) == 0) "Media: volume keys + Bluetooth ✅" else "Call (speaker)" }, { prefs.getInt("audio_route", 0) == 0 }, { prefs.edit().putInt("audio_route", if (prefs.getInt("audio_route", 0) == 0) 1 else 0).apply(); status.text = "NOVA ko dobara Start karo" }),
                SItem("bolt", "Fast mode", { "Kam animation, zyada raftaar" }, { prefs.getBoolean("fast_mode", false) }, { prefs.edit().putBoolean("fast_mode", !prefs.getBoolean("fast_mode", false)).apply(); ch.applyPrefs() })
            )),
            SSection("ANIMATION & DEPTH", listOf(
                SItem("sun", "Animation Studio", { "Live animation khud banao + depth clock" }, null, { animStudio() }),
                SItem("clock", "Home / Lock wallpaper alag", { "Har jagah alag picture" }, null, { wallpaperTargetDialog() }),
                SItem("tune", "Buttons edit karo", { "Size, jagah, aage-peeche, hide" }, null, { uiEditToggle() })
            )),
            SSection("AUTOMATION", listOf(
                SItem("grid", "Macros - naye features", { Macros.all(this).size.toString() + " macros - APK ke andar banao" }, null, { macrosDialog() }),
                SItem("close", "Failed / ruke hue kaam", { FailedTasks.all(this).size.toString() + " failed, " + PendingTasks.count(this) + " intezaar mein" }, null, { failedTasksDialog() }),
                SItem("bell", "Reminders", { TaskStore.all(this).size.toString() + " active" }, null, { reminderDialog() }),
                SItem("chat", "Commands ki list", { "Sab voice commands + macro help" }, null, { commandsHelp() })
            )),
            SSection("DATA & FILES", listOf(
                SItem("chat", "Chat History", { ChatLog.count(this).toString() + " lines - offline bhi dekho" }, null, { chatHistoryDialog() }),
                SItem("grid", "NOVA Clipboard", { "Copy kiye links / text" }, null, { clipDialog() }),
                SItem("download", "Backup abhi", { "NOVA-FILES mein backup + export" }, null, { Thread { val r = Backup.backupToDownloads(this); h.post { status.text = r } }.start() }),
                SItem("folder", "NOVA-FILES", { Backup.dir()?.path ?: "/storage/emulated/0/NOVA-FILES" }, null, { status.text = "Sab kuch: " + (Backup.dir()?.path ?: "/storage/emulated/0/NOVA-FILES") })
            )),
            SSection("SECURITY & SYSTEM", listOf(
                SItem("lock", "Screen Lock PIN", { if (Unlocker.hasPin(this)) "PIN saved ✅" else "PIN nahi diya" }, null, { pinDialog() }),
                SItem("lock", "Test unlock", { "Lock karke khol ke dekho" }, null, { testUnlock() }),
                SItem("moon", "Azan alert", { "Dera Ghazi Khan ke hisaab se" }, { prefs.getBoolean("azan", true) }, { toggleAzan() }),
                SItem("bolt", "Ads auto-skip", { "YouTube ads skip" }, { prefs.getBoolean("adskip", true) }, { flip("adskip", true) }),
                SItem("mic", "App khulte hi NOVA sune", { "Auto start" }, { prefs.getBoolean("auto", false) }, { flip("auto", false) }),
                SItem("camera", "Gallery se photo bhejo", { "NOVA ko photo dikhao" }, null, { pickPhoto() }),
                SItem("folder", "Storage permission", { "All files access" }, null, { storagePerm() }),
                SItem("tune", "Setup", { "API key, Accessibility" }, null, { startActivity(Intent(this, SetupActivity::class.java)) })
            ))
        )
        SettingsPanel.show(this, accent, sections)
    }

    private fun toggleRobot() {
        val on = !prefs.getBoolean("robot", true); RobotPet.setEnabled(this, on)
        inAppRobot?.visibility = if (on) View.VISIBLE else View.GONE
        if (on && !Settings.canDrawOverlays(this)) startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun toggleBubble() {
        val v = !prefs.getBoolean("bubble", true); prefs.edit().putBoolean("bubble", v).apply()
        if (v && !Settings.canDrawOverlays(this)) startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        status.text = "Floating button ${onoff(v)} (NOVA dobara Start karo)"
    }

    private fun toggleAzan() {
        val v = !prefs.getBoolean("azan", true); prefs.edit().putBoolean("azan", v).apply()
        if (v) Prayer.scheduleNext(this) else Prayer.cancelAll(this)
        status.text = "Azan alert ${onoff(v)}"
    }

    private fun storagePerm() {
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            try { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
            catch (_: Exception) { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        } else status.text = "Storage permission pehle se hai"
    }

    private fun volumeDialog() {
        val saved = prefs.getInt("vol", 100)
        val bar = SeekBar(this).apply {
            max = 200; progress = saved
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) { applyVol(p); prefs.edit().putInt("vol", p).apply() } }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        val box = LinearLayout(this).apply { setPadding(dp(20), dp(16), dp(20), dp(8)); addView(bar, lp(-1, -2)) }
        dlg().setTitle("NOVA volume (100 ke upar = extra boost)").setView(box).setPositiveButton("OK", null).show()
    }

    private fun pinDialog() {
        val et = EditText(this).apply {
            hint = if (Unlocker.hasPin(this@MainActivity)) "PIN saved hai - naya daalo (khali = hata do)" else "Phone ka PIN / password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
        }
        val box = LinearLayout(this).apply { setPadding(dp(20), dp(10), dp(20), 0); addView(et, lp(-1, -2)) }
        dlg().setTitle("🔒 Screen Lock PIN")
            .setMessage("PIN sirf is phone mein encrypt hokar rehta hai, backup file mein nahi jata. Sirf PIN/password lock chalta hai (pattern/fingerprint nahi).")
            .setView(box)
            .setPositiveButton("Save") { _, _ -> Unlocker.savePin(this, et.text.toString().trim()); status.text = if (et.text.isNullOrBlank()) "PIN hata diya" else "PIN save ho gaya ✅" }
            .setNegativeButton("Cancel", null).show()
    }

    private fun testUnlock() {
        status.text = "5 second mein screen lock hogi, phir NOVA unlock karegi..."
        Thread {
            Thread.sleep(1500); val a = Unlocker.lockNow()
            Thread.sleep(4500)
            val r = Unlocker.unlock(applicationContext)
            h.post { status.text = "$a / $r" }
        }.start()
    }

    private fun azanDialog() {
        dlg().setTitle("🕌 Dera Ghazi Khan, Punjab").setMessage(Prayer.todayText() + "\n\n(Karachi method, Hanafi Asr - masjid ki jantri se 1-3 min ka farq ho sakta hai)")
            .setPositiveButton("OK", null).show()
    }

    private fun clipDialog() {
        ClipStore.saveCurrent(this)
        val l = ClipStore.all(this).takeLast(30).reversed()
        if (l.isEmpty()) { dlg().setTitle("📋 NOVA Clipboard").setMessage("Khali hai. Kuch copy karo, phir NOVA kholo.").setPositiveButton("OK", null).show(); return }
        dlg().setTitle("📋 NOVA Clipboard (tap = copy)").setItems(l.map { ClipStore.describe(it) }.toTypedArray()) { _, i ->
            ClipStore.putOnClipboard(this, l[i].optString("text")); status.text = "Clipboard par rakh diya - ab kahin bhi paste karo"
        }.setNeutralButton("Sab saaf") { _, _ -> File(filesDir, "clips.json").delete(); Backup.replace("clips.json", "[]") }.show()
    }

    // ================= lifecycle =================
    private fun askStartupPerms() {
        val need = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.READ_MEDIA_AUDIO)
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.READ_MEDIA_VIDEO)
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.POST_NOTIFICATIONS)
        } else if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        if (need.isNotEmpty() && !prefs.getBoolean("asked_media", false)) { prefs.edit().putBoolean("asked_media", true).apply(); requestPermissions(need.toTypedArray(), REQ_MEDIA) }
    }

    override fun onResume() {
        super.onResume()
        active = LiveService.isRunning()
        if (active) status.text = LiveService.lastStatus
        LiveService.onStatus = { status.text = it }
        LiveService.onSpeaking = { ch.speaking = it }
        LiveService.onEnd = { active = false; ch.speaking = false; paint() }
        refreshMute(); paint(); updateCards(true)
        Weather.refresh(this) { updateCards(true) }
        tickRun?.let { h.removeCallbacks(it) }
        tickRun = object : Runnable { override fun run() { tickN++; updateCards(); if (tickN % 900 == 0) Weather.refresh(this@MainActivity) { updateCards(true) }; h.postDelayed(this, 1000) } }
        h.post(tickRun!!)
        if (!active && !autoTried && prefs.getBoolean("auto", false) &&
            (prefs.getString("k", "") ?: "").isNotEmpty() && SetupActivity.accessibilityOn(this) &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { autoTried = true; start() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) try { ClipStore.readNow(this)?.let { ClipStore.add(this, it) } } catch (_: Exception) {}
    }

    override fun onPause() {
        tickRun?.let { h.removeCallbacks(it) }
        LiveService.onStatus = null; LiveService.onSpeaking = null; LiveService.onEnd = null
        super.onPause()
    }

    private fun refreshMute() {
        val red = Color.parseColor("#FF4D5E")
        muteNovaBtn.icon = if (Vol.speakerMuted) "vol_off" else "vol_up"; muteNovaBtn.tint = if (Vol.speakerMuted) red else null
        muteMicBtn.icon = if (Vol.micMuted) "mic_off" else "mic"; muteMicBtn.tint = if (Vol.micMuted) red else null
    }

    private fun paint() {
        micBtn.active = active
        micLabel.text = if (active) "Listening... tap to stop" else "Tap to speak"
    }

    override fun onDestroy() {
        Vol.removeSpeakListener(robotSpeakListener)
        try { activeAudioPlayer?.release() } catch (_: Exception) {}
        activeAudioPlayer = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        val mime = intent.type ?: ""

        // PART 9A: WhatsApp / system Audio share (voice note, .ogg/.opus/.m4a/.mp3)
        if (action == Intent.ACTION_SEND && (mime.startsWith("audio/") || mime.equals("application/ogg", ignoreCase = true))) {
            val streamUri: Uri? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            }
            if (streamUri != null) {
                handleSharedAudio(streamUri, mime)
                return
            }
        }

        // PART 9B: WhatsApp / Chrome text share or nova://assistant deep link
        val req = ChromeBridge.parseIncomingIntent(intent) ?: return
        val prompt = ChromeBridge.buildAssistantPrompt(req)
        setTextMode(true)
        if (action == Intent.ACTION_SEND && mime == "text/plain" && prompt.isNotBlank()) {
            sendToTextChat(prompt)
            status.text = "Shared text sent to NOVA Text Mode"
            return
        }
        if (prompt.isNotBlank()) {
            input.setText(prompt)
            input.setSelection(prompt.length)
        }
        input.post {
            input.requestFocus()
            if (input.text.isNotEmpty()) input.setSelection(input.text.length)
            try {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            } catch (_: Exception) {}
        }
        status.text = if (req.url.isNotBlank()) "Chrome se link/text aa gaya - Send dabao" else "Chrome se text aa gaya - Send dabao"
    }

    // ================= Part 8 & 9: Text-Only Mode + Audio Share =================
    private fun setTextMode(on: Boolean) {
        textMode = on
        modeToggleBtn.text = if (on) "Text Mode ✓" else "Voice Mode"
        textChatPanel.visibility = if (on) View.VISIBLE else View.GONE
        cardsScroll.visibility = if (on) View.GONE else View.VISIBLE
        inputRow.visibility = if (on || inputRow.visibility == View.VISIBLE) View.VISIBLE else View.GONE
        if (on) {
            if (LiveService.isRunning()) {
                // Keep Voice Mode mic strictly off in Text Mode
                stopLive()
            }
            status.text = "Text Mode (No mic, no LiveService)"
            input.requestFocus()
            chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
        } else {
            status.text = if (active) LiveService.lastStatus else "Ready"
        }
    }

    private fun toggleChat(forceOpen: Boolean = false) {
        if (forceOpen) {
            setTextMode(true)
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, 0)
            return
        }
        setTextMode(!textMode)
        if (textMode) {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, 0)
        }
    }

    private fun addTextBubble(role: String, text: String): TextView {
        val isUser = role == "user"
        val wrap = LinearLayout(this).apply {
            gravity = if (isUser) Gravity.END else Gravity.START
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val tv = TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(Color.WHITE)
            setTextIsSelectable(true)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                if (isUser) {
                    setColor(Color.argb(220, 48, 88, 160))
                } else {
                    setColor(Color.argb(230, 18, 22, 38))
                    setStroke(dp(1), accent)
                }
            }
        }
        val maxW = (resources.displayMetrics.widthPixels * 0.78f).toInt()
        tv.maxWidth = maxW
        wrap.addView(tv, lp(-2, -2))
        chatList.addView(wrap, lp(-1, -2))
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
        return tv
    }

    private fun sendTyped() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        input.setText("")
        if (!textMode && active) {
            LiveService.sendText(t)
            return
        }
        if (!textMode) setTextMode(true)
        sendToTextChat(t)
    }

    private fun sendToTextChat(prompt: String) {
        addTextBubble("user", prompt)
        textTurns.add("user" to prompt)
        ChatLog.add("user", prompt)
        val replyView = addTextBubble("nova", "Thinking...")
        status.text = "Generating reply..."

        Thread {
            val replyText = try {
                when {
                    AiRouter.isHostingRequest(prompt) -> {
                        AiRouter.openHosting(applicationContext, prompt)
                    }
                    AiRouter.isCodeRequest(prompt) -> {
                        val res = AiRouter.routeCodeTask(applicationContext, prompt) { p -> h.post { status.text = p } }
                        if (res.ok) {
                            "${res.message}\nBackup folder: ${res.backupDir}\nZip: ${res.zipPath}\n\n${res.code.take(1500)}"
                        } else {
                            res.message
                        }
                    }
                    else -> callGeminiTextOnly(prompt)
                }
            } catch (e: Throwable) {
                "Error: ${e.message}"
            }
            textTurns.add("model" to replyText)
            ChatLog.add("nova", replyText)
            h.post {
                replyView.text = replyText
                status.text = "Ready"
                chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
            }
            if (speakReplies && !Vol.speakerMuted && replyText.isNotBlank()) {
                VoiceEngine.speakBlocking(applicationContext, replyText.take(600))
            }
        }.start()
    }

    private fun callGeminiTextOnly(prompt: String): String {
        val sys = Personas.girl.prompt(prefs.getString("user_name", "Ishaq") ?: "Ishaq")
        val contents = JSONArray()
        for ((role, txt) in textTurns.takeLast(12)) {
            val r = if (role == "user") "user" else "model"
            contents.put(JSONObject().put("role", r).put("parts", JSONArray().put(JSONObject().put("text", txt))))
        }
        if (contents.length() == 0) {
            contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt))))
        }
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sys))))
            .put("contents", contents)
        val res = GeminiRest.post(applicationContext, body)
        if (!res.ok) return res.text
        return extractGeminiText(res.text)
    }

    private fun extractGeminiText(rawJson: String): String {
        return try {
            val cands = JSONObject(rawJson).optJSONArray("candidates")
            val parts = cands?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            val sb = StringBuilder()
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val t = parts.optJSONObject(i)?.optString("text", "") ?: ""
                    if (t.isNotBlank()) sb.append(t)
                }
            }
            sb.toString().ifBlank { "No text response returned." }
        } catch (_: Exception) {
            rawJson.take(1500)
        }
    }

    private fun handleSharedAudio(uri: Uri, rawMime: String) {
        setTextMode(true)
        status.text = "Loading shared voice note..."
        Thread {
            try {
                val rawType = (contentResolver.getType(uri) ?: rawMime).ifBlank { "audio/ogg" }
                val resolvedMime = if (rawType.equals("application/ogg", ignoreCase = true)) "audio/ogg" else rawType
                val ext = when {
                    resolvedMime.contains("mp4") || resolvedMime.contains("m4a") -> "m4a"
                    resolvedMime.contains("mpeg") || resolvedMime.contains("mp3") -> "mp3"
                    resolvedMime.contains("wav") -> "wav"
                    else -> "ogg"
                }
                var displayName = "voice_note.$ext"
                try {
                    contentResolver.query(uri, null, null, null, null)?.use { c ->
                        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (c.moveToFirst() && idx >= 0) displayName = c.getString(idx) ?: displayName
                    }
                } catch (_: Exception) {}

                val localFile = File(cacheDir, "shared_audio_${System.currentTimeMillis()}.$ext")
                contentResolver.openInputStream(uri)?.use { input ->
                    localFile.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("Cannot read shared audio URI")

                h.post {
                    addAudioCard(displayName, localFile)
                    val replyView = addTextBubble("nova", "Listening & transcribing audio...")
                    Thread {
                        val b64 = Base64.encodeToString(localFile.readBytes(), Base64.NO_WRAP)
                        val body = JSONObject()
                            .put("contents", JSONArray().put(
                                JSONObject().put("role", "user").put("parts", JSONArray()
                                    .put(JSONObject().put("text", "Transcribe this WhatsApp/shared voice message and reply helpfully in Urdu/Hinglish."))
                                    .put(JSONObject().put("inlineData", JSONObject().put("mimeType", resolvedMime).put("data", b64))))
                            ))
                        val res = GeminiRest.post(applicationContext, body)
                        val ans = if (res.ok) extractGeminiText(res.text) else res.text
                        h.post {
                            replyView.text = ans
                            status.text = "Audio processed"
                            chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
                        }
                    }.start()
                }
            } catch (e: Throwable) {
                h.post {
                    status.text = "Audio share failed: ${e.message}"
                    addTextBubble("nova", "Could not open shared audio: ${e.message}")
                }
            }
        }.start()
    }

    private fun addAudioCard(title: String, audioFile: File) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.argb(220, 32, 46, 76))
                setStroke(dp(1), accent)
            }
        }
        val playBtn = Button(this).apply {
            text = "▶ Play"
            setTextColor(Color.WHITE)
            isAllCaps = false
            minimumHeight = dp(48)
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.parseColor("#1D3557")) }
        }
        playBtn.setOnClickListener {
            val cur = activeAudioPlayer
            if (cur != null && cur.isPlaying) {
                try { cur.stop(); cur.release() } catch (_: Exception) {}
                activeAudioPlayer = null
                playBtn.text = "▶ Play"
                return@setOnClickListener
            }
            try {
                activeAudioPlayer?.release()
                val mp = MediaPlayer().apply {
                    setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    setDataSource(audioFile.absolutePath)
                    setOnCompletionListener {
                        playBtn.text = "▶ Play"
                        try { release() } catch (_: Exception) {}
                        activeAudioPlayer = null
                    }
                    prepare()
                    start()
                }
                activeAudioPlayer = mp
                playBtn.text = "⏹ Stop"
            } catch (e: Exception) {
                status.text = "Audio play error: ${e.message}"
            }
        }
        val info = TextView(this).apply {
            text = "🎙 $title (${maxOf(1L, audioFile.length() / 1024)} KB)"
            setTextColor(Color.WHITE)
            textSize = 13.5f
            setPadding(dp(10), 0, 0, 0)
        }
        card.addView(playBtn, lp(-2, dp(48)))
        card.addView(info, lp(0, -2, 1f))
        chatList.addView(card, lp(-1, -2).apply { topMargin = dp(4); bottomMargin = dp(4) })
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun camFile() = File(cacheDir, "cam.jpg")

    private fun takePhoto() {
        if (!active) { status.text = "Camera Vision ke liye pehle mic button dabao (NOVA Start)"; return }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAM_PERM); return }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fp", camFile())
            startActivityForResult(Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION), REQ_CAM)
        } catch (e: Exception) { status.text = "Camera nahi khul saka: ${e.message}" }
    }

    private fun pickPhoto() {
        if (!active) { status.text = "Pehle mic button dabao (NOVA Start)"; return }
        try { startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), REQ_PICK) }
        catch (_: Exception) { status.text = "Gallery nahi khuli" }
    }

    @Deprecated("simple")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (res != RESULT_OK) return
        if (req == REQ_FILE) { val u = data?.data ?: return; handlePickedFile(u); return }
        if (req == REQ_WALL) {
            val u = data?.data ?: return
            status.text = "Picture add ho rahi hai..."
            Thread {
                val t = Themes.addCustom(applicationContext, u)
                runOnUiThread {
                    if (t != null) { Themes.set(this, t.id); applyTheme(); status.text = "Nayi picture lag gayi: ${t.name} (APK mein save)" }
                    else status.text = "Picture add nahi ho saki"
                }
            }.start()
            return
        }
        val uri: Uri? = if (req == REQ_CAM) Uri.fromFile(camFile()) else data?.data
        if (uri == null) return
        Thread {
            val jpg = Media.jpegFromUri(this, uri)
            runOnUiThread {
                if (jpg != null) { LiveService.sendPhoto(jpg); status.text = "Photo bhej di - ab poocho 'isme kya hai?'" }
                else status.text = "Photo khul nahi saki"
            }
        }.start()
    }

    /** 0-100 = phone ki awaaz, 100-200 = poori awaaz + NOVA ki awaaz ka extra boost. */
    private fun applyVol(p: Int) {
        val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        val f = if (p <= 100) p / 100f else 1f
        for (st in intArrayOf(android.media.AudioManager.STREAM_VOICE_CALL, android.media.AudioManager.STREAM_MUSIC)) {
            try { am.setStreamVolume(st, Math.round(am.getStreamMaxVolume(st) * f), 0) } catch (_: Exception) {}
        }
        Vol.boost = if (p > 100) p / 100f else 1f
    }

    private fun toggle() {
        if (active) { stopLive(); status.text = "Stopped" } else start()
    }

    private fun start() {
        if (!Net.online(this)) { status.text = "Internet nahi hai - WiFi / Mobile data ON karo"; return }
        if ((prefs.getString("k", "") ?: "").isEmpty()) {
            status.text = "Pehle Setup mein Gemini API key daalo"
            startActivity(Intent(this, SetupActivity::class.java)); return
        }
        val need = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isNotEmpty()) { requestPermissions(need.toTypedArray(), 1); return }
        if (!SetupActivity.accessibilityOn(this)) {
            status.text = "Accessibility ON karo (Setup), phir wapas aake mic dabao"
            startActivity(Intent(this, SetupActivity::class.java)); return
        }
        active = true
        status.text = "Connecting..."
        startForegroundService(Intent(this, LiveService::class.java))
        paint()
    }

    private fun startMode(wake: Boolean) {
        prefs.edit().putBoolean("wake", wake).putBoolean("college", prefs.getBoolean("college", false) && wake).apply()
        if (wake && !WakeWord.available(this)) status.text = "Is phone par wake-word nahi chalta - normal mode mein chalegi"
        if (LiveService.isRunning()) {
            stopLive(); status.text = "Mode badal rahi hoon..."
            h.postDelayed({ start() }, 700)
        } else start()
    }

    private fun offNova() { stopLive(); status.text = "NOVA band (Off)"; paint() }

    private fun stopLive() {
        try { LiveService.liveClient?.stop() } catch (_: Exception) {}
        try { LiveService.instance?.stopSelf() } catch (_: Exception) {}
        stopService(Intent(this, LiveService::class.java))
        active = false; ch.speaking = false
        paint()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == REQ_MEDIA) return
        if (code == REQ_CAM_PERM) { if (res.firstOrNull() == PackageManager.PERMISSION_GRANTED) takePhoto(); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
        else status.text = "Mic permission chahiye"
    }
}
