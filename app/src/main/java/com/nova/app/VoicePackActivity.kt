package com.nova.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.widget.*

/**
 * Voice Pack Manager:
 * - Lists 6 voices (5 female + 1 male)
 * - Auto-registers prebundled offline voices on first launch
 * - Downloads/installs extra voice packs to filesDir/voices/ with progress bar, delete, re-download, test playback
 * - Links to device System TTS offline language data & ElevenLabs optional cloud voice
 */
class VoicePackActivity : Activity() {
    private lateinit var listCol: LinearLayout
    private lateinit var statusTv: TextView
    private val h = Handler(Looper.getMainLooper())
    private val accent by lazy { Themes.current(this).accent }

    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#0A0D16")
        window.navigationBarColor = Color.parseColor("#0A0D16")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0A0D16"))
            fitsSystemWindows = true
        }

        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#111726"))
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val back = TextView(this).apply {
            text = "←"; textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            minWidth = dp(48); minHeight = dp(48); contentDescription = "Back"
            setOnClickListener { finish() }
        }
        val title = TextView(this).apply {
            text = "Voice Pack Manager"
            textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            setPadding(dp(8), 0, 0, 0)
        }
        val sysTtsBtn = TextView(this).apply {
            text = "System TTS"; textSize = 12f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(accent); gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))
            minHeight = dp(48)
            setOnClickListener {
                try { startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) }
                catch (_: Exception) { Toast.makeText(this@VoicePackActivity, "TTS installer unavailable", Toast.LENGTH_SHORT).show() }
            }
        }
        bar.addView(back)
        bar.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(sysTtsBtn)
        root.addView(bar)

        statusTv = TextView(this).apply {
            text = "Offline voices stored in filesDir/voices (5 Female, 1 Male)"
            textSize = 12f; setTextColor(Color.parseColor("#9BA8BD"))
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        root.addView(statusTv)

        listCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(4), dp(14), dp(24))
        }
        root.addView(ScrollView(this).apply { addView(listCol) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        render()
    }

    private fun render() {
        listCol.removeAllViews()
        val selected = VoiceEngine.selectedId(this)
        val voices = VoiceEngine.all(this)
        for (v in voices) {
            val isSel = v.id == selected
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(Color.parseColor("#131B2E"))
                    setStroke(dp(if (isSel) 2 else 1), if (isSel) accent else Color.parseColor("#2A3652"))
                }
            }

            val topRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            val nameTv = TextView(this).apply {
                text = "${v.name} (${v.gender.replaceFirstChar { it.uppercase() }})"
                textSize = 16f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (isSel) accent else Color.WHITE)
            }
            val sizeTv = TextView(this).apply {
                text = "${v.sizeKb} KB"
                textSize = 12f; setTextColor(Color.parseColor("#8FA0B8"))
            }
            topRow.addView(nameTv, LinearLayout.LayoutParams(0, -2, 1f))
            topRow.addView(sizeTv)
            card.addView(topRow)

            val descTv = TextView(this).apply {
                text = "${v.description} • ${v.localeTag}" + (if (v.installed) " • Installed" else " • Not downloaded")
                textSize = 12.5f; setTextColor(Color.parseColor("#B0BED2"))
                setPadding(0, dp(4), 0, dp(8))
            }
            card.addView(descTv)

            val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100; progress = 0; visibility = View.GONE
            }
            card.addView(pb, LinearLayout.LayoutParams(-1, dp(6)).apply { bottomMargin = dp(8) })

            val actions = LinearLayout(this).apply { gravity = Gravity.START }
            fun btn(label: String, filled: Boolean, onClick: () -> Unit): TextView = TextView(this).apply {
                text = label; textSize = 12.5f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                minHeight = dp(48); setPadding(dp(14), dp(8), dp(14), dp(8))
                setTextColor(if (filled) Color.BLACK else Color.WHITE)
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    if (filled) setColor(accent) else { setColor(Color.parseColor("#1D2842")); setStroke(dp(1), Color.parseColor("#3B4D75")) }
                }
                setOnClickListener { onClick() }
            }

            if (v.installed) {
                actions.addView(btn(if (isSel) "Selected ✓" else "Use Voice", isSel) {
                    VoiceEngine.select(this, v.id)
                    statusTv.text = "Selected voice: ${v.name}"
                    render()
                }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })

                actions.addView(btn("Test", false) {
                    VoiceEngine.select(this, v.id)
                    statusTv.text = "Testing ${v.name}..."
                    Thread {
                        VoiceEngine.speakBlocking(applicationContext, "Salam, main ${v.name} hoon. Aap kaise hain?")
                        h.post { statusTv.text = "Finished test: ${v.name}" }
                    }.start()
                    render()
                }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })

                actions.addView(btn("Re-dl", false) {
                    pb.visibility = View.VISIBLE; pb.progress = 0
                    Thread {
                        val ok = VoiceEngine.downloadPack(applicationContext, v.id) { p -> h.post { pb.progress = p } }
                        h.post {
                            pb.visibility = View.GONE
                            statusTv.text = if (ok) "Re-downloaded ${v.name}" else "Download failed"
                            render()
                        }
                    }.start()
                }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })

                if (!v.bundled) {
                    actions.addView(btn("Delete", false) {
                        VoiceEngine.deletePack(this, v.id)
                        statusTv.text = "Deleted ${v.name}"
                        render()
                    }, LinearLayout.LayoutParams(-2, -2))
                }
            } else {
                actions.addView(btn("Download (${v.sizeKb} KB)", true) {
                    pb.visibility = View.VISIBLE; pb.progress = 0
                    statusTv.text = "Downloading ${v.name}..."
                    Thread {
                        val ok = VoiceEngine.downloadPack(applicationContext, v.id) { p -> h.post { pb.progress = p } }
                        h.post {
                            pb.visibility = View.GONE
                            if (ok) {
                                VoiceEngine.select(this, v.id)
                                statusTv.text = "Installed & selected ${v.name}"
                            } else {
                                statusTv.text = "Download failed for ${v.name}"
                            }
                            render()
                        }
                    }.start()
                }, LinearLayout.LayoutParams(-2, -2))
            }

            card.addView(actions)
            listCol.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        }
    }
}
