package com.dialorsd.jarvis

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.IBinder
import android.util.Base64
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/*
 * Live mode: a hands-free realtime conversation over the Gemini Live API (WebSocket, BidiGenerateContent).
 * Docs (checked 2026-11-12):
 *  - endpoint, setup, realtimeInput.audio, toolCall/toolResponse:
 *    https://ai.google.dev/gemini-api/docs/live-api/get-started-websocket
 *  - audio 16 kHz PCM in, 24 kHz PCM out, transcription, VAD, interrupted, voices:
 *    https://ai.google.dev/gemini-api/docs/live-api/capabilities
 *  - session resumption, context window compression, goAway:
 *    https://ai.google.dev/gemini-api/docs/live-api/session-management
 *  - tools (function declarations, googleSearch, scheduling): https://ai.google.dev/gemini-api/docs/live-api/tools
 *  - message reference: https://ai.google.dev/api/live
 */

/** Shared HTTP client for WebSockets. */
object Net {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    fun liveRequest(key: String): Request =
        Request.Builder().url(Defaults.LIVE_WS + "?key=" + URLEncoder.encode(key, "UTF-8")).build()
}

/** Facade used by the UI, receivers and the brain. All methods are safe to call from any thread. */
object LiveSession {
    /** Shown on the main screen under the orb while Live mode runs. */
    val status = mutableStateOf("")
    val running = mutableStateOf(false)
    val muted = mutableStateOf(false)
    @Volatile internal var engine: LiveEngine? = null
    val active: Boolean get() = engine != null

    // ---- v4: interpreter mode (same Live connection, translate-only instruction, no tools) ----
    /** True while the running session is the interpreter. */
    val interpreter: Boolean get() = engine?.interpreter == true
    val interpreterOn = mutableStateOf(false)
    /** Last thing heard and its translation, shown large on the interpreter screen. */
    val heard = mutableStateOf("")
    val translated = mutableStateOf("")

    /** Must be called while an activity of ours is visible (microphone foreground-service rule). */
    fun start(ctx: Context, interpreter: Boolean = false) {
        if (active) return
        try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, LiveService::class.java)
                .putExtra(LiveService.EXTRA_INTERPRETER, interpreter))
        } catch (e: Exception) {
            Store.addMessage("note", "Live mode could not start: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun stop(ctx: Context) {
        ctx.startService(Intent(ctx, LiveService::class.java).setAction(LiveService.ACTION_STOP))
    }

    fun sendText(text: String): Boolean = engine?.sendUserText(text) ?: false

    /** Something the phone wants Jarvis to say (calendar heads-up...). */
    fun announce(text: String) {
        if (interpreter) return // would be translated aloud
        engine?.sendUserText("(Notice from the phone, not said by the user. Tell the user briefly, in character: $text)")
    }

    fun toggleMute() {
        val e = engine ?: return
        e.muted = !e.muted
        muted.value = e.muted
    }
}

/** Foreground service (type microphone) that owns the Live session, so it works with the screen off. */
class LiveService : Service() {
    companion object {
        const val ACTION_STOP = "com.dialorsd.jarvis.LIVE_STOP"
        const val EXTRA_INTERPRETER = "interpreter"
        const val CH_LIVE = "jarvis_live"
        private const val NOTIF_ID = 44
    }

