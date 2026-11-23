package com.nova.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.io.File

/**
 * In-app Downloads Manager showing resumable Direct & HLS (.m3u8) downloads,
 * progress bars, Pause/Resume/Play in VideoPlayerActivity/Delete controls.
 */
class DownloadsActivity : Activity() {
    private lateinit var listLayout: LinearLayout
    private lateinit var folderTv: TextView
    private var accent = Color.parseColor("#00E5FF")

    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()
    private fun lp(w: Int, h: Int, wt: Float = 0f) = LinearLayout.LayoutParams(w, h, wt)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DownloadVault.init(this)
        val th = Themes.current(this)
        accent = th.accent
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
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val back = Button(this).apply {
            text = "←"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            minimumWidth = dp(48); minimumHeight = dp(48)
            contentDescription = "Back"
            setOnClickListener { finish() }
        }
        val title = TextView(this).apply {
            text = "Downloads Manager"
            setTextColor(Color.WHITE)
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(6), 0, 0, 0)
        }
        val addBtn = Button(this).apply {
            text = "+ URL"
            setTextColor(Color.BLACK)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            minimumHeight = dp(48)
            background = GradientDrawable().apply { setColor(accent); cornerRadius = dp(18).toFloat() }
            setOnClickListener { promptAddUrl() }
        }
        top.addView(back, lp(dp(48), dp(48)))
        top.addView(title, lp(0, -2, 1f))
        top.addView(addBtn, lp(-2, dp(48)))
        root.addView(top, lp(-1, -2))

        folderTv = TextView(this).apply {
            text = "Saved to: " + DownloadVault.outputDir(this@DownloadsActivity).path
            setTextColor(Color.parseColor("#8FA3BF"))
            textSize = 12f
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        root.addView(folderTv, lp(-1, -2))

        listLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(4), dp(14), dp(24))
        }
        val scroll = ScrollView(this).apply { addView(listLayout) }
        root.addView(scroll, lp(-1, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        DownloadVault.onUpdate = { render() }
        render()
    }

    override fun onPause() {
        DownloadVault.onUpdate = null
        super.onPause()
    }

    private fun promptAddUrl() {
        val et = EditText(this).apply {
            hint = "Direct video URL (.mp4 / .webm / .m3u8)"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        val box = LinearLayout(this).apply { setPadding(dp(18), dp(8), dp(18), 0); addView(et, lp(-1, -2)) }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Download Video / File")
            .setView(box)
            .setPositiveButton("Download") { _, _ ->
                val u = et.text.toString().trim()
                if (u.isNotEmpty()) {
                    val t = DownloadVault.enqueue(this, u)
                    if (t.status == DlStatus.UNSUPPORTED) {
                        Toast.makeText(this, t.error, Toast.LENGTH_LONG).show()
                    }
                    render()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun render() {
        listLayout.removeAllViews()
        val list = DownloadVault.tasks.toList()
        if (list.isEmpty()) {
            listLayout.addView(TextView(this).apply {
                text = "No downloads yet.\nUse NOVA Browser video sniffer or tap + URL above.\n(Note: YouTube downloads are unsupported.)"
                setTextColor(Color.parseColor("#9AA6B8"))
                textSize = 14f
                setPadding(dp(8), dp(36), dp(8), dp(12))
                gravity = Gravity.CENTER
            }, lp(-1, -2))
            return
        }

        for (t in list) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#141E33"))
                    cornerRadius = dp(16).toFloat()
                    setStroke(dp(1), Color.parseColor("#2A3B5C"))
                }
            }
            card.addView(TextView(this).apply {
                text = t.title.ifBlank { t.url.take(50) }
                setTextColor(Color.WHITE)
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
            })
            val sizeStr = when {
                t.type == DlType.HLS && t.totalSegments > 0 ->
                    "${t.nextSegment}/${t.totalSegments} segments (${Formatter.formatShortFileSize(this, t.downloadedBytes)})"
                t.totalBytes > 0 ->
                    "${Formatter.formatShortFileSize(this, t.downloadedBytes)} / ${Formatter.formatShortFileSize(this, t.totalBytes)}"
                else -> Formatter.formatShortFileSize(this, t.downloadedBytes)
            }
            val stColor = when (t.status) {
                DlStatus.DONE -> Color.parseColor("#38F9D7")
                DlStatus.FAILED, DlStatus.UNSUPPORTED -> Color.parseColor("#FF5C77")
                DlStatus.RUNNING -> accent
                else -> Color.parseColor("#FBBF24")
            }
            card.addView(TextView(this).apply {
                text = "${t.status.name} • ${t.type.name} • $sizeStr" + if (t.error.isNotBlank()) "\n${t.error}" else ""
                setTextColor(stColor)
                textSize = 12.5f
                setPadding(0, dp(4), 0, dp(6))
            })

            val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = when {
                    t.status == DlStatus.DONE -> 100
                    t.type == DlType.HLS && t.totalSegments > 0 -> (t.nextSegment * 100 / t.totalSegments).coerceIn(0, 100)
                    t.totalBytes > 0 -> ((t.downloadedBytes * 100L) / t.totalBytes).toInt().coerceIn(0, 100)
                    else -> 0
                }
                visibility = if (t.status == DlStatus.DONE) View.GONE else View.VISIBLE
            }
            card.addView(pb, lp(-1, dp(6)).apply { bottomMargin = dp(8) })

            val actions = LinearLayout(this).apply { gravity = Gravity.START }
            fun pill(label: String, col: Int, onClick: () -> Unit): Button = Button(this).apply {
                text = label
                textSize = 12f
                setTextColor(Color.BLACK)
                typeface = Typeface.DEFAULT_BOLD
                minimumHeight = dp(48)
                setPadding(dp(12), 0, dp(12), 0)
                background = GradientDrawable().apply { setColor(col); cornerRadius = dp(12).toFloat() }
                setOnClickListener { onClick() }
            }

            if (t.status == DlStatus.RUNNING || t.status == DlStatus.QUEUED) {
                actions.addView(pill("Pause", Color.parseColor("#FBBF24")) { DownloadVault.pause(this, t.id) },
                    lp(-2, dp(48)).apply { rightMargin = dp(8) })
            } else if (t.status == DlStatus.PAUSED || t.status == DlStatus.FAILED) {
                actions.addView(pill("Resume", accent) { DownloadVault.resume(this, t.id) },
                    lp(-2, dp(48)).apply { rightMargin = dp(8) })
            }
            if (t.status == DlStatus.DONE && t.filePath.isNotBlank() && File(t.filePath).exists()) {
                actions.addView(pill("Play", Color.parseColor("#38F9D7")) {
                    startActivity(
                        Intent(this, VideoPlayerActivity::class.java)
                            .putExtra("url", Uri.fromFile(File(t.filePath)).toString())
                            .putExtra("title", t.title)
                    )
                }, lp(-2, dp(48)).apply { rightMargin = dp(8) })
            }
            actions.addView(pill("Delete", Color.parseColor("#FF5C77")) { DownloadVault.delete(this, t.id, true) },
                lp(-2, dp(48)))
            card.addView(actions, lp(-1, -2))
            listLayout.addView(card, lp(-1, -2).apply { bottomMargin = dp(10) })
        }
    }
}
