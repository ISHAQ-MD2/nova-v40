package com.nova.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File

/**
 * v30: Code sirf DeepSeek ya Claude se likhwana (koi aur AI nahi). App mein jo naam bola jaye wahi app khulti hai;
 * naam na bolo to default DeepSeek khulta hai. Jo code mile wo NOVA-FILES/code mein bhi copy ho jata hai (backup).
 * GitHub/Vercel deploy ke liye Termux ke commands use karta hai (user ka apna login wahan pehle se hona chahiye).
 */
object AiCode {
    enum class Provider(val label: String, val pkg: String, val url: String) {
        DEEPSEEK("DeepSeek", "com.deepseek.chat", "https://chat.deepseek.com/"),
        CLAUDE("Claude", "com.anthropic.claude", "https://claude.ai/new")
    }

    /** Message mein naam ho to wahi, warna default DeepSeek. */
    fun detect(ctx: Context, text: String): Provider {
        val t = text.lowercase()
        if (t.contains("claude") || t.contains("clode") || t.contains("klod")) return Provider.CLAUDE
        if (t.contains("deepseek") || t.contains("deap seek") || t.contains("deak sea") || t.contains("deap sea")) return Provider.DEEPSEEK
        val def = ctx.getSharedPreferences("nova", Context.MODE_PRIVATE).getString("ai_default", "deepseek")
        return if (def == "claude") Provider.CLAUDE else Provider.DEEPSEEK
    }

    private fun openProvider(ctx: Context, p: Provider): Boolean {
        val pm = ctx.packageManager
        val li = pm.getLaunchIntentForPackage(p.pkg)
        val i = if (li != null) li else Intent(Intent.ACTION_VIEW, Uri.parse(p.url)).setPackage("com.android.chrome")
        return try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val c: Context = NovaAccessibilityService.instance ?: ctx
            c.startActivity(i); true
        } catch (_: Exception) {
            try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (_: Exception) { false }
        }
    }

    /** DeepSeek/Claude khol kar prompt bhejo aur jawab ka text wapas lao (code block ke saath). Background thread se bulao. */
    fun ask(ctx: Context, provider: Provider, prompt: String, progress: (String) -> Unit): String {
        val acc = NovaAccessibilityService.instance ?: return "FAIL: Accessibility OFF hai - Setup mein ON karo taake $provider.label mein type/click ho sake"
        progress("${provider.label} khol rahi hoon...")
        if (!openProvider(ctx, provider)) return "FAIL: ${provider.label} nahi khul saki"
        Thread.sleep(3200)
        var typed = false
        for (k in 0 until 10) { if (acc.typeText(prompt)) { typed = true; break }; Thread.sleep(700) }
        if (!typed) return "FAIL: ${provider.label} ka text box nahi mila - look_screen se check karo"
        Thread.sleep(500)
        progress("Bhej rahi hoon...")
        if (!pollSend(acc)) {
            // kuch AI apps mein Enter key se chalta hai - ek baar text field par Enter try karo
            acc.typeText(prompt)
        }
        progress("${provider.label} jawab likh rahi hai...")
        Thread.sleep(2500)
        var prev = ""; var stable = 0
        val end = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < end && stable < 3) {
            Thread.sleep(2200)
            val cur = acc.dumpText(8000)
            if (cur == prev) stable++ else { stable = 0; prev = cur }
        }
        return prev.ifBlank { "FAIL: ${provider.label} se jawab nahi mila (net / login check karo)" }
    }

    private fun pollSend(acc: NovaAccessibilityService): Boolean {
        val labels = listOf("Send", "Send message", "Submit", "Send prompt", "send")
        for (i in 0 until 8) { if (acc.clickExact(labels)) return true; Thread.sleep(400) }
        return false
    }

    /** Poora kaam: AI se code mangwao, bachao, chalao (HTML/Python/JS/Bash), NOVA-FILES mein backup. */
    fun codeTask(ctx: Context, task: String, progress: (String) -> Unit): CodeStudio.Out {
        val provider = detect(ctx, task)
        val sys = "Write ONLY one fenced code block with the complete working program (tagged html, python, javascript or bash). For a web page use one self-contained html file. No explanation outside the block.\n\nTask: $task"
        // v30: DeepSeek ki asli API key lagi ho to seedha HTTP se (app khol'ne/type karne ki zaroorat nahi) - warna wahi purana UI tareeqa
        val reply = if (provider == Provider.DEEPSEEK && DeepSeekRest.hasKey(ctx)) {
            progress("DeepSeek API se code mangwa rahi hoon...")
            DeepSeekRest.ask(ctx, task, sys) ?: ask(ctx, provider, sys, progress)
        } else ask(ctx, provider, sys, progress)
        if (reply.startsWith("FAIL:")) return CodeStudio.Out(false, "", "", reply, "")
        val parsed = CodeStudio.parsePublic(reply) ?: return CodeStudio.Out(false, "", reply, "Code block nahi mila ${provider.label} ke jawab mein", "")
        val (lang, code) = parsed
        progress("Chala rahi hoon...")
        val name = "nova_" + (System.currentTimeMillis() % 100000)
        val out = CodeStudio.run(ctx, lang, code, name)
        try {
            val f = File(CodeStudio.saveDir(ctx), "$name.${if (lang == "html") "html" else if (lang == "python") "py" else if (lang == "javascript") "js" else "sh"}")
            f.writeText(code)
            WallBus.pushFeed("CODE", "${provider.label}: $name (${code.length} chars)")
        } catch (_: Exception) {}
        return out
    }
}