    private var engine: LiveEngine? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            engine?.end(null)
            stopSelfNow()
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(NotificationChannel(CH_LIVE, "Live conversation", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while Jarvis is listening in Live mode"
        })
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val end = PendingIntent.getService(this, 1, Intent(this, LiveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CH_LIVE)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle(if (intent?.getBooleanExtra(EXTRA_INTERPRETER, false) == true) "Jarvis interpreter is listening" else "Jarvis Live is listening")
            .setContentText("Talk freely. Tap End to stop.")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(R.drawable.ic_stat_jarvis, "End", end)
            .build()
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, n,
                if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        } catch (e: Exception) {
            // Typically: started while the app was not visible, or no microphone permission.
            Store.addMessage("note", "Live mode could not start (${e.javaClass.simpleName}: ${e.message}). Open the app and try again.")
            stopSelfNow()
            return START_NOT_STICKY
        }
        if (engine == null) {
            val interp = intent?.getBooleanExtra(EXTRA_INTERPRETER, false) == true
            val e = LiveEngine(applicationContext, interp) { stopSelfNow() }
            engine = e
            LiveSession.engine = e
            LiveSession.interpreterOn.value = interp
            if (interp) { LiveSession.heard.value = ""; LiveSession.translated.value = "" }
            LiveSession.running.value = true
            LiveSession.muted.value = false
            HeadsetButton.refresh(this)
            e.begin()
        }
        return START_NOT_STICKY
    }

    private fun stopSelfNow() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        engine?.end(null)
        engine = null
        LiveSession.engine = null
        LiveSession.running.value = false
        LiveSession.interpreterOn.value = false
        LiveSession.status.value = ""
        HeadsetButton.refresh(this)
        super.onDestroy()
    }
}

/** One Live conversation: WebSocket + microphone + speaker. */
class LiveEngine(private val ctx: Context, val interpreter: Boolean, private val onEnded: () -> Unit) {
    private val s = Settings(ctx)
    private val models = listOf(s.liveModel, s.liveFallbackModel).map { it.trim().removePrefix("models/") }.filter { it.isNotBlank() }.distinct()
    private var modelIndex = 0
    @Volatile private var ws: WebSocket? = null
    @Volatile private var ready = false          // setupComplete received on the current socket
    @Volatile private var everReady = false
    @Volatile private var ended = false
    @Volatile private var handle: String? = null // session resumption handle
    @Volatile private var reconnects = 0
    @Volatile var muted = false
    @Volatile private var paused = false         // audio focus lost (phone call...)
    @Volatile private var lastActivity = System.currentTimeMillis()
    private val userBuf = StringBuilder()
    private val jarvisBuf = StringBuilder()
    private val player = LivePlayer()
    private var recThread: Thread? = null
    private val am = ctx.getSystemService(AudioManager::class.java)
    private var focus: AudioFocusRequest? = null
    private var prevMode = AudioManager.MODE_NORMAL

    private fun ukrainian(): Boolean = hasCyrillic(userBuf.toString()) || Store.lastLanguageIsUkrainian()

