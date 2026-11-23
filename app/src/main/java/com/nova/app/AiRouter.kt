package com.nova.app

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Smart AI Router:
 * - Detects requested AI provider (DeepSeek [default], ChatGPT, Claude, Gemini, Grok)
 * - Delegates code requests through NOVA Browser WebView (checks real login first)
 * - Saves project code in filesDir/code_backups/<timestamp>_<project>/ and exports zip to Download/NOVA-Backups/
 * - Optional hosting helper ONLY when user explicitly asks to host/deploy (opens web dashboards in NOVA Browser)
 */
object AiRouter {
    data class CodeResult(
        val ok: Boolean,
        val provider: String,
        val needsLogin: Boolean = false,
        val code: String = "",
        val backupDir: String = "",
        val zipPath: String = "",
        val message: String = ""
    )

    private val main = Handler(Looper.getMainLooper())

    fun detectProvider(ctx: Context, prompt: String): AiProvider {
        val low = prompt.lowercase()
        return when {
            low.contains("chatgpt") || low.contains("gpt") || low.contains("openai") -> ChatGPTProvider()
            low.contains("claude") || low.contains("anthropic") -> ClaudeProvider()
            low.contains("gemini") -> GeminiProvider()
            low.contains("grok") -> GrokProvider()
            low.contains("deepseek") -> DeepSeekProvider()
            else -> {
                val def = ctx.getSharedPreferences("nova", Context.MODE_PRIVATE).getString("ai_default", "deepseek")
                if (def == "claude") ClaudeProvider() else DeepSeekProvider()
            }
        }
    }

    fun isCodeRequest(prompt: String): Boolean {
        val t = prompt.lowercase()
        val keys = listOf(
            "write code", "generate code", "code likho", "code banao", "create app", "build a website",
            "html page", "python script", "javascript", "kotlin", "debug this", "fix this code",
            "program likho", "webpage", "web app", "react", "css", "sql query", "algorithm"
        )
        return keys.any { t.contains(it) }
    }

    fun isHostingRequest(prompt: String): Boolean {
        val t = prompt.lowercase()
        return t.contains("host it") || t.contains("deploy kar do") || t.contains("deploy it") ||
            t.contains("host on ") || t.contains("deploy to ") || t.contains("render") ||
            t.contains("railway") || t.contains("vercel") || t.contains("netlify")
    }

    fun backupsRoot(ctx: Context): File = File(ctx.filesDir, "code_backups").apply { mkdirs() }