/** GitHub + Vercel/Netlify deploy, Termux ke zariye (user ka login Termux mein pehle se hona chahiye - gh auth login / vercel login). */
object DeployHub {
    fun githubPush(ctx: Context, repoName: String, localDir: String = ""): String {
        val dir = localDir.ifBlank { CodeStudio.saveDir(ctx).path }
        val cmd = """
            cd '$dir' || exit 9
            git init -q 2>/dev/null
            git add -A
            git -c user.email=nova@device -c user.name=NOVA commit -q -m "NOVA update" 2>/dev/null || true
            if ! gh repo view "$repoName" >/dev/null 2>&1; then
                gh repo create "$repoName" --public --source=. --remote=origin --push 2>&1
            else
                git remote add origin "https://github.com/$(gh api user -q .login)/$repoName.git" 2>/dev/null
                git branch -M main 2>/dev/null
                git push -u origin main 2>&1
            fi
            gh repo view "$repoName" --json url -q .url 2>/dev/null
        """.trimIndent()
        val out = TermuxBridge.run(ctx, cmd, false)
        val url = Regex("https://github\\.com/\\S+").find(out)?.value
        WallBus.pushFeed("GITHUB", url ?: "push attempt: $repoName")
        return if (url != null) "GitHub par ho gaya: $url" else "GitHub ka output:\n" + out.take(1500)
    }

    fun vercelDeploy(ctx: Context, localDir: String = ""): String {
        val dir = localDir.ifBlank { CodeStudio.saveDir(ctx).path }
        val cmd = "cd '$dir' || exit 9\nnpx --yes vercel --prod --yes 2>&1 | tail -30"
        val out = TermuxBridge.run(ctx, cmd, false)
        val url = Regex("https://\\S+\\.vercel\\.app\\S*").find(out)?.value
        WallBus.pushFeed("VERCEL", url ?: "deploy attempt")
        return if (url != null) "Live ho gaya: $url" else "Vercel ka output:\n" + out.take(1500)
    }

    fun netlifyDeploy(ctx: Context, localDir: String = ""): String {
        val dir = localDir.ifBlank { CodeStudio.saveDir(ctx).path }
        val cmd = "cd '$dir' || exit 9\nnpx --yes netlify-cli deploy --prod --dir=. 2>&1 | tail -30"
        val out = TermuxBridge.run(ctx, cmd, false)
        val url = Regex("https://\\S+\\.netlify\\.app\\S*").find(out)?.value
        WallBus.pushFeed("NETLIFY", url ?: "deploy attempt")
        return if (url != null) "Live ho gaya: $url" else "Netlify ka output:\n" + out.take(1500)
    }
}