    fun begin() {
        if (s.geminiKey.isBlank()) { end(Brain.errorLine(BrainException(Failure.NO_KEY, "no key"), ukrainian())); return }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            end("Live mode needs the microphone permission."); return
        }
        Voice.stop()
        LiveSession.status.value = "connecting…"
        Store.setOrb(OrbState.THINKING)
        connect()
    }

    // ---------------------------------------------------------------- socket

    private fun connect() {
        if (ended) return
        ready = false
        LiveSession.status.value = if (reconnects > 0) "reconnecting…" else "connecting…"
        ws = Net.client.newWebSocket(Net.liveRequest(s.geminiKey), Listener())
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (webSocket !== ws) return
            webSocket.send(setupMessage().toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket === ws) handle(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (webSocket === ws) handle(bytes.utf8()) // the server often sends JSON as binary frames
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            if (webSocket === ws) closed(code, reason, null)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (webSocket === ws) closed(response?.code ?: -1, response?.message ?: "", t)
        }
    }

    private fun setupMessage(): JSONObject {
        val model = models[modelIndex.coerceAtMost(models.size - 1)]
        val gen = JSONObject()
            .put("responseModalities", JSONArray().put("AUDIO"))
            .put("speechConfig", JSONObject().put("voiceConfig", JSONObject()
                .put("prebuiltVoiceConfig", JSONObject().put("voiceName", s.ttsVoice))))
        val setup = JSONObject()
            .put("model", "models/$model")
            .put("generationConfig", gen)
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt()))))
        if (!interpreter) setup.put("tools", LiveTools.declarations(s))
        setup
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
            .put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject()))
            .put("sessionResumption", handle?.let { JSONObject().put("handle", it) } ?: JSONObject())
        return JSONObject().put("setup", setup)
    }

    private fun systemPrompt(): String {
        if (interpreter) return Interpreter.instruction(s)
        val c = runCatching { chatContext(ctx, s) }.getOrElse { ChatContext(Store.loadTimers(), null, s.webSearch) }
        return Brain.character(s) + Brain.memoryBlock() + "\n\n" + Brain.contextBlock(c) + """

This is a live, hands-free voice conversation through the phone's loudspeaker. Speak naturally and briefly.
Reply in the language the user speaks: if he speaks Ukrainian, you MUST respond unmistakably in Ukrainian; if English, in English.
To act, call the provided functions (timers, reminders, tasks, memory, notes and lists, calendar, navigation${if (s.webSearch) ", web search" else ""}).
Call a function only when the user asks for that action, then confirm briefly in one sentence using its result.
${Brain.abilityRules()}"""
    }

    private fun handle(text: String) {
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return
        o.optJSONObject("sessionResumptionUpdate")?.let { u ->
            val h = u.optString("newHandle")
            if (u.optBoolean("resumable", false) && h.isNotBlank()) handle = h
        }
        if (o.has("setupComplete")) onReady()
        o.optJSONObject("serverContent")?.let { onContent(it) }
        o.optJSONObject("toolCall")?.let { onToolCall(it) }
        o.optJSONObject("goAway")?.let {
            // The connection will be closed soon (about every 10 minutes): reconnect now with the handle.
            if (handle != null) {
                val old = ws
                reconnects = 0
                Jarvis.scope.launch { flushTranscripts(); connect(); old?.close(1000, "goAway") }
            }
        }
    }

    private fun onReady() {
        ready = true
        reconnects = 0
        val first = !everReady
        everReady = true
        LiveSession.status.value = "live · " + models[modelIndex.coerceAtMost(models.size - 1)]
        if (first) {
            Store.addMessage("note", if (interpreter) "Interpreter on: ${Interpreter.name(s.interpA)} ⇄ ${Interpreter.name(s.interpB)}."
                else if (ukrainian()) "Live-режим увімкнено. Говоріть вільно." else "Live mode on. Speak freely.")
            startAudio()
        }
        Store.setOrb(OrbState.LISTENING)
    }

    private fun onContent(sc: JSONObject) {
        lastActivity = System.currentTimeMillis()
        if (sc.optBoolean("interrupted", false)) {
            player.flush()
            synchronized(this) { if (jarvisBuf.isNotEmpty()) jarvisBuf.append(" …") }
            flushTranscripts()
            Store.setOrb(OrbState.LISTENING)
        }
        sc.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let { t ->
            synchronized(this) {
                if (jarvisBuf.isNotEmpty()) flushJarvis()
                userBuf.append(t)
            }
        }
        sc.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let { t ->
            synchronized(this) {
                if (userBuf.isNotEmpty()) flushUser()
                jarvisBuf.append(t)
            }
        }
        val parts = sc.optJSONObject("modelTurn")?.optJSONArray("parts")
        if (parts != null) for (i in 0 until parts.length()) {
            val d = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
            val data = d.optString("data")
            if (data.isEmpty() || paused) continue
            val pcm = runCatching { Base64.decode(data, Base64.DEFAULT) }.getOrNull() ?: continue
            val rate = Regex("rate=(\\d+)").find(d.optString("mimeType"))?.groupValues?.get(1)?.toIntOrNull() ?: 24000
            player.enqueue(pcm, rate)
            if (Store.orb.value != OrbState.SPEAKING) Store.setOrb(OrbState.SPEAKING)
        }
        if (sc.optBoolean("turnComplete", false)) flushTranscripts()
    }

    @Synchronized private fun flushUser() {
        val t = userBuf.toString().trim()
        userBuf.setLength(0)
        if (t.isNotEmpty()) { Store.addMessage("user", t); if (interpreter) LiveSession.heard.value = t }
    }

    @Synchronized private fun flushJarvis() {
        val t = jarvisBuf.toString().trim()
        jarvisBuf.setLength(0)
        if (t.isNotEmpty()) { Store.addMessage("jarvis", t); if (interpreter) LiveSession.translated.value = t }
    }

    @Synchronized private fun flushTranscripts() { flushUser(); flushJarvis() }

    private fun onToolCall(tc: JSONObject) {
        val calls = tc.optJSONArray("functionCalls") ?: return
        Store.setOrb(OrbState.THINKING)
        synchronized(this) { flushUser() }
        val uk = ukrainian()
        Jarvis.scope.launch {
            val responses = JSONArray()
            for (i in 0 until calls.length()) {
                val fc = calls.optJSONObject(i) ?: continue
                val result = runCatching { LiveTools.run(ctx, s, fc.optString("name"), fc.optJSONObject("args") ?: JSONObject(), uk) }
                    .getOrElse { "error: ${it.message}" }
                // "scheduling" is read by models with asynchronous function calling and ignored by the others.
                val resp = JSONObject().put("result", result).put("scheduling", "WHEN_IDLE")
                responses.put(JSONObject().put("id", fc.optString("id")).put("name", fc.optString("name")).put("response", resp))
            }
            ws?.send(JSONObject().put("toolResponse", JSONObject().put("functionResponses", responses)).toString())
            if (Store.orb.value == OrbState.THINKING) Store.setOrb(OrbState.LISTENING)
        }
    }

    fun sendUserText(text: String): Boolean {
        val w = ws ?: return false
        if (!ready) return false
        lastActivity = System.currentTimeMillis()
        synchronized(this) { flushTranscripts() }
        return w.send(JSONObject().put("realtimeInput", JSONObject().put("text", text)).toString())
    }

    private fun closed(code: Int, reason: String, t: Throwable?) {
        if (ended) return
        ready = false
        ws = null // ignore any later events from this socket
        val kind = LiveErrors.classify(code, reason, t)
        val detail = LiveErrors.describe(code, reason, t)
        val uk = ukrainian()
        flushTranscripts()
        // Model refused before the session started: try the backup Live model once.
        if (!everReady && kind == Failure.MODEL && modelIndex < models.size - 1) {
            Store.addMessage("note", "Live model ${models[modelIndex]} refused ($detail); trying ${models[modelIndex + 1]}.")
            modelIndex++
            connect()
            return
        }
        when (kind) {
            Failure.QUOTA, Failure.BAD_KEY, Failure.MODEL, Failure.NO_KEY -> {
                end(Brain.errorLine(BrainException(kind, detail), uk) + " (Live: $detail)")
                return
            }
            else -> {}
        }
        // Something rejected the setup itself: retrying will not help.
        if (!everReady && kind == Failure.OTHER) {
            end((if (uk) "Сер, Live-режим не запустився: " else "I'm afraid Live mode would not start, sir: ") + detail)
            return
        }
        // Network drop, server restart or session time limit: reconnect (resumes if we have a handle).
        if (reconnects < 3) {
            reconnects++
            val wait = 1000L shl reconnects
            LiveSession.status.value = "reconnecting…"
            Store.setOrb(OrbState.THINKING)
            Jarvis.scope.launch {
                kotlinx.coroutines.delay(wait)
                if (!ended) {
                    if (handle == null && everReady) Store.addMessage("note", if (uk) "З'єднання перервалося; починаю нову розмову." else "Connection dropped; starting a fresh Live session.")
                    connect()
                }
            }
            return
        }
        val msg = when {
            LiveErrors.isTimeLimit(reason) -> if (uk) "Сер, сесію завершено через ліміт часу Google. Натисніть Live, щоб почати знову."
                else "Our session has reached Google's time limit, sir. Tap Live to start again."
            kind == Failure.NETWORK -> Brain.errorLine(BrainException(Failure.NETWORK, detail), uk)
            else -> (if (uk) "Сер, зв'язок з Live втрачено: " else "I've lost the Live connection, sir: ") + detail
        }
        end(msg)
    }

    /** Ends the session. [message] (spoken-style) is added to the chat when not null. */
    fun end(message: String?) {
        if (ended) return
        ended = true
        flushTranscripts()
        if (message != null) Store.addMessage("jarvis", message)
        Store.addMessage("note", if (interpreter) "Interpreter off." else if (ukrainian()) "Live-режим вимкнено." else "Live mode ended.")
        runCatching { ws?.close(1000, "bye") }
        ws = null
        recThread?.interrupt()
        player.release()
        releaseAudioRouting()
        Store.setOrb(OrbState.IDLE)
        LiveSession.status.value = ""
        onEnded()
    }

    // ---------------------------------------------------------------- audio

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                paused = true; player.flush(); LiveSession.status.value = "paused (another app or a call)"
            }
            AudioManager.AUDIOFOCUS_GAIN -> { paused = false; LiveSession.status.value = "live" }
            AudioManager.AUDIOFOCUS_LOSS -> Jarvis.scope.launch {
                end(if (ukrainian()) "Сер, інша програма забрала звук; Live-режим вимкнено." else "Another app has taken the audio, sir, so I've ended Live mode.")
            }
        }
    }

    @SuppressLint("MissingPermission") // checked in begin()
    private fun startAudio() {
        setupAudioRouting()
        recThread = Thread({ recordLoop() }, "live-mic").also { it.start() }
    }

    private fun setupAudioRouting() {
        val a = am ?: return
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener(focusListener).build()
        runCatching { a.requestAudioFocus(focus!!) }
        prevMode = a.mode
        // Communication mode turns on the phone's echo canceller path, so he can be interrupted while speaking.
        runCatching { a.mode = AudioManager.MODE_IN_COMMUNICATION }
        if (Build.VERSION.SDK_INT >= 31) {
            val devices = runCatching { a.availableCommunicationDevices }.getOrDefault(emptyList())
            val order = listOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
            val pick = order.firstNotNullOfOrNull { t -> devices.firstOrNull { it.type == t } }
            if (pick != null) runCatching { a.setCommunicationDevice(pick) }
        } else {
            @Suppress("DEPRECATION")
            runCatching { a.isSpeakerphoneOn = !a.isWiredHeadsetOn }
        }
    }

    private fun releaseAudioRouting() {
        val a = am ?: return
        if (Build.VERSION.SDK_INT >= 31) runCatching { a.clearCommunicationDevice() }
        else {
            @Suppress("DEPRECATION")
            runCatching { a.isSpeakerphoneOn = false }
        }
        runCatching { a.mode = prevMode }
        focus?.let { f -> runCatching { a.abandonAudioFocusRequest(f) } }
    }

    @SuppressLint("MissingPermission")
    private fun recordLoop() {
        val rate = 16000
        val chunk = rate / 25 * 2 // 40 ms of 16-bit mono = 1280 bytes
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, chunk * 8))
        } catch (e: Exception) {
            Jarvis.scope.launch { end("Microphone error: ${e.message}") }; return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            Jarvis.scope.launch { end("The microphone is busy or unavailable.") }; return
        }
        val effects = mutableListOf<android.media.audiofx.AudioEffect>()
        runCatching { if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)?.let { it.enabled = true; effects += it } }
        runCatching { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.let { it.enabled = true; effects += it } }
        runCatching { if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(rec.audioSessionId)?.let { it.enabled = true; effects += it } }
        val buf = ByteArray(chunk)
        var sending = true
        try {
            rec.startRecording()
            while (!ended && !Thread.currentThread().isInterrupted) {
                var n = 0
                while (n < chunk) {
                    val r = rec.read(buf, n, chunk - n)
                    if (r <= 0) break
                    n += r
                }
                if (n <= 0) { Thread.sleep(20); continue }
                val speaking = player.speaking()
                if (Store.orb.value == OrbState.SPEAKING && !speaking) Store.setOrb(OrbState.LISTENING)
                // Echo guard: without barge-in, the mic is not sent while he speaks.
                val send = ready && !muted && !paused && (s.liveBargeIn || !speaking)
                if (send) {
                    val b64 = Base64.encodeToString(buf, 0, n, Base64.NO_WRAP)
                    ws?.send("{\"realtimeInput\":{\"audio\":{\"data\":\"$b64\",\"mimeType\":\"audio/pcm;rate=16000\"}}}")
                } else if (sending && ready) {
                    ws?.send("{\"realtimeInput\":{\"audioStreamEnd\":true}}")
                }
                sending = send
                if (speaking) lastActivity = System.currentTimeMillis()
                // Battery: end a forgotten session after 10 minutes with nothing said by either side.
                if (System.currentTimeMillis() - lastActivity > 10 * 60_000L) {
                    Jarvis.scope.launch { end(if (ukrainian()) "Тиша вже десять хвилин, сер; вимикаю Live-режим." else "Ten minutes of silence, sir; I've ended Live mode to save the battery.") }
                    break
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            if (!ended) Jarvis.scope.launch { end("Microphone error: ${e.message}") }
        } finally {
            runCatching { rec.stop() }
            effects.forEach { runCatching { it.release() } }
            rec.release()
        }
    }
}

