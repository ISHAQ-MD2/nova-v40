package com.nova.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * Code Backups Viewer & Zip Exporter:
 * Lists projects stored in filesDir/code_backups/ and exported zips in Download/NOVA-Backups/.
 */
class BackupsActivity : Activity() {
    private lateinit var listLayout: LinearLayout
    private var accent = Color.parseColor("#00E5FF")

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
            text = "Code Backups"
            setTextColor(Color.WHITE); textSize = 19f; typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(6), 0, 0, 0)
        }
        top.addView(back, lp(dp(48), dp(48)))
        top.addView(title, lp(0, -2, 1f))
        root.addView(top, lp(-1, -2))

        root.addView(TextView(this).apply {
            text = "Exports folder: " + AiRouter.zipExportDir(this@BackupsActivity).path
            setTextColor(Color.parseColor("#8FA3BF")); textSize = 12f
            setPadding(dp(16), dp(8), dp(16), dp(8))
        })

        listLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(4), dp(14), dp(24)) }
        root.addView(ScrollView(this).apply { addView(listLayout) }, lp(-1, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        listLayout.removeAllViews()
        val dirs = AiRouter.backupsRoot(this).listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (dirs.isEmpty()) {
            listLayout.addView(TextView(this).apply {
                text = "No code backups yet.\nAsk NOVA to generate code (e.g. 'Write a calculator in HTML with DeepSeek') and it will back up here automatically."
                setTextColor(Color.parseColor("#9AA6B8")); textSize = 14f; gravity = Gravity.CENTER
                setPadding(dp(12), dp(40), dp(12), dp(12))
            })
            return
        }

        val zipDir = AiRouter.zipExportDir(this)
        for (dir in dirs) {
            val files = dir.listFiles()?.filter { it.isFile } ?: emptyList()
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#141E33")); cornerRadius = dp(16).toFloat(); setStroke(dp(1), Color.parseColor("#2A3B5C"))
                }
            }
            card.addView(TextView(this).apply {
                text = dir.name; setTextColor(Color.WHITE); textSize = 15f; typeface = Typeface.DEFAULT_BOLD
            })
            card.addView(TextView(this).apply {
                text = "${files.size} file(s): " + files.joinToString(", ") { it.name }
                setTextColor(Color.parseColor("#8FA3BF")); textSize = 12.5f; setPadding(0, dp(4), 0, dp(8))
            })

            val row = LinearLayout(this)
            fun pill(label: String, col: Int, onClick: () -> Unit): Button = Button(this).apply {
                text = label; textSize = 12f; setTextColor(Color.BLACK); typeface = Typeface.DEFAULT_BOLD
                minimumHeight = dp(48); setPadding(dp(12), 0, dp(12), 0)
                background = GradientDrawable().apply { setColor(col); cornerRadius = dp(12).toFloat() }
                setOnClickListener { onClick() }
            }
            row.addView(pill("View / Copy", accent) { viewFiles(dir, files) }, lp(-2, dp(48)).apply { rightMargin = dp(8) })
            row.addView(pill("Share Zip", Color.parseColor("#38F9D7")) { shareZip(dir, zipDir) }, lp(-2, dp(48)).apply { rightMargin = dp(8) })
            row.addView(pill("Delete", Color.parseColor("#FF5C77")) {
                dir.deleteRecursively()
                render()
            }, lp(-2, dp(48)))
            card.addView(row)
            listLayout.addView(card, lp(-1, -2).apply { bottomMargin = dp(10) })
        }
    }

    private fun viewFiles(dir: File, files: List<File>) {
        if (files.isEmpty()) return
        val names = files.map { it.name }.toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(dir.name)
            .setItems(names) { _, idx ->
                val f = files[idx]
                val content = try { f.readText().take(25_000) } catch (_: Exception) { "" }
                val tv = TextView(this).apply {
                    text = content; setTextColor(Color.parseColor("#E8EEF7")); typeface = Typeface.MONOSPACE; textSize = 11.5f
                    setPadding(dp(14), dp(10), dp(14), dp(10)); setTextIsSelectable(true)
                }
                AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(f.name)
                    .setView(ScrollView(this).apply { addView(tv) })
                    .setPositiveButton("Copy All") { _, _ ->
                        try {
                            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(f.name, content))
                            Toast.makeText(this, "Copied ${f.name}", Toast.LENGTH_SHORT).show()
                        } catch (_: Exception) {}
                    }
                    .setNegativeButton("Close", null).show()
            }.show()
    }

    private fun shareZip(dir: File, zipDir: File) {
        val suffix = dir.name.substringBefore('_')
        val found = zipDir.listFiles()?.firstOrNull { it.name.endsWith(".zip") && it.name.contains(suffix) }
        val target = found ?: File(cacheDir, "${dir.name}.zip").also { zf ->
            java.util.zip.ZipOutputStream(zf.outputStream()).use { zos ->
                dir.listFiles()?.forEach { f ->
                    if (f.isFile) {
                        zos.putNextEntry(java.util.zip.ZipEntry(f.name))
                        f.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fp", target)
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, "Export Code Zip"))
        } catch (e: Exception) {
            Toast.makeText(this, "Share failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
