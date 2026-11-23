package com.nova.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * "NOVA" bolne ka intezaar: phone ka apna speech recognizer (offline pack ho to bilkul data nahi).
 * Jab tak NOVA na bola jaye Gemini ko kuch nahi jata aur NOVA koi reaction nahi deti.
 */
class WakeWord(private val ctx: Context, private val main: Handler, private val onWake: (String) -> Unit) {
    companion object {
        fun available(ctx: Context) = try { SpeechRecognizer.isRecognitionAvailable(ctx) } catch (_: Exception) { false }
        private val RX = Regex("(?i)(\\bnova+h?\\b|\\bnoba\\b|\\bnowa\\b|नोवा|नॉवा|नोवह|नोबा)")
    }

    private var sr: SpeechRecognizer? = null
    @Volatile private var alive = false
    private var sawWake = false
    private var last = ""
    private val settle = Runnable { fire() }
    private val again = Runnable { listen() }

    fun start() { alive = true; main.post { listen() } }
    fun stop() { alive = false; main.removeCallbacks(settle); main.removeCallbacks(again); main.post { destroy() } }

    private fun destroy() { try { sr?.cancel() } catch (_: Exception) {}; try { sr?.destroy() } catch (_: Exception) {}; sr = null }

    private fun best(b: Bundle?): String = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""

    private fun listen() {
        if (!alive) return
        destroy(); sawWake = false; last = ""
        val r = try { SpeechRecognizer.createSpeechRecognizer(ctx) } catch (_: Exception) { retry(3000); return }
        sr = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(t: Int, b: Bundle?) {}
            override fun onPartialResults(b: Bundle?) {
                val t = best(b); if (t.isEmpty()) return
                last = t
                if (RX.containsMatchIn(t)) { sawWake = true; main.removeCallbacks(settle); main.postDelayed(settle, 2200) }
            }
            override fun onResults(b: Bundle?) {
                val t = best(b); if (t.isNotEmpty()) last = t
                if (RX.containsMatchIn(last)) fire() else retry(150)
            }
            override fun onError(e: Int) {
                if (sawWake) { fire(); return }
                when (e) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> retry(150)
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> retry(1200)
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { alive = false }
                    else -> retry(2500)
                }
            }
        })
        try {
            r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName))
        } catch (_: Exception) { retry(2500) }
    }

    private fun retry(ms: Long) { if (alive) { main.removeCallbacks(again); main.postDelayed(again, ms) } }

    private fun fire() {
        if (!alive) return
        alive = false
        main.removeCallbacks(settle); main.removeCallbacks(again)
        val phrase = last
        destroy()
        onWake(phrase)
    }
}
