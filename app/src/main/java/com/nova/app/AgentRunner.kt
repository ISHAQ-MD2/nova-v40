package com.nova.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bina awaaz ke (scheduled kaam ke liye): Gemini REST + wahi phone-control tools, loop mein.
 * v17: kaam tab hi "ho gaya" jab model 'TASK_DONE' kahe; adhoora chhode to dobara dhakela jata hai;
 * ek hi app 3 dafa se zyada kholne par ya 4 minute baad ruk jata hai (FAIL) aur Retry ka button milta hai.
 */
object AgentRunner {
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile var cancelled = false
    fun cancel() { cancelled = true }

    /** In tools ke kamyab hote hi kaam KHATAM (model ko dobara na bulao). */
    private val FINAL_ACTIONS = setOf("whatsapp_send", "whatsapp_voice", "whatsapp_call", "send_media", "whatsapp_status", "call", "notify_user", "email_compose", "copy_link_send", "youtube_play")
    private val FAIL_WORDS = listOf("error", "fail", "warning", "nahi", "not found", "missing", "khul gayi", "can't", "cannot")

    private const val PLAYBOOK = " PLAY STORE INSTALL PLAYBOOK: call open_app for the Play Store ONCE (if the Play Store is already on screen do NOT open it again). Search for the requested kind of app (type in the search box and submit). " +
        "Open a matching result, read the screen text (read_screen_text) to find its size such as '120 MB'. If the size is outside the requested range press back and open the next result (try up to 6 results). " +
        "When the size fits, tap Install, then poll read_screen_text until you see 'Open' or 'Uninstall' (download finished). Only then finish."

    private const val YT_BOOK = " YOUTUBE PLAYBOOK: call youtube_play(query) ONCE - it opens YouTube, searches, skips ads and plays the best matching result automatically (never ask which result). If the link must then be sent to someone call copy_link_send(who)."

    private const val BROWSER_BOOK = " BROWSER PLAYBOOK (v36): for anything on the web use NOVA's OWN browser tools (browser_open, browser_read, browser_click, browser_type, browser_download, browser_upload) - not Chrome. A file link (MediaFire, Drive, .zip/.apk) -> browser_download(url). A song/video -> youtube_play. On a website like GitHub: browser_open -> browser_read -> browser_click/browser_type using the [numbers] -> browser_read again to verify before TASK_DONE."

    /** Result: "OK: ..." ya "FAIL: wajah" */
    fun run(ctx: Context, instruction: String): String {
        if (!running.compareAndSet(false, true)) return "FAIL: pehla kaam abhi chal raha hai"
        try { return runGuarded(ctx, instruction) } finally { running.set(false) }
    }

    private fun runGuarded(ctx: Context, instruction: String): String {
        cancelled = false
        val acc = NovaAccessibilityService.instance
        try { acc?.home(); Thread.sleep(700) } catch (_: Exception) {}
        val r = try { runInner(ctx, instruction) } catch (e: Exception) { "FAIL: error ${e.message}" }
        try { Thread.sleep(500); acc?.home() } catch (_: Exception) {}
        return r
    }

    private fun runInner(ctx: Context, instruction: String): String {
        Memory.init(ctx)
        val p = ctx.getSharedPreferences("nova", Context.MODE_PRIVATE)
        val key = Crypto.dec(p.getString("k", "") ?: "")
        if (key.isEmpty()) return "FAIL: API key nahi mili"
        val user = (p.getString("u", "") ?: "").ifEmpty { "Boss" }
        val sys = Brain.agent(ctx, user)
        val webTask = Regex("https?:|www\\.|\\.com|mediafire|drive|github|browser|link", RegexOption.IGNORE_CASE).containsMatchIn(instruction)
        val extra = (if (Regex("play ?store|install|download", RegexOption.IGNORE_CASE).containsMatchIn(instruction) && !webTask) PLAYBOOK else "") +
            (if (instruction.contains("youtube", true)) YT_BOOK else "") + (if (webTask || instruction.contains("youtube", true)) BROWSER_BOOK else "")
        val contents = JSONArray().put(JSONObject().put("role", "user").put("parts",
            JSONArray().put(JSONObject().put("text", "SCHEDULED TASK (the screen is on the home screen and unlocked; do it now, on screen, exactly like a live voice command - open the app and finish the WHOLE job using tools). " +
                "Never stop after just opening an app or searching - continue until the final result. When completely finished reply exactly 'TASK_DONE: <short summary>'; if it is impossible reply 'TASK_FAILED: <reason>'.$extra  TASK: $instruction"))))
        val tools = JSONArray().put(JSONObject().put("functionDeclarations", Tools.declarations(false)))
        val t0 = System.currentTimeMillis()
        val opens = HashMap<String, Int>()
        var nudges = 0
        var step = 0
        while (step < 26) {
            if (cancelled) return "FAIL: aapne rok diya"
            if (System.currentTimeMillis() - t0 > 240_000L) return "FAIL: 4 minute mein bhi kaam nahi hua"
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sys))))
                .put("contents", contents).put("tools", tools)
            val res = GeminiRest.post(ctx, body)
            if (!res.ok) return "FAIL: " + res.text
            val content = JSONObject(res.text).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
                ?: return "FAIL: model ne jawab nahi diya"
            contents.put(content)
            val parts = content.optJSONArray("parts") ?: JSONArray()
            val replies = JSONArray()
            val said = StringBuilder()
            var finished: String? = null
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                val fc = part.optJSONObject("functionCall")
                if (fc != null) {
                    val name = fc.optString("name"); val args = fc.optJSONObject("args") ?: JSONObject()
                    if (name == "open_app") {
                        val k = args.optString("name").lowercase()
                        val n = (opens[k] ?: 0) + 1; opens[k] = n
                        if (n > 3) return "FAIL: '$k' bar bar khul raha tha (3 dafa se zyada) - ruk gayi"
                    }
                    val out = Tools.execute(ctx, name, args)
                    Memory.log("agent-tool", "$name ${args.toString().take(150)} -> ${out.take(150)}")
                    if (name in FINAL_ACTIONS && FAIL_WORDS.none { out.contains(it, true) }) finished = out
                    replies.put(JSONObject().put("functionResponse", JSONObject().put("name", name).put("response", JSONObject().put("result", out))))
                } else if (part.has("text")) said.append(part.optString("text"))
            }
            if (finished != null) return "OK: $finished"
            val txt = said.toString().trim()
            if (replies.length() == 0) {
                if (txt.contains("TASK_FAILED", true)) return "FAIL: " + txt.substringAfter(":", txt).trim().take(200)
                if (txt.contains("TASK_DONE", true)) return "OK: " + txt.substringAfter(":", txt).trim().take(200)
                if (nudges < 3) {
                    nudges++
                    contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text",
                        "You have NOT finished. Look at the screen again (read_screen_text) and continue with tools until the WHOLE task is completed. Reply 'TASK_DONE: ...' only when it is truly finished, or 'TASK_FAILED: reason'."))))
                    step++; continue
                }
                return "FAIL: model ne kaam adhoora chhor diya" + if (txt.isNotEmpty()) " (" + txt.take(80) + ")" else ""
            }
            contents.put(JSONObject().put("role", "user").put("parts", replies))
            step++
        }
        return "FAIL: kaam adhoora reh gaya (bahut zyada steps)"
    }
}