/** Low-latency streaming playback of 24 kHz PCM chunks; flush() stops at once (barge-in). */
class LivePlayer {
    private val queue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var track: AudioTrack? = null
    @Volatile private var rate = 24000
    @Volatile private var playUntil = 0L
    @Volatile private var closed = false
    private var thread: Thread? = null

    fun enqueue(pcm: ByteArray, sampleRate: Int) {
        if (closed) return
        if (track == null || sampleRate != rate) open(sampleRate)
        queue.offer(pcm)
    }

    fun speaking(): Boolean = queue.isNotEmpty() || System.currentTimeMillis() < playUntil

    @Synchronized private fun open(sampleRate: Int) {
        if (track != null && sampleRate == rate) return
        track?.let { runCatching { it.stop() }; it.release() }
        rate = sampleRate
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(min, rate / 5 * 2)) // ~200 ms
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        t.play()
        track = t
        if (thread == null) thread = Thread({ loop() }, "live-speaker").also { it.start() }
    }

    private fun loop() {
        while (!closed) {
            val b = try { queue.poll(100, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { break } ?: continue
            val t = track ?: continue
            val even = b.size - (b.size % 2)
            val now = System.currentTimeMillis()
            playUntil = maxOf(now, playUntil) + (even / 2) * 1000L / rate
            var off = 0
            while (off < even && !closed) {
                val n = runCatching { t.write(b, off, even - off) }.getOrDefault(-1)
                if (n <= 0) break
                off += n
            }
        }
    }

    /** Barge-in: drop everything queued and what is in the device buffer. */
    fun flush() {
        queue.clear()
        playUntil = 0L
        track?.let { t -> runCatching { t.pause(); t.flush(); t.play() } }
    }

    fun release() {
        closed = true
        queue.clear()
        thread?.interrupt()
        track?.let { runCatching { it.stop() }; it.release() }
        track = null
    }
}

/** Live function declarations mirroring the text-mode actions. */
object LiveTools {
    private fun str(desc: String) = JSONObject().put("type", "STRING").put("description", desc)
    private fun fn(name: String, desc: String, props: Map<String, JSONObject>, required: List<String>): JSONObject {
        val o = JSONObject().put("name", name).put("description", desc)
        if (props.isNotEmpty()) {
            val p = JSONObject(); props.forEach { (k, v) -> p.put(k, v) }
            o.put("parameters", JSONObject().put("type", "OBJECT").put("properties", p).put("required", JSONArray(required)))
        }
        return o
    }

    fun declarations(s: Settings): JSONArray {
        val f = JSONArray()
        f.put(fn("set_timer", "Set a countdown timer. When it ends Jarvis phones the user. Call when the user asks for a timer.",
            mapOf("seconds" to JSONObject().put("type", "INTEGER").put("description", "Duration in seconds, e.g. 10 minutes = 600"),
                "label" to str("Short name, e.g. pasta")), listOf("seconds")))
        f.put(fn("set_reminder", "Phone the user at an exact local date and time to remind him of something.",
            mapOf("at" to str("Local time as YYYY-MM-DDTHH:MM"), "text" to str("What to remind about")), listOf("at", "text")))
        f.put(fn("cancel_timer", "Cancel timers or reminders whose name matches, or all.",
            mapOf("label" to str("Name of the timer/reminder, or 'all'")), listOf("label")))
        f.put(fn("start_task", "Start a long background job (research, writing, planning). Jarvis phones the user with the result.",
            mapOf("prompt" to str("Complete, self-contained instruction"), "label" to str("Short name")), listOf("prompt")))
        f.put(fn("remember", "Store a lasting fact about the user (not passwords).", mapOf("text" to str("The fact")), listOf("text")))
        f.put(fn("forget", "Delete remembered facts containing these words.", mapOf("match" to str("Words identifying the memory")), listOf("match")))
        f.put(fn("note_add", "Save a note the user dictates.", mapOf("text" to str("The note")), listOf("text")))
        f.put(fn("list_add", "Add one item to a named list such as shopping.",
            mapOf("list" to str("List name, e.g. shopping"), "item" to str("One item")), listOf("list", "item")))
        f.put(fn("list_remove", "Remove or tick off an item from a named list.",
            mapOf("list" to str("List name"), "item" to str("Item")), listOf("list", "item")))
        f.put(fn("list_clear", "Remove every item from a named list.", mapOf("list" to str("List name")), listOf("list")))
        f.put(fn("calendar_add", "Add an event to the phone's calendar.",
            mapOf("title" to str("Event title"), "start" to str("Local start YYYY-MM-DDTHH:MM"),
                "end" to str("Local end YYYY-MM-DDTHH:MM (optional)"), "location" to str("Optional place")), listOf("title", "start")))
        f.put(fn("navigate", "Start Google Maps navigation to a place.", mapOf("destination" to str("Address or place name")), listOf("destination")))
        // ---- v4 ----
        val int = { d: String -> JSONObject().put("type", "INTEGER").put("description", d) }
        f.put(fn("notifications_read", "Read the user's recent phone notifications (only from apps he allowed). Use ONLY when he asks about his messages or notifications.",
            mapOf("app" to str("Optional app name, e.g. Telegram or Gmail"), "since_minutes" to int("How far back, default 120"),
                "limit" to int("Max items, default 10")), emptyList()))
        f.put(fn("watch_add", "Watch a web page and phone the user when a condition is met. Checks every interval_minutes (min 15, default 30). " +
            "Number kinds only work on simple pages that show the number in their HTML.",
            mapOf("kind" to str("site_up, page_contains, page_not_contains, page_changed, price_below, number_below or number_above"),
                "target" to str("Web address"), "condition" to str("Phrase to find; 'down' for site_up to wait for it going down; for numbers an optional phrase the number follows"),
                "threshold" to JSONObject().put("type", "NUMBER").put("description", "Number for price_below/number_below/number_above"),
                "label" to str("Short name"), "interval_minutes" to int("Minutes between checks (15 or more)"),
                "repeat" to JSONObject().put("type", "BOOLEAN").put("description", "Keep watching after it fires (default false)")),
            listOf("kind", "target")))
        f.put(fn("watch_list", "List the active web page watchers.", emptyMap(), emptyList()))
        f.put(fn("watch_remove", "Remove watchers matching a label, address, #id, or all.", mapOf("match" to str("What to remove")), listOf("match")))
        f.put(fn("pc_command", "Send a named command to the user's paired PC and get its reply. status = CPU, memory, disk, uptime, top programs " +
            "(arg = a program name to check). watch_exit = phone him when program arg exits. shutdown_when_done = shut down after program arg exits. " +
            "sleep/shutdown/restart/shutdown_when_done need confirmed=true, which you may only set after asking 'Shall I, sir?' and hearing a clear yes.",
            mapOf("cmd" to str("status, lock, sleep, shutdown, restart, cancel_shutdown, shutdown_when_done, watch_exit or run"),
                "name" to str("For run: the name of a command the user set up on the PC"), "arg" to str("Program/process name where needed"),
                "confirmed" to JSONObject().put("type", "BOOLEAN").put("description", "True only after the user said yes")), listOf("cmd")))
        f.put(fn("day_summary", "What happened today (timers, tasks, PC messages, watchers), what is still running, open items, tomorrow's events and weather.",
            emptyMap(), emptyList()))
        if (s.webSearch && !s.liveGrounding) {
            f.put(fn("web_search", "Look up current information on the web (news, weather, prices, opening hours, recent facts).",
                mapOf("query" to str("Search query")), listOf("query")))
        }
        val tools = JSONArray().put(JSONObject().put("functionDeclarations", f))
        if (s.webSearch && s.liveGrounding) tools.put(JSONObject().put("googleSearch", JSONObject()))
        return tools
    }

    /** Executes one function call and returns a short result text for the model. */
    suspend fun run(ctx: Context, s: Settings, name: String, args: JSONObject, uk: Boolean): String {
        if (name == "web_search") {
            val q = args.optString("query")
            val r = withContext(Dispatchers.IO) { Brain.lookup(s, q) }
            Store.addSources(r.sources)
            return r.text.take(3000)
        }
        val type = when (name) {
            "set_timer" -> "timer"; "set_reminder" -> "reminder"; "start_task" -> "task"
            else -> name
        }
        val secs = args.optDouble("seconds", 0.0).let { if (it.isNaN()) 0L else it.toLong() }
        val a = Action(type, secs, args.optString("label"), args.optString("prompt"), args)
        Info.run(ctx, a, uk)?.let { return it.take(4000) } // v4 lookups: the result goes straight back to the model
        val (notes, fails) = Jarvis.runActions(ctx, listOf(a), uk)
        if (notes.isNotBlank()) Store.addMessage("note", notes)
        return listOf(notes, fails).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "done" }
    }
}

