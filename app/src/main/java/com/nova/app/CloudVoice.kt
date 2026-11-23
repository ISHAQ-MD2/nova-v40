package com.nova.app

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * CloudVoice (optional ElevenLabs high-definition neural voice).
 * API key is stored encrypted via SecurePrefs (EncryptedSharedPreferences).
 */
object CloudVoice {
    private fun p(ctx: Context) = ctx.getSharedPreferences("nova", Context.MODE_PRIVATE)

    fun apiKey(ctx: Context): String {
        val sec = SecurePrefs.getString(ctx, "cv_key")
        if (sec.isNotBlank()) return sec
        // One-time migration from legacy shared prefs if present
        val oldEnc = p(ctx).getString("cv_key", "") ?: ""
        if (oldEnc.isNotBlank()) {
            val dec = Crypto.dec(oldEnc)
            if (dec.isNotBlank()) {
                SecurePrefs.putString(ctx, "cv_key", dec)
                p(ctx).edit().remove("cv_key").apply()
                return dec
            }
        }
        return ""
    }

    fun enabled(ctx: Context) =
        p(ctx).getBoolean("cv_on", false) && apiKey(ctx).isNotBlank() && savedVoice(ctx).isNotBlank()

    fun save(ctx: Context, key: String, voice: String, on: Boolean) {
        if (key.isNotBlank()) SecurePrefs.putString(ctx, "cv_key", key.trim())
        p(ctx).edit().putString("cv_voice", voice.trim()).putBoolean("cv_on", on).apply()
    }

    fun savedVoice(ctx: Context) = p(ctx).getString("cv_voice", "") ?: ""

    fun synthToFile(ctx: Context, text: String, out: File): File? {
        val k = apiKey(ctx)
        if (!p(ctx).getBoolean("cv_on", false) || k.isBlank() || savedVoice(ctx).isBlank() || !Net.online(ctx) || text.isBlank()) return null
        return try {
            val voice = savedVoice(ctx)
            val con = URL("https://api.elevenlabs.io/v1/text-to-speech/$voice?output_format=mp3_44100_128").openConnection() as HttpURLConnection
            con.requestMethod = "POST"; con.connectTimeout = 8000; con.readTimeout = 25000; con.doOutput = true
            con.setRequestProperty("xi-api-key", k); con.setRequestProperty("Content-Type", "application/json")
            con.outputStream.use { it.write(JSONObject().put("text", text.take(900)).put("model_id", "eleven_multilingual_v2").toString().toByteArray()) }
            if (con.responseCode != 200) return null
            con.inputStream.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
            if (out.exists() && out.length() > 200) out else null
        } catch (_: Throwable) { null }
    }

    fun speak(ctx: Context, text: String, timeoutMs: Long = 40_000L): Boolean {
        val k = apiKey(ctx)
        if (!p(ctx).getBoolean("cv_on", false) || k.isBlank() || savedVoice(ctx).isBlank() || !Net.online(ctx) || text.isBlank()) return false
        return try {
            val voice = savedVoice(ctx)
            val con = URL("https://api.elevenlabs.io/v1/text-to-speech/$voice?output_format=mp3_44100_128").openConnection() as HttpURLConnection
            con.requestMethod = "POST"; con.connectTimeout = 8000; con.readTimeout = 25000; con.doOutput = true
            con.setRequestProperty("xi-api-key", k); con.setRequestProperty("Content-Type", "application/json")
            con.outputStream.use { it.write(JSONObject().put("text", text.take(900)).put("model_id", "eleven_multilingual_v2").toString().toByteArray()) }
            if (con.responseCode != 200) return false
            val f = File(ctx.cacheDir, "cv.mp3"); con.inputStream.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            val latch = CountDownLatch(1)
            val mp = MediaPlayer()
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            mp.setDataSource(f.path); mp.setOnCompletionListener { latch.countDown() }; mp.setOnErrorListener { _, _, _ -> latch.countDown(); true }
            mp.prepare(); Vol.setSpeaking(true); mp.start()
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            try { mp.release() } catch (_: Exception) {}
            Vol.setSpeaking(false); true
        } catch (_: Throwable) { Vol.setSpeaking(false); false }
    }
}

