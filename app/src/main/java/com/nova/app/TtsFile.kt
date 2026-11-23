package com.nova.app

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Generates audio files for WhatsApp / offline voice playback using ElevenLabs (when enabled) or VoiceEngine offline voices.
 */
object TtsFile {
    private fun dir(ctx: Context): File = File(ctx.filesDir, "nova_voice").apply { mkdirs() }

    fun synth(ctx: Context, text: String, timeoutMs: Long = 25000L): File? {
        if (text.isBlank()) return null
        val out = File(dir(ctx), "voice_" + (System.currentTimeMillis() % 1000000) + ".mp3")
        if (CloudVoice.enabled(ctx) && Net.online(ctx)) {
            val f = CloudVoice.synthToFile(ctx, text, out)
            if (f != null) {
                try { Backup.sub("voices")?.let { f.copyTo(File(it, f.name), true) } } catch (_: Exception) {}
                return f
            }
        }
        return synthLocal(ctx, text, out, timeoutMs)
    }

    private fun synthLocal(ctx: Context, text: String, out: File, timeoutMs: Long): File? {
        val latch = CountDownLatch(1)
        var ok = false
        var tts: TextToSpeech? = null
        tts = TextToSpeech(ctx.applicationContext) { st ->
            val e = tts
            if (st == TextToSpeech.SUCCESS && e != null) {
                VoiceEngine.configureTts(ctx, e)
                e.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { ok = true; latch.countDown() }
                    @Deprecated("old api") override fun onError(id: String?) { latch.countDown() }
                    override fun onError(id: String?, code: Int) { latch.countDown() }
                })
                val params = android.os.Bundle()
                e.synthesizeToFile(text, params, out, "nova_voice")
            } else latch.countDown()
        }
        try { latch.await(timeoutMs, TimeUnit.MILLISECONDS) } catch (_: Exception) {}
        try { tts?.shutdown() } catch (_: Exception) {}
        return if (ok && out.exists() && out.length() > 200) out else null
    }
}
