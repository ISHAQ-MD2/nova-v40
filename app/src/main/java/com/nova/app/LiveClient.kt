package com.nova.app

import android.content.Context
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import android.util.Base64
import okhttp3.*
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Gemini Live voice + function calling (phone control tools). */
class LiveClient(
    ctx: Context,
    apiKey0: String,
    private val persona: Persona,
    private val userName: String,
    private val onStatus: (String) -> Unit,
    private val onSpeaking: (Boolean) -> Unit,
    private val onEnd: () -> Unit,
    private val onTool: (String, JSONObject) -> String
) {
    companion object {
        // Pehle naya model, na chale to purana try hota hai (khud-ba-khud)
        // Asli (verified) Live models. User Setup mein apna model bhi de sakta hai (pref "model").
        val MODELS = listOf("gemini-3.1-flash-live-preview", "gemini-2.5-flash-native-audio-preview-12-2025")
        const val CONNECT_TIMEOUT_MS = 15000L
        const val MAX_NET_RETRIES = 4
        const val IN_RATE = 16000
        const val OUT_RATE = 24000
        const val IDLE_MS = 25_000L
    }

    private val appCtx = ctx.applicationContext
    @Volatile private var apiKey = apiKey0
    @Volatile private var resumeHandle: String? = null        // v30: session resumption (lambi baat-cheet mein connection badalne par bhi yaaddaasht)
    @Volatile private var lastServerMsg = System.currentTimeMillis()
    @Volatile private var lastLoud = 0L
    @Volatile private var lastRefresh = 0L
    private val inBuf = StringBuilder()
    private val outBuf = StringBuilder()
    @Volatile private var modelIdx = 0
    @Volatile private var gen = 0
    private val modelName get() = "models/" + models[modelIdx]
    private val connectTimeout = Runnable {
        if (running && !ready) failOrNext("Server ne jawab nahi diya (timeout)")
    }
    private val main = Handler(Looper.getMainLooper())
    private val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val http = Net.client(pingSec = 20, readTimeoutSec = 0)
    @Volatile private var netRetries = 0
    private val models: List<String> by lazy {
        val custom = appCtx.getSharedPreferences("nova", Context.MODE_PRIVATE).getString("model", "")?.trim().orEmpty()
        if (custom.isNotEmpty()) listOf(custom) + MODELS.filter { it != custom } else MODELS
    }
    private val toolExec = Executors.newSingleThreadExecutor()
    private var ws: WebSocket? = null
    private val prefs = appCtx.getSharedPreferences("nova", Context.MODE_PRIVATE)
    /** true = sirf "NOVA" bolne par jawab (data bachta hai). */
    private val wakeMode get() = prefs.getBoolean("wake", true) && WakeWord.available(appCtx)
    @Volatile var active = false
    @Volatile var onActive: ((Boolean) -> Unit)? = null
    @Volatile var voiceBusy = false
    @Volatile private var audioGen = 0
    @Volatile private var lastActivity = 0L
    @Volatile private var pendingPhrase: String? = null
    @Volatile private var toolsBusy = 0
    private var wake: WakeWord? = null
    private var lastBattWarn = 0L
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            try { checkIdle(); checkBattery(); watchdog() } catch (_: Exception) {}
            main.postDelayed(this, 5000)
        }
    }
    @Volatile private var running = false
    @Volatile private var ready = false
    @Volatile private var speaking = false

    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null
    private val playQueue = LinkedBlockingQueue<ByteArray>()
    private var micThread: Thread? = null
    private var playThread: Thread? = null

    private fun status(s: String) { main.post { onStatus(s) } }
    private fun setSpeaking(v: Boolean) { if (speaking != v) { speaking = v; main.post { onSpeaking(v) } } }

    fun start() {
        if (running) return
        running = true
        Memory.init(appCtx)
        Tools.imageSink = { bytes -> sendImage(bytes) }
        lastActivity = System.currentTimeMillis()
        main.postDelayed(tick, 5000)
        if (wakeMode) enterPassive()
        else {
            active = true; notifyActive(true)
            commMode()
            if (!Net.online(appCtx)) status("Internet nahi hai - connect hote hi dobara koshish karungi")
            connect()
        }
    }

    private fun notifyActive(a: Boolean) { main.post { onActive?.invoke(a) } }

    /**
     * v30: "5 minute baad NOVA jawab nahi deti jab tak on-off na karo" ka ilaaj.
     * Agar aap bol rahe hain (mic ka level upar) aur 20 second se server ki taraf se kuch nahi aaya, connection adha mara hai - khud dobara jodo.
     */
    private fun watchdog() {
        if (!active || !ready || voiceBusy || speaking || toolsBusy > 0) return
        val now = System.currentTimeMillis()
        if (lastLoud > lastServerMsg + 20_000L && now - lastLoud < 9_000L && now - lastRefresh > 30_000L) {
            lastRefresh = now; lastServerMsg = now
            WallBus.pushFeed("LIVE", "connection refresh (awaaz ja rahi thi, jawab nahi aaya)")
            ready = false
            reconnect("Connection refresh ho raha hai...", 200L)
        }
    }
    /** v18: 0 = MEDIA (volume buttons chalte hain, Bluetooth se awaaz aati hai) - default; 1 = purana call-mode (speaker) */
    private fun mediaRoute() = try { appCtx.getSharedPreferences("nova", Context.MODE_PRIVATE).getInt("audio_route", 0) == 0 } catch (_: Exception) { true }

    private fun commMode() {
        if (mediaRoute()) {
            audioManager.mode = AudioManager.MODE_NORMAL
            @Suppress("DEPRECATION") run { audioManager.isSpeakerphoneOn = false }
        } else {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION") run { audioManager.isSpeakerphoneOn = true }
        }
    }

    /** Sula do: Gemini se connection band, mic sirf phone ke wake-word ke paas. Data zero, koi reaction nahi. */
    fun enterPassive() {
        main.post {
            if (!running) return@post
            gen++
            main.removeCallbacks(connectTimeout)
            try { ws?.close(1000, "idle") } catch (_: Exception) {}
            ws = null; ready = false; active = false; pendingPhrase = null; resumeHandle = null
            flushTranscript()
            stopAudio()
            try { audioManager.mode = AudioManager.MODE_NORMAL } catch (_: Exception) {}
            setSpeaking(false)
            notifyActive(false)
            status("Sun rahi hoon - sirf 'NOVA' bolne par jawab dungi")
            wake?.stop()
            if (!Vol.micMuted) wake = WakeWord(appCtx, main) { phrase -> wake = null; wakeNow(phrase) }.also { it.start() }
        }
    }

    /** Jagao (wake-word, bubble tap ya screen-dekho se). */
    fun wakeNow(phrase: String = "") {
        main.post {
            if (!running) return@post
            lastActivity = System.currentTimeMillis()
            if (active) { if (phrase.isNotBlank()) sendText(phrase); return@post }
            wake?.stop(); wake = null
            pendingPhrase = phrase.ifBlank { "NOVA" }
            active = true; notifyActive(true)
            netRetries = 0
            commMode()
            status("Jaag rahi hoon...")
            connect()
        }
    }

    fun toggleActive() { if (active && wakeMode) enterPassive() else wakeNow("NOVA") }

    private fun checkIdle() {
        if (!wakeMode || !active || !ready || voiceBusy) return
        val now = System.currentTimeMillis()
        if (speaking || toolsBusy > 0 || playQueue.isNotEmpty()) { lastActivity = now; return }
        if (now - lastActivity > IDLE_MS) enterPassive()
    }

    private fun battery(): Pair<Int, Boolean> {
        val i = appCtx.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val lvl = i?.getIntExtra("level", -1) ?: -1; val sc = i?.getIntExtra("scale", 100) ?: 100
        val st = i?.getIntExtra("status", -1) ?: -1
        val charging = st == 2 || st == 5
        return (if (lvl < 0) 100 else lvl * 100 / sc.coerceAtLeast(1)) to charging
    }

    private fun checkBattery() {
        if (!active || !ready || voiceBusy) return
        val (lvl, ch) = battery()
        val now = System.currentTimeMillis()
        if (lvl <= 15 && !ch && now - lastBattWarn > 30 * 60_000L) {
            lastBattWarn = now
            lastActivity = now
            ws?.send(JSONObject().put("realtimeInput", JSONObject().put("text",
                "(system) Phone ki battery sirf $lvl% hai aur charge nahi ho raha. Pyar se, apne partner ki tarah, ek-do line mein bolo ke mobile charge kar lo, battery low hai hum baat kaise karenge.")).toString())
        }
    }

    /** Chal rahe live tools ke khatam hone tak ruko. */
    fun waitIdleTools(maxMs: Long) {
        val end = System.currentTimeMillis() + maxMs
        while (System.currentTimeMillis() < end && toolsBusy > 0) Thread.sleep(200)
    }

    /** WhatsApp voice message ke liye: NOVA apna mic + call-mode chhod deti hai taake WhatsApp record kar sake. */
    fun waitQuiet(maxMs: Long) {
        val end = System.currentTimeMillis() + maxMs
        while (System.currentTimeMillis() < end && (speaking || playQueue.isNotEmpty())) Thread.sleep(200)
    }
    /** Mic block: meri awaaz Gemini tak nahi jati (sunna band). Sote hue wake-word bhi band. */
    fun setMicMuted(m: Boolean) {
        Vol.micMuted = m
        main.post {
            if (!running) return@post
            if (m) { try { wake?.stop() } catch (_: Exception) {}; wake = null; status("🎤 Mic mute - likh kar baat karo") }
            else { status("🎤 Mic ON"); if (!active && wakeMode) enterPassive() }
        }
    }

    @Volatile private var resumeActive = false

    fun forceNormalAudio() {
        try { audioManager.mode = AudioManager.MODE_NORMAL } catch (_: Exception) {}
        @Suppress("DEPRECATION") try { audioManager.isSpeakerphoneOn = false } catch (_: Exception) {}
        if (android.os.Build.VERSION.SDK_INT >= 31) try { audioManager.clearCommunicationDevice() } catch (_: Exception) {}
    }

    /** WhatsApp voice ke liye NOVA poori band: connection, mic, speaker, call-mode, wake-word. */
    fun suspendForVoice() {
        voiceBusy = true
        val latch = java.util.concurrent.CountDownLatch(1)
        main.post {
            try {
                if (active) resumeActive = true
                gen++
                main.removeCallbacks(connectTimeout)
                try { ws?.close(1000, "voice") } catch (_: Exception) {}
                ws = null; ready = false; active = false; pendingPhrase = null
                flushTranscript()
                try { val w = wake; wake = null; w?.stop() } catch (_: Exception) {}
                stopAudio()
                setSpeaking(false)
                notifyActive(false)
                status("Voice message ke liye band hoon...")
            } finally { latch.countDown() }
        }
        try { latch.await(2, TimeUnit.SECONDS) } catch (_: Exception) {}
        forceNormalAudio()
    }

    /** Voice khatam: pehle jaisi halat mein wapas chalu (jagi thi to jag jaye, so rahi thi to sun-ti rahe). */
    fun resumeAfterVoice(note: String = "") {
        main.post {
            voiceBusy = false
            val was = resumeActive; resumeActive = false
            if (!running) return@post
            lastActivity = System.currentTimeMillis()
            if (was) {
                if (wakeMode) wakeNow(note)
                else {
                    active = true; notifyActive(true); netRetries = 0
                    if (note.isNotBlank()) pendingPhrase = note
                    commMode(); status("Jaag rahi hoon..."); connect()
                }
            } else if (wakeMode) enterPassive()
        }
    }

    private fun amp(b: ByteArray): ByteArray {
        val g = Vol.boost
        if (g <= 1.01f) return b
        val o = ByteArray(b.size)
        var i = 0
        while (i + 1 < b.size) {
            var v = ((b[i + 1].toInt() shl 8) or (b[i].toInt() and 0xFF)) * g
            if (v > 32767f) v = 32767f else if (v < -32768f) v = -32768f
            val x = v.toInt()
            o[i] = (x and 0xFF).toByte(); o[i + 1] = (x shr 8).toByte()
            i += 2
        }
        return o
    }

    private fun connect() {
        status("Connecting...")
        val url = "wss://generativelanguage.googleapis.com/ws/" +
            "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
        val my = ++gen
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (my == gen) webSocket.send(setupMessage())
            }
            override fun onMessage(webSocket: WebSocket, text: String) { if (my == gen) safeHandle(text) }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { if (my == gen) safeHandle(bytes.utf8()) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (my != gen || !running) return
                val code = response?.code?.let { " (HTTP $it)" } ?: ""
                failOrNext((t.message ?: "network error") + code)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (my != gen || !running) return
                failOrNext("Closed ($code) $reason")
            }
        }
        ws = http.newWebSocket(Request.Builder().url(url).build(), listener)
        main.removeCallbacks(connectTimeout)
        main.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
    }

    /**
     * Connect hone se pehle fail ho:
     *  - network/DNS error ho to wahi model dobara (backoff ke saath, 4 baar)
     *  - model/server error ho to agla model
     *  - sab fail ho to saaf error dikhao
     */
    private fun failOrNext(why: String) {
        main.removeCallbacks(connectTimeout)
        if (!running) return
        // naya model resumption/compression na samjhe to unke bina dobara
        if (!ready && extrasOn() && (why.contains("Unknown name", true) || why.contains("compression", true) || why.contains("resumption", true) || why.contains("Invalid JSON", true))) {
            prefs.edit().putBoolean("live_extras_off", true).apply()
            reconnect("Dobara jud rahi hoon...", 300L); return
        }
        // quota/key masla: doosri API key
        if (why.contains("429") || why.contains("quota", true) || why.contains("exhausted", true) || why.contains("RESOURCE_EXHAUSTED", true) || why.contains("(HTTP 403)")) {
            val alt = Keys.rotate(appCtx)
            if (alt != null && alt != apiKey) { apiKey = alt; ready = false; resumeHandle = null; reconnect("Doosri API key se jud rahi hoon...", 300L); return }
        }
        if (ready) {
            // Baat-cheet ke beech mein connection toot gaya: khud wapas jud jao
            ready = false
            if (netRetries < MAX_NET_RETRIES) {
                netRetries++; reconnect("Connection toota - dobara jud rahi hoon ($netRetries)...", 1500L * netRetries)
            } else giveUp(why)
            return
        }
        val netIssue = !Net.online(appCtx) || why.contains("resolve host", true) || why.contains("dns", true) ||
            why.contains("timeout", true) || why.contains("timed out", true) || why.contains("failed to connect", true) ||
            why.contains("unreachable", true) || why.contains("connection reset", true) || why.contains("software caused", true)
        if (netIssue && netRetries < MAX_NET_RETRIES) {
            netRetries++
            reconnect("Net ka masla - dobara koshish ($netRetries/$MAX_NET_RETRIES)...", 2000L * netRetries)
        } else if (!netIssue && modelIdx < models.size - 1) {
            modelIdx++
            reconnect("Dusra model try kar rahi hoon...", 300L)
        } else giveUp(why)
    }

    private fun giveUp(why: String) {
        status("Error: " + Net.friendly(appCtx, why))
        if (wakeMode) main.postDelayed({ if (running && !ready) enterPassive() }, 4000) else stop()
    }

    private fun reconnect(msg: String, delayMs: Long) {
        gen++          // purane socket ke cancel ka callback ab ignore hoga
        try { ws?.cancel() } catch (_: Exception) {}
        ws = null
        status(msg)
        main.postDelayed({ if (running && !ready) connect() }, delayMs)
    }

    private fun safeHandle(msg: String) {
        try { handle(msg) } catch (e: Exception) { status("Error: ${e.message}") }
    }

    fun stop() {
        if (!running) return
        running = false; ready = false
        flushTranscript()
        Tools.imageSink = null
        main.removeCallbacks(connectTimeout)
        try { ws?.close(1000, "bye") } catch (_: Exception) {}
        ws = null
        toolExec.shutdownNow()
        main.removeCallbacks(tick)
        wake?.stop(); wake = null
        stopAudio()
        try { audioManager.mode = AudioManager.MODE_NORMAL } catch (_: Exception) {}
        speaking = false; active = false
        main.post { onSpeaking(false); onActive?.invoke(false); onEnd() }
    }

    private fun stopAudio() {
        audioGen++
        micThread?.interrupt(); playThread?.interrupt()
        micThread = null; playThread = null
        try { record?.stop() } catch (_: Exception) {}
        try { record?.release() } catch (_: Exception) {}
        record = null
        try { track?.stop() } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}
        track = null
        try { aec?.release(); ns?.release(); agc?.release() } catch (_: Exception) {}
        aec = null; ns = null; agc = null
        playQueue.clear()
    }

    private fun extrasOn() = !prefs.getBoolean("live_extras_off", false)

    private fun setupMessage(): String {
        val setup = JSONObject().apply {
            put("model", modelName)
            if (extrasOn()) {
                put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject()))
                put("sessionResumption", JSONObject().also { r -> resumeHandle?.let { r.put("handle", it) } })
            }
            put("generationConfig", JSONObject().apply {
                put("responseModalities", JSONArray().put("AUDIO"))
                put("speechConfig", JSONObject().put("voiceConfig",
                    JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", persona.voice))))
            })
            put("systemInstruction", JSONObject().put("parts",
                JSONArray().put(JSONObject().put("text", Brain.live(appCtx, persona, userName)))))
            put("inputAudioTranscription", JSONObject())
            put("outputAudioTranscription", JSONObject())
            put("tools", JSONArray().put(JSONObject().put("functionDeclarations", Tools.declarations(blocking = false))))
        }
        return JSONObject().put("setup", setup).toString()
    }

    private fun handle(msg: String) {
        val json = try { JSONObject(msg) } catch (_: Exception) { return }
        lastServerMsg = System.currentTimeMillis()
        json.optJSONObject("sessionResumptionUpdate")?.let { u ->
            val hnd = u.optString("newHandle")
            if (u.optBoolean("resumable", true) && hnd.isNotEmpty()) resumeHandle = hnd
        }
        if (json.has("goAway")) {
            // server connection badalne wala hai: pehle se naya jod lo (handle se baat wahin se jaari)
            main.post {
                if (running && ready && !voiceBusy && System.currentTimeMillis() - lastRefresh > 10_000L) {
                    lastRefresh = System.currentTimeMillis(); ready = false
                    WallBus.pushFeed("LIVE", "session refresh (goAway)")
                    reconnect("Session refresh...", 150L)
                }
            }
            return
        }
        if (json.has("setupComplete")) {
            main.removeCallbacks(connectTimeout)
            ready = true
            netRetries = 0
            lastActivity = System.currentTimeMillis()
            if (record == null && !voiceBusy) startAudio()
            status("Live - boliye, $userName")
            pendingPhrase?.let { ph -> pendingPhrase = null
                ws?.send(JSONObject().put("realtimeInput", JSONObject().put("text", "(User ne NOVA ko bulaya aur bola:) $ph")).toString()) }
            return
        }
        val calls = json.optJSONObject("toolCall")?.optJSONArray("functionCalls")
        if (calls != null) {
            lastActivity = System.currentTimeMillis()
            for (i in 0 until calls.length()) {
                val c = calls.getJSONObject(i)
                val id = c.optString("id"); val name = c.optString("name")
                val args = c.optJSONObject("args") ?: JSONObject()
                try {
                    toolsBusy++
                    toolExec.execute {
                        try {
                            status("Kaam: $name")
                            val res = try { onTool(name, args) } catch (e: Exception) { "error: ${e.message}" }
                            sendToolResponse(id, name, res)
                        } catch (_: Throwable) {
                        } finally {
                            toolsBusy = (toolsBusy - 1).coerceAtLeast(0)
                            lastActivity = System.currentTimeMillis()
                            status("Live - boliye, $userName")
                        }
                    }
                } catch (_: Exception) { toolsBusy = (toolsBusy - 1).coerceAtLeast(0) }
            }
            return
        }
        val sc = json.optJSONObject("serverContent") ?: return
        if (sc.optBoolean("interrupted")) {
            playQueue.clear()
            try { track?.apply { pause(); flush(); play() } } catch (_: Exception) {}
            setSpeaking(false)
        }
        sc.optJSONObject("inputTranscription")?.optString("text")?.let { if (it.isNotEmpty()) { lastActivity = System.currentTimeMillis(); synchronized(inBuf) { inBuf.append(it); WallBus.live("user", inBuf.toString()) } } }
        sc.optJSONObject("outputTranscription")?.optString("text")?.let { if (it.isNotEmpty()) synchronized(outBuf) { outBuf.append(it); WallBus.live("nova", outBuf.toString()) } }
        if (sc.optBoolean("turnComplete")) flushTranscript()
        val parts = sc.optJSONObject("modelTurn")?.optJSONArray("parts") ?: return
        for (i in 0 until parts.length()) {
            val data = parts.getJSONObject(i).optJSONObject("inlineData")?.optString("data")
            if (!data.isNullOrEmpty() && !voiceBusy) { lastActivity = System.currentTimeMillis(); playQueue.offer(Base64.decode(data, Base64.DEFAULT)) }
        }
    }

    /** Baat-cheet ka poora turn yaaddaasht mein likh do. */
    private fun flushTranscript() {
        val u = synchronized(inBuf) { inBuf.toString().trim().also { inBuf.setLength(0) } }
        val n = synchronized(outBuf) { outBuf.toString().trim().also { outBuf.setLength(0) } }
        if (u.isNotEmpty()) Memory.log("user", u)
        if (n.isNotEmpty()) Memory.log("nova", n)
    }

    /** Screenshot/photo ko live model ko frame ki tarah bhejna. */
    private fun sendImage(jpeg: ByteArray) {
        val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        ws?.send(JSONObject().put("realtimeInput", JSONObject().put("video",
            JSONObject().put("data", b64).put("mimeType", "image/jpeg"))).toString())
    }

    /** Typed message (text chat). */
    fun sendText(t: String) {
        if (!ready || t.isBlank()) { status("Pehle NOVA Start karo"); return }
        lastActivity = System.currentTimeMillis()
        Memory.log("user", t)
        ws?.send(JSONObject().put("realtimeInput", JSONObject().put("text", t)).toString())
    }

    /** Public: camera photo wagaira bhejna. */
    fun sendPhoto(jpeg: ByteArray) { if (ready) sendImage(jpeg) }

    private fun sendToolResponse(id: String, name: String, result: String) {
        val fr = JSONObject().put("id", id).put("name", name).put("response", JSONObject().put("result", result))
        val msg = JSONObject().put("toolResponse", JSONObject().put("functionResponses", JSONArray().put(fr)))
        ws?.send(msg.toString())
    }

    private fun rms(b: ByteArray, n: Int): Float {
        var s = 0.0; var i = 0; var c = 0
        while (i + 1 < n) { val v = ((b[i + 1].toInt() shl 8) or (b[i].toInt() and 0xFF)).toShort().toInt(); s += v.toDouble() * v; i += 2; c++ }
        return if (c == 0) 0f else (Math.sqrt(s / c) / 6000.0).toFloat().coerceIn(0f, 1f)
    }

    private fun startAudio() {
        val ag = ++audioGen
        try {
            val minIn = AudioRecord.getMinBufferSize(IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, IN_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minIn, 6400))
            record = rec
            val sid = rec.audioSessionId
            if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(sid)?.also { it.enabled = true }
            if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(sid)?.also { it.enabled = true }
            if (AutomaticGainControl.isAvailable()) agc = AutomaticGainControl.create(sid)?.also { it.enabled = true }

            val minOut = AudioTrack.getMinBufferSize(OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val tr = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(if (mediaRoute()) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(OUT_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(minOut, 48000))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setSessionId(sid)
                .build()
            track = tr
            tr.play()

            playThread = Thread {
                try {
                    while (running && ag == audioGen) {
                        val chunk = playQueue.poll(500, TimeUnit.MILLISECONDS)
                        if (chunk == null) { Vol.level = 0f; setSpeaking(false); continue }
                        setSpeaking(true)
                        Vol.level = rms(chunk, chunk.size)
                        if (!Vol.speakerMuted) track?.write(amp(chunk), 0, chunk.size)
                    }
                } catch (_: InterruptedException) {}
            }.also { it.start() }

            rec.startRecording()
            micThread = Thread {
                val buf = ByteArray(3200)
                try {
                    while (running && ag == audioGen) {
                        val n = rec.read(buf, 0, buf.size)
                        if (n > 0) { val lv = rms(buf, n); Vol.micLevel = lv; if (lv > 0.12f && ready && !Vol.micMuted) lastLoud = System.currentTimeMillis() }
                        if (n > 0 && ready && !Vol.micMuted) {
                            val b64 = Base64.encodeToString(buf, 0, n, Base64.NO_WRAP)
                            val m = JSONObject().put("realtimeInput", JSONObject().put("audio",
                                JSONObject().put("data", b64).put("mimeType", "audio/pcm;rate=$IN_RATE")))
                            ws?.send(m.toString())
                        } else if (n < 0) { main.post { if (running && ready && ag == audioGen) { stopAudio(); startAudio() } }; break }
                    }
                } catch (_: Exception) {}
            }.also { it.start() }
        } catch (e: Exception) {
            status("Error: ${e.message}")
            stop()
        }
    }
}