object LiveErrors {
    fun classify(code: Int, reason: String, t: Throwable?): Failure {
        val r = (reason + " " + (t?.message ?: "")).lowercase()
        return when {
            t is UnknownHostException -> Failure.NETWORK
            isTimeLimit(r) -> Failure.SERVER // "deadline exceeded" is not a quota problem
            code == 429 || "quota" in r || "resource_exhausted" in r || "resource exhausted" in r || "rate limit" in r || "exceeded" in r -> Failure.QUOTA
            code == 401 || code == 403 || "api key" in r || "api_key" in r || "unauthenticated" in r || "permission denied" in r -> Failure.BAD_KEY
            code == 404 || "not found" in r || "is not supported" in r || "unsupported model" in r || "model" in r && "not" in r -> Failure.MODEL
            code == 1007 || code == 1008 || code == 400 -> Failure.OTHER
            t is IOException -> Failure.NETWORK
            else -> Failure.SERVER
        }
    }

    fun isTimeLimit(reason: String): Boolean {
        val r = reason.lowercase()
        return "deadline" in r || "time limit" in r || "duration" in r || "session" in r && "limit" in r
    }

    fun describe(code: Int, reason: String, t: Throwable?): String =
        listOfNotNull(if (code >= 0) "code $code" else null, reason.takeIf { it.isNotBlank() },
            t?.let { "${it.javaClass.simpleName}: ${it.message}" }).joinToString(", ").ifBlank { "unknown" }.take(300)