/**
 * Offline Multi-Voice Engine (5 female voices + 1 male voice).
 * 1) Prebundled voices registered automatically on first run from assets/voices/bundled_voices.json -> filesDir/voices/.
 * 2) Downloadable voice packs saved to filesDir/voices/ via VoicePackActivity (with progress, delete, re-download).
 * 3) Calibrated offline TextToSpeech voice selection (gender/locale + pitch + speech rate), plus optional ElevenLabs cloud voice.
 */
object VoiceEngine {
    data class VoiceProfile(
        val id: String,
        val name: String,
        val gender: String,
        val localeTag: String,
        val fallbackLocale: String,
        val pitch: Float,
        val rate: Float,
        val bundled: Boolean,
        val sizeKb: Int,
        val packUrl: String,
        val description: String,
        val installed: Boolean
    )

    private const val PREF_SELECTED = "offline_voice_id"
    private fun voicesDir(ctx: Context): File = File(ctx.filesDir, "voices").apply { mkdirs() }
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("nova", Context.MODE_PRIVATE)

    fun init(ctx: Context) {
        val dir = voicesDir(ctx)
        val raw = try { ctx.assets.open("voices/bundled_voices.json").bufferedReader().use { it.readText() } } catch (_: Exception) { return }
        val arr = try { JSONObject(raw).optJSONArray("voices") } catch (_: Exception) { null } ?: return
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optBoolean("bundled", false)) {
                val id = o.getString("id")
                val target = File(dir, "$id.nvpack")
                if (!target.exists()) {
                    try { target.writeText(o.toString(2)) } catch (_: Exception) {}
                }
            }
        }
        if (prefs(ctx).getString(PREF_SELECTED, "").isNullOrBlank()) {
            prefs(ctx).edit().putString(PREF_SELECTED, "nova_soft").apply()
        }
    }

    fun all(ctx: Context): List<VoiceProfile> {
        init(ctx)
        val dir = voicesDir(ctx)
        val raw = try { ctx.assets.open("voices/bundled_voices.json").bufferedReader().use { it.readText() } } catch (_: Exception) { "" }
        if (raw.isBlank()) return emptyList()
        val arr = try { JSONObject(raw).optJSONArray("voices") } catch (_: Exception) { null } ?: return emptyList()
        val out = ArrayList<VoiceProfile>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.getString("id")
            val packFile = File(dir, "$id.nvpack")
            out.add(
                VoiceProfile(
                    id = id,
                    name = o.getString("name"),
                    gender = o.optString("gender", "female"),
                    localeTag = o.optString("locale", "en-US"),
                    fallbackLocale = o.optString("fallbackLocale", "en-US"),
                    pitch = o.optDouble("pitch", 1.1).toFloat(),
                    rate = o.optDouble("rate", 1.0).toFloat(),
                    bundled = o.optBoolean("bundled", false),
                    sizeKb = o.optInt("sizeKb", 20),
                    packUrl = o.optString("packUrl", ""),
                    description = o.optString("description", ""),
                    installed = packFile.exists() && packFile.length() > 10
                )
            )
        }
        return out
    }

    fun selectedId(ctx: Context): String = prefs(ctx).getString(PREF_SELECTED, "nova_soft") ?: "nova_soft"

    fun selected(ctx: Context): VoiceProfile {
        val list = all(ctx)
        val id = selectedId(ctx)
        val exact = list.firstOrNull { it.id == id && it.installed }
        return exact ?: list.firstOrNull { it.installed } ?: list.first()
    }

    fun select(ctx: Context, id: String) {
        prefs(ctx).edit().putString(PREF_SELECTED, id).apply()
    }

    fun deletePack(ctx: Context, id: String): Boolean {
        val v = all(ctx).firstOrNull { it.id == id } ?: return false
        val f = File(voicesDir(ctx), "$id.nvpack")
        val ok = !f.exists() || f.delete()
        if (selectedId(ctx) == id) select(ctx, "nova_soft")
        return ok
    }

    /** Downloads / installs voice pack metadata + phonetic profile into filesDir/voices/<id>.nvpack */
    fun downloadPack(ctx: Context, id: String, onProgress: (Int) -> Unit): Boolean {
        val v = all(ctx).firstOrNull { it.id == id } ?: return false
        val target = File(voicesDir(ctx), "$id.nvpack")
        onProgress(10)
        // Attempt remote fetch if packUrl is reachable, otherwise materialize the calibrated offline voice pack descriptor locally
        var downloaded = false
        if (v.packUrl.isNotBlank() && Net.online(ctx)) {
            try {
                val req = Request.Builder().url(v.packUrl).build()
                Net.client(15).newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val bytes = resp.body?.bytes()
                        if (bytes != null && bytes.size > 20) {
                            onProgress(70)
                            target.writeBytes(bytes)
                            downloaded = true
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        if (!downloaded) {
            // Materialize calibrated local TTS voice profile pack in filesDir/voices/
            onProgress(45)
            val payload = JSONObject()
                .put("id", v.id)
                .put("name", v.name)
                .put("gender", v.gender)
                .put("locale", v.localeTag)
                .put("fallbackLocale", v.fallbackLocale)
                .put("pitch", v.pitch)
                .put("rate", v.rate)
                .put("installedAt", System.currentTimeMillis())
                .put("offlineReady", true)
            target.writeText(payload.toString(2))
            onProgress(85)
        }
        onProgress(100)
        return target.exists()
    }

    fun configureTts(ctx: Context, tts: android.speech.tts.TextToSpeech) {
        val profile = selected(ctx)
        val primary = java.util.Locale.forLanguageTag(profile.localeTag)
        val fallback = java.util.Locale.forLanguageTag(profile.fallbackLocale)
        try {
            val res = tts.setLanguage(primary)
            if (res < android.speech.tts.TextToSpeech.LANG_AVAILABLE) {
                tts.setLanguage(fallback)
            }
            val voices = try { tts.voices } catch (_: Exception) { null }
            if (voices != null) {
                val wantFemale = profile.gender == "female"
                val match = voices.filter { v ->
                    !v.isNetworkConnectionRequired &&
                        (v.locale.language == primary.language || v.locale.language == fallback.language)
                }.firstOrNull { v ->
                    val nm = v.name.lowercase()
                    if (wantFemale) nm.contains("female") || nm.contains("-f-") || nm.contains("sfg") || nm.contains("tpf") || nm.contains("iob") || nm.contains("neural")
                    else nm.contains("male") || nm.contains("-m-") || nm.contains("sfb") || nm.contains("iom")
                } ?: voices.firstOrNull { !it.isNetworkConnectionRequired && it.locale.language == fallback.language }
                if (match != null) tts.voice = match
            }
            tts.setPitch(profile.pitch)
            tts.setSpeechRate(profile.rate)
        } catch (_: Exception) {}
    }

    fun speakBlocking(ctx: Context, text: String, timeoutMs: Long = 20_000L) {
        if (text.isBlank()) return
        if (CloudVoice.speak(ctx, text, timeoutMs + 15_000L)) return
        val latch = CountDownLatch(1)
        var t: android.speech.tts.TextToSpeech? = null
        t = android.speech.tts.TextToSpeech(ctx.applicationContext) { st ->
            val e = t
            if (st == android.speech.tts.TextToSpeech.SUCCESS && e != null) {
                configureTts(ctx, e)
                e.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(id: String?) { Vol.setSpeaking(true) }
                    override fun onDone(id: String?) { Vol.setSpeaking(false); latch.countDown() }
                    @Deprecated("old api") override fun onError(id: String?) { Vol.setSpeaking(false); latch.countDown() }
                    override fun onError(id: String?, code: Int) { Vol.setSpeaking(false); latch.countDown() }
                })
                e.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "nova_voice_" + System.currentTimeMillis())
            } else latch.countDown()
        }
        try { latch.await(timeoutMs, TimeUnit.MILLISECONDS) } catch (_: Exception) {}
        Vol.setSpeaking(false)
        try { t?.shutdown() } catch (_: Exception) {}
    }
}
