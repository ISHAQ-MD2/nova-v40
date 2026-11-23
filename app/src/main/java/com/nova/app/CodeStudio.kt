package com.nova.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

/** Chhota web server (phone par): HTML project ka link banata hai - isi Wi-Fi ke dusre phone/PC par bhi khulta hai. */
object MiniServer {
    @Volatile private var ss: ServerSocket? = null
    @Volatile var root: File? = null
    const val PORT = 8088

    fun start(dir: File): Boolean {
        root = dir
        if (ss != null && !ss!!.isClosed) return true
        return try {
            val s = ServerSocket(PORT); ss = s
            Thread {
                while (!s.isClosed) { try { val c = s.accept(); Thread { serve(c) }.start() } catch (_: Exception) { break } }
            }.start(); true
        } catch (_: Exception) { false }
    }

    private fun serve(c: Socket) {
        try {
            val inp = c.getInputStream().bufferedReader(); val line = inp.readLine() ?: return
            var path = line.split(" ").getOrNull(1) ?: "/"; path = path.substringBefore("?")
            if (path == "/" || path.isEmpty()) path = "/index.html"
            val f = File(root, path.replace("..", ""))
            val out = c.getOutputStream()
            if (!f.isFile) { out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 9\r\n\r\nNot found".toByteArray()); out.flush(); c.close(); return }
            val mime = when (f.extension.lowercase()) { "html", "htm" -> "text/html; charset=utf-8"; "js" -> "application/javascript"; "css" -> "text/css"; "json" -> "application/json"; "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "svg" -> "image/svg+xml"; else -> "text/plain; charset=utf-8" }
            val data = f.readBytes()
            out.write("HTTP/1.1 200 OK\r\nContent-Type: $mime\r\nContent-Length: ${data.size}\r\nAccess-Control-Allow-Origin: *\r\n\r\n".toByteArray()); out.write(data); out.flush(); c.close()
        } catch (_: Exception) { try { c.close() } catch (_: Exception) {} }
    }

    fun lanIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }.firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(":") == false }?.hostAddress
    } catch (_: Exception) { null }
}

/**
 * Gemini se code likhwao -> chalao -> error aaye to khud fix karwao (4 koshish tak) -> output / link.
 * HTML: phone par server + browser mein khulta hai (LAN link). Python/JS/bash: Termux mein chalta hai.
 */
object CodeStudio {
    class Out(val ok: Boolean, val lang: String, val code: String, val log: String, val link: String)

    private fun ask(ctx: Context, system: String, user: String): String? {
        val body = JSONObject().put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", user)))))
        val r = GeminiRest.post(ctx, body)
        if (!r.ok) return null
        return try { JSONObject(r.text).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text") } catch (_: Exception) { null }
    }

    private const val SYS = "You are a coding agent. Reply with ONLY one fenced code block containing the COMPLETE working program, tagged html, python, javascript or bash. " +
        "For web pages use ONE self-contained html file (inline css/js). Python must print its result. No explanations outside the block."

    fun parsePublic(t: String): Pair<String, String>? = parse(t)
    private fun parse(t: String): Pair<String, String>? {
        val m = Regex("```([a-zA-Z]*)\\s*\\n(.*?)```", RegexOption.DOT_MATCHES_ALL).find(t) ?: return null
        var lang = m.groupValues[1].lowercase()
        if (lang == "js" || lang == "node") lang = "javascript"; if (lang == "sh" || lang == "shell") lang = "bash"; if (lang == "py") lang = "python"; if (lang.isEmpty()) lang = "python"
        return lang to m.groupValues[2].trimEnd()
    }

    private fun dir(ctx: Context): File = (Backup.sub("code") ?: File(ctx.filesDir, "code").apply { mkdirs() })

    fun saveDir(ctx: Context): File = dir(ctx)
    /** Ek baar chalao. HTML: ok + link. Baaki: Termux se (exit code 0 = ok). */
    fun run(ctx: Context, lang: String, code: String, name: String): Out {
        val d = dir(ctx)
        if (lang == "html") {
            val f = File(d, "$name.html"); f.writeText(code); File(d, "index.html").writeText(code)
            MiniServer.start(d)
            val ip = MiniServer.lanIp()
            return Out(true, lang, code, "HTML tayyar (${f.path})", "http://" + (ip ?: "127.0.0.1") + ":" + MiniServer.PORT + "/" + f.name)
        }
        val ext = when (lang) { "python" -> "py"; "javascript" -> "js"; else -> "sh" }
        val runner = when (lang) { "python" -> "python3"; "javascript" -> "node"; else -> "bash" }
        File(d, "$name.$ext").writeText(code)
        val cmd = "mkdir -p ~/nova_code && cat > ~/nova_code/$name.$ext <<'NOVA_EOF'\n$code\nNOVA_EOF\n$runner ~/nova_code/$name.$ext 2>&1"
        val res = TermuxBridge.run(ctx, cmd, false)
        val ok = res.startsWith("exit=0")
        return Out(ok, lang, code, res, "")
    }

    /** Poora loop. progress() UI ko batata hai. Background thread se bulao. */
    fun auto(ctx: Context, prompt: String, progress: (String) -> Unit): Out {
        val name = "nova_" + (System.currentTimeMillis() % 100000)
        progress("Gemini code likh raha hai...")
        var txt = ask(ctx, SYS, prompt) ?: return Out(false, "", "", "Gemini se code nahi mila (net / API key?)", "")
        var last: Out? = null
        for (attempt in 1..4) {
            val (lang, code) = parse(txt) ?: return Out(false, "", txt, "Code block nahi mila", "")
            progress("Chala rahi hoon (koshish $attempt)...")
            val o = run(ctx, lang, code, name); last = o
            if (o.ok) return o
            progress("Error aaya, fix karwa rahi hoon...")
            txt = ask(ctx, SYS, "Original task: $prompt\n\nThis $lang code failed:\n```$lang\n$code\n```\nError/output:\n${o.log.take(2500)}\n\nFix it and return the complete corrected program.") ?: return o
        }
        return last ?: Out(false, "", "", "fail", "")
    }
}