    fun zipExportDir(ctx: Context): File {
        val pub = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val d = File(pub, "NOVA-Backups")
        if ((d.exists() || d.mkdirs()) && d.canWrite()) return d
        return File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "NOVA-Backups").apply { mkdirs() }
    }

    private fun toast(ctx: Context, msg: String) {
        main.post { try { Toast.makeText(ctx.applicationContext, msg, Toast.LENGTH_LONG).show() } catch (_: Exception) {} }
    }

    /**
     * Routes a coding request to the appropriate AI provider via NOVA Browser WebView.
     * Call on a background thread.
     */
    fun routeCodeTask(ctx: Context, prompt: String, onProgress: (String) -> Unit = {}): CodeResult {
        val app = ctx.applicationContext
        val provider = detectProvider(app, prompt)
        onProgress("Opening ${provider.name()} in NOVA Browser...")

        NovaBrowser.init(app)
        if (!NovaBrowser.ensureHost()) {
            NovaBrowser.openUi(app, provider.chatUrl())
            Thread.sleep(2000)
        }
        val tab = NovaBrowser.ui { NovaBrowser.newTab(provider.chatUrl(), true, false) }
            ?: return CodeResult(false, provider.name(), message = "Could not open browser tab for ${provider.name()}")
        NovaBrowser.openUi(app)
        NovaBrowser.waitLoaded(tab, 15_000L)
        Thread.sleep(1500)

        val loggedIn = provider.isLoggedIn(tab.web)
        if (!loggedIn) {
            val msg = "Please log in to ${provider.name()} in Nova's browser once"
            toast(app, msg)
            provider.openLogin(tab.web)
            NovaBrowser.openUi(app)
            return CodeResult(ok = false, provider = provider.name(), needsLogin = true, message = msg)
        }

        onProgress("Sending prompt to ${provider.name()}...")
        val fullPrompt = "Write complete, clean, ready-to-run code inside fenced markdown code blocks for:\n$prompt"
        provider.sendPrompt(tab.web, fullPrompt)

        onProgress("Waiting for ${provider.name()} response...")
        Thread.sleep(3500)
        var prev = ""
        var stable = 0
        val deadline = System.currentTimeMillis() + 55_000L
        while (System.currentTimeMillis() < deadline && stable < 3) {
            Thread.sleep(2000)
            val cur = WebEvalHelper.extractReplyText(tab.web)
            if (cur.isNotBlank() && cur == prev) stable++
            else {
                stable = 0
                prev = cur
            }
        }

        val reply = prev.trim()
        if (reply.isBlank()) {
            return CodeResult(false, provider.name(), message = "No code response captured from ${provider.name()}. You can copy directly from NOVA Browser.")
        }

        val parsed = CodeStudio.parsePublic(reply)
        val lang = parsed?.first ?: "txt"
        val code = parsed?.second ?: reply
        val saved = saveProjectBackup(app, provider.name(), prompt, lang, code, reply)
        WallBus.pushFeed("CODE", "${provider.name()} backup saved: ${File(saved.first).name}")
        return CodeResult(
            ok = true,
            provider = provider.name(),
            code = code,
            backupDir = saved.first,
            zipPath = saved.second,
            message = "Code generated via ${provider.name()} and backed up."
        )
    }

    fun saveProjectBackup(ctx: Context, providerName: String, prompt: String, lang: String, code: String, rawReply: String): Pair<String, String> {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val slug = prompt.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(24).ifBlank { "nova_code" }
        val folderName = "${stamp}_${slug}"
        val dir = File(backupsRoot(ctx), folderName).apply { mkdirs() }

        val ext = when (lang.lowercase()) {
            "html" -> "html"
            "python", "py" -> "py"
            "javascript", "js" -> "js"
            "kotlin", "kt" -> "kt"
            "css" -> "css"
            "bash", "sh" -> "sh"
            else -> "txt"
        }
        val mainName = if (ext == "html") "index.html" else "main.$ext"
        File(dir, mainName).writeText(code)
        File(dir, "prompt.txt").writeText("Provider: $providerName\nPrompt: $prompt\n")
        if (rawReply != code) File(dir, "response.md").writeText(rawReply)

        val zipFile = File(zipExportDir(ctx), "${slug}_${stamp}.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            dir.listFiles()?.forEach { f ->
                if (f.isFile) {
                    zos.putNextEntry(ZipEntry("$folderName/${f.name}"))
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
        try { MediaScannerConnection.scanFile(ctx, arrayOf(zipFile.absolutePath), null, null) } catch (_: Throwable) {}
        return dir.absolutePath to zipFile.absolutePath
    }

    /** Optional hosting web dashboard helper — only called when user explicitly requests hosting/deployment. */
    fun openHosting(ctx: Context, targetHint: String): String {
        val app = ctx.applicationContext
        val low = targetHint.lowercase()
        val (platform, url) = when {
            low.contains("railway") -> "Railway" to "https://railway.app/dashboard"
            low.contains("vercel") -> "Vercel" to "https://vercel.com/dashboard"
            low.contains("netlify") -> "Netlify" to "https://app.netlify.com/"
            else -> "Render" to "https://dashboard.render.com/"
        }
        NovaBrowser.init(app)
        NovaBrowser.openUi(app, url)
        val ck = try { CookieManager.getInstance().getCookie(url) ?: "" } catch (_: Throwable) { "" }
        return if (ck.isBlank()) {
            "Opened $platform dashboard in NOVA Browser. Please sign in once, then upload or connect your project zip from Download/NOVA-Backups/."
        } else {
            "Opened $platform dashboard in NOVA Browser. Your backup zip is ready in Download/NOVA-Backups/ to deploy."
        }
    }
}
