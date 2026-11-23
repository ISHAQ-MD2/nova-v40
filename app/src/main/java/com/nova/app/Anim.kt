package com.nova.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Live animation ke layers - aap khud banao aur preset ki tarah save karo. */
class Layer(val kind: Int, val count: Int, val speed: Int, val size: Int, val alpha: Int, val color: Int)   // color 0 = theme ka rang

object Anim {
    val KINDS = listOf("🌸 Petals", "🌿 Leaves", "✨ Sparks", "🔵 Bokeh", "❄ Snow", "🌧 Rain", "⭐ Stars", "🔥 Embers", "🫧 Bubbles", "💾 Matrix")
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("nova", Context.MODE_PRIVATE)

    fun toJson(l: List<Layer>): String { val a = JSONArray(); l.forEach { a.put(JSONObject().put("k", it.kind).put("n", it.count).put("s", it.speed).put("z", it.size).put("a", it.alpha).put("c", it.color)) }; return a.toString() }
    fun fromJson(s: String?): List<Layer>? = try {
        if (s.isNullOrBlank()) null else { val a = JSONArray(s); (0 until a.length()).map { val o = a.getJSONObject(it); Layer(o.getInt("k"), o.getInt("n"), o.optInt("s", 100), o.optInt("z", 100), o.optInt("a", 100), o.optInt("c", 0)) } }
    } catch (_: Exception) { null }

    /** null = theme ka apna default */
    fun current(ctx: Context): List<Layer>? = fromJson(prefs(ctx).getString("anim_cur", null))
    fun setCurrent(ctx: Context, l: List<Layer>?) { prefs(ctx).edit().apply { if (l == null) remove("anim_cur") else putString("anim_cur", toJson(l)) }.apply() }

    val BUILTIN: Map<String, List<Layer>> = linkedMapOf(
        "Petal Rain" to listOf(Layer(0, 34, 110, 110, 90, 0), Layer(3, 8, 80, 100, 60, 0)),
        "Snow Night" to listOf(Layer(4, 55, 100, 100, 90, 0), Layer(6, 25, 100, 100, 80, 0)),
        "Fire Embers" to listOf(Layer(7, 40, 110, 100, 95, 0), Layer(2, 10, 100, 100, 80, 0)),
        "Matrix" to listOf(Layer(9, 26, 110, 100, 90, 0), Layer(2, 8, 100, 100, 70, 0)),
        "Stars & Bubbles" to listOf(Layer(6, 45, 100, 100, 90, 0), Layer(8, 16, 90, 100, 80, 0)),
        "Rain" to listOf(Layer(5, 70, 100, 100, 80, 0), Layer(3, 6, 70, 100, 50, 0)),
        "Leaves Wind" to listOf(Layer(1, 28, 120, 110, 95, 0))
    )

    fun userPresets(ctx: Context): Map<String, List<Layer>> = try {
        val a = JSONArray(prefs(ctx).getString("anim_presets", "[]")); val m = LinkedHashMap<String, List<Layer>>()
        for (i in 0 until a.length()) { val o = a.getJSONObject(i); fromJson(o.getString("layers"))?.let { m[o.getString("name")] = it } }
        m
    } catch (_: Exception) { LinkedHashMap() }

    fun savePreset(ctx: Context, name: String, l: List<Layer>) {
        val m = LinkedHashMap(userPresets(ctx)); m[name] = l
        val a = JSONArray(); m.forEach { (k, v) -> a.put(JSONObject().put("name", k).put("layers", toJson(v))) }
        prefs(ctx).edit().putString("anim_presets", a.toString()).apply()
    }
    fun deletePreset(ctx: Context, name: String) {
        val m = LinkedHashMap(userPresets(ctx)); m.remove(name)
        val a = JSONArray(); m.forEach { (k, v) -> a.put(JSONObject().put("name", k).put("layers", toJson(v))) }
        prefs(ctx).edit().putString("anim_presets", a.toString()).apply()
    }
}