    /** Diagnostics: open a socket, send a minimal setup, wait for setupComplete. Returns null if OK. Blocking. */
    fun probe(s: Settings, model: String): String? {
        if (s.geminiKey.isBlank()) return "No Gemini API key saved."
        val latch = CountDownLatch(1)
        var result: String? = "timed out after 20 s without setupComplete"
        val ws = Net.client.newWebSocket(Net.liveRequest(s.geminiKey), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val setup = JSONObject().put("setup", JSONObject().put("model", "models/" + model.removePrefix("models/"))
                    .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("AUDIO"))
                        .put("speechConfig", JSONObject().put("voiceConfig", JSONObject()
                            .put("prebuiltVoiceConfig", JSONObject().put("voiceName", s.ttsVoice)))))
                    .put("tools", LiveTools.declarations(s)))
                webSocket.send(setup.toString())
            }
            fun msg(text: String) {
                if (text.contains("setupComplete")) { result = null; latch.countDown() }
            }
            override fun onMessage(webSocket: WebSocket, text: String) = msg(text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = msg(bytes.utf8())
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (latch.count > 0) { result = "closed: " + describe(code, reason, null); latch.countDown() }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (latch.count > 0) { result = "failed: " + describe(response?.code ?: -1, response?.message ?: "", t); latch.countDown() }
            }
        })
        latch.await(20, TimeUnit.SECONDS)
        ws.close(1000, "probe done")
        return result
    }
}
