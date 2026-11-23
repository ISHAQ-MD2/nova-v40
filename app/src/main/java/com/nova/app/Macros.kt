package com.nova.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Custom commands (macros) - APK ke andar hi naye features banao, bina dobara build kiye.
 * Har macro = naam + bolne ke alfaaz (triggers) + steps (har line ek kaam).
 * Steps ka syntax (har line):
 *   open_app WhatsApp          | call Ali                    | whatsapp_send Ali | kahan ho
 *   youtube_search cat videos  | music_play palpal           | speak Salam      | notify Yaad rakho
 *   wait 2                     | torch on / torch off        | url https://...  | volume up / volume down
 *   macro <dusra macro>        | tool <tool_name> {"key":"value"}   (koi bhi NOVA tool)
 */
object Macros {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("nova", Context.MODE_PRIVATE)

    class M(val name: String, val triggers: List<String>, val steps: List<String>)

    fun all(ctx: Context): List<M> = try {
        val a = JSONArray(prefs(ctx).getString("macros", "[]"))
        (0 until a.length()).map { val o = a.getJSONObject(it)
            M(o.getString("name"), o.optString("triggers").split(",").map { s -> s.trim() }.filter { s -> s.isNotEmpty() }, o.optString("steps").lines().map { s -> s.trim() }.filter { s -> s.isNotEmpty() }) }
    } catch (_: Exception) { emptyList() }

    fun save(ctx: Context, oldName: String?, name: String, triggers: String, steps: String) {
        val list = all(ctx).filter { it.name != oldName && it.name != name }
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("name", it.name).put("triggers", it.triggers.joinToString(", ")).put("steps", it.steps.joinToString("\n"))) }
        a.put(JSONObject().put("name", name.trim()).put("triggers", triggers).put("steps", steps))
        prefs(ctx).edit().putString("macros", a.toString()).apply()
    }

    fun delete(ctx: Context, name: String) {
        val a = JSONArray()
        all(ctx).filter { it.name != name }.forEach { a.put(JSONObject().put("name", it.name).put("triggers", it.triggers.joinToString(", ")).put("steps", it.steps.joinToString("\n"))) }
        prefs(ctx).edit().putString("macros", a.toString()).apply()
    }

    fun names(ctx: Context) = all(ctx).map { it.name }

    /** Bole hue (Roman) text mein koi trigger ho to us macro ka naam. */
    fun match(ctx: Context, said: String): String? {
        val t = said.lowercase(Locale.ROOT)
        for (m in all(ctx)) for (tr in m.triggers) { val x = Script.normalize(tr).lowercase(Locale.ROOT); if (x.length >= 3 && t.contains(x)) return m.name }
        return null
    }

    fun run(ctx: Context, name: String, depth: Int = 0): String {
        val m = all(ctx).firstOrNull { it.name.equals(name.trim(), true) } ?: return "Macro '$name' nahi mila"
        if (depth > 3) return "Macro bahut gehra (loop?)"
        var done = 0; val errs = ArrayList<String>()
        for (line in m.steps) {
            try { val r = step(ctx, line, depth); done++; if (r.startsWith("ERR:")) errs.add(r.removePrefix("ERR:")) } catch (e: Exception) { errs.add(line + " -> " + e.message) }
            if (AgentRunner.cancelled) break
        }
        return "Macro '${m.name}': $done step chale" + if (errs.isEmpty()) "" else " (masle: ${errs.joinToString("; ").take(120)})"
    }

    private fun step(ctx: Context, line: String, depth: Int): String {
        val cmd = line.substringBefore(" ").lowercase(Locale.ROOT)
        val rest = line.substringAfter(" ", "").trim()
        fun tool(n: String, vararg kv: Pair<String, Any>): String { val o = JSONObject(); kv.forEach { o.put(it.first, it.second) }; return Tools.execute(ctx, n, o) }
        return when (cmd) {
            "open_app" -> tool("open_app", "name" to rest)
            "call" -> tool("call", "who" to rest)
            "whatsapp_send" -> tool("whatsapp_send", "who" to rest.substringBefore("|").trim(), "message" to rest.substringAfter("|", "").trim())
            "whatsapp_voice" -> tool("whatsapp_voice", "who" to rest, "seconds" to 10)
            "youtube_search" -> tool("youtube_search", "query" to rest)
            "music_play" -> tool("music_play", "query" to rest.substringBefore("|").trim(), "mode" to (rest.substringAfter("|", "name").trim().ifEmpty { "name" }))
            "speak" -> { Notifier.speakBlocking(ctx, rest, 20000); "ok" }
            "notify" -> { Notifier.show(ctx, "NOVA", rest); "ok" }
            "wait" -> { Thread.sleep(((rest.toDoubleOrNull() ?: 1.0) * 1000).toLong().coerceIn(100L, 60_000L)); "ok" }
            "url" -> { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(rest)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); "ok" }
            "volume" -> tool("volume", "direction" to (if (rest.startsWith("d", true)) "down" else "up"))
            "torch" -> torch(ctx, !rest.startsWith("off", true))
            "macro" -> run(ctx, rest, depth + 1)
            "tool" -> { val n = rest.substringBefore(" ").trim(); val js = rest.substringAfter(" ", "{}").trim(); Tools.execute(ctx, n, try { JSONObject(js) } catch (_: Exception) { JSONObject() }) }
            else -> "ERR:'$cmd' samajh nahi aaya"
        }
    }

    private fun torch(ctx: Context, on: Boolean): String = try {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        val id = cm.cameraIdList.first { cm.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        cm.setTorchMode(id, on); "ok"
    } catch (e: Exception) { "ERR:torch nahi chali" }

    /** Help: step syntax + sab tools ke naam */
    fun help(ctx: Context): String {
        val sb = StringBuilder("STEPS (har line ek kaam):\n")
        sb.append("open_app WhatsApp\ncall Ali\nwhatsapp_send Ali | kahan ho\nyoutube_search cat videos\nmusic_play palpal\nspeak Salam boss\nnotify Yaad rakho\nwait 2\ntorch on\nvolume up\nurl https://example.com\nmacro dusra_macro\ntool weather {}\n\nNOVA ke saare tools (tool <naam> {json}):\n")
        try { val a = Tools.declarations(false); for (i in 0 until a.length()) sb.append("• ").append(a.getJSONObject(i).optString("name")).append("\n") } catch (_: Exception) {}
        return sb.toString()
    }
}
