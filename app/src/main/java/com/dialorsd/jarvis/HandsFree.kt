package com.dialorsd.jarvis

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * v5 hands-free mode: a loop of the phone's own speech recognition -> the text brain (Groq first if chosen, then
 * Gemini) -> speech (Gemini voice while it has quota, else the phone voice) -> listen again. Costs no Live quota.
 * Runs inside LiveService (same notification with End), started by hand or when every Live model is out of quota.
 * The recognizer is never running while Jarvis speaks, so he cannot hear himself.
 *
 * v6 Groq voice mode ([profile] GROQ): the same loop, but hearing is Groq Whisper (the microphone is read with
 * AudioRecord, the on-device VoiceGate cuts one utterance, a 16 kHz mono WAV goes to Groq), the brain is Groq-first
 * (fast chat model, then Gemini) and speaking is Groq's Orpheus voice first (English), then Gemini, then the phone.
 * Every step falls back: Whisper limit/error -> the phone recognizer for as long as Groq says (and back afterwards).
 * [idleOverrideMs] > 0: a session that continues a call, which ends after that much silence.
 */
class HandsFreeEngine(private val ctx: Context, private val announce: String?, private val profile: MainMode = MainMode.PHONE,
                      private val idleOverrideMs: Long = 0L, private val onEnded: () -> Unit) : Conversation {
    override val interpreter = false
    override val handsFree = true
    @Volatile override var muted = false

    private val s = Settings(ctx)
    private val main = Handler(Looper.getMainLooper())
    private var rec: SpeechRecognizer? = null
    @Volatile private var ended = false
    @Volatile private var listening = false
    @Volatile private var turn = 0                    // bumps on every new thing to answer; stale callbacks check it
    @Volatile private var lastHeard = System.currentTimeMillis()
    @Volatile private var lastCallback = System.currentTimeMillis()
    private var quietRestarts = 0                      // NO_MATCH / timeouts in a row (nobody talking)
    private var errors = 0                             // real recognizer errors in a row
    private var defaultLanguage = false                // the chosen language was refused: use the phone's default
    private var geminiVoice = true
    private val mutedStreams = mutableListOf<Int>()
    private val am = ctx.getSystemService(AudioManager::class.java)

    // ---- v6: Groq voice mode state ----
    private val groqMode = profile == MainMode.GROQ && s.groqKey.isNotBlank()
    @Volatile private var capToken = Any()            // the capture thread runs while its token is current
    @Volatile private var capThread: Thread? = null
    @Volatile private var speakingNow = false         // Jarvis is talking through the speaker
    @Volatile private var busy = false                // an utterance is being transcribed / answered
    @Volatile private var bargedIn = false
    @Volatile private var ignoreUntil = 0L            // the echo tail after he stops talking
    private var whisperOff = false                    // Whisper failed for good this session: phone recognizer only
    private var sttFails = 0
    private val notedLimits = mutableSetOf<String>()

    private fun status(t: String) { LiveSession.status.value = (if (groqMode) "groq voice" else "hands-free") + " · $t" }

    private fun micOk() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Hear with Groq Whisper right now? (Not while Groq says its limit is reached: the phone recognizer covers that.) */
    private fun wantWhisper(): Boolean =
        groqMode && s.hearing == Hearing.WHISPER && micOk() && !whisperOff && GroqLimits.check("stt") == null

    private fun idleLimitMs() = if (idleOverrideMs > 0) idleOverrideMs else s.liveIdleMinutes * 60_000L

    override fun begin() {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx) && !wantWhisper()) {
            end("Speech recognition is not available on this phone, sir, so hands-free mode cannot run."); return
        }
        Voice.init(ctx)
        if (groqMode) {
            Store.addMessage("note", "Groq voice mode on (hearing: ${if (wantWhisper()) "Groq Whisper" else "phone recognizer"}; " +
                "brain: Groq ${s.groqVoiceModel}, then Gemini; voice: ${if (s.groqTtsOn) "Groq, then Gemini/phone" else "Gemini/phone"}). " +
                "No Live quota is used. Tap END to stop.")
        } else {
            val brain = if (s.handsFreePreferGroq && s.groqKey.isNotBlank()) "Groq, then Gemini" else "Gemini, then Groq"
            Store.addMessage("note", "Hands-free mode on (phone speech recognition; brain: $brain). No Live quota is used. Tap END to stop.")
        }
        lastHeard = System.currentTimeMillis()
        if (announce != null) {
            Store.addMessage("jarvis", announce)
            speakThenListen(announce, ++turn)
        } else listenSoon(0)
        main.postDelayed(watchdog, 5000)
    }

    /** Ends after the idle time; restarts a recognizer that went silent without any callback. */
    private val watchdog = object : Runnable {
        override fun run() {
            if (ended) return
            val now = System.currentTimeMillis()
            val capturing = capThread?.isAlive == true
            val idleNow = if (capturing) !busy && !speakingNow else listening
            if (!muted && now - lastHeard > idleLimitMs() && idleNow) {
                if (idleOverrideMs > 0) { end(null); return }
                val m = s.liveIdleMinutes
                end(if (Store.lastLanguageIsUkrainian()) "Тиша вже $m хв, сер; вимикаю режим без рук." else "$m minutes of silence, sir; I've ended hands-free mode.")
                return
            }
            if (!capturing && listening && now - lastCallback > 30_000) listenSoon(0)
            main.postDelayed(this, 5000)
        }
    }

    // ---------------------------------------------------------------- listening

    private fun listenSoon(delayMs: Long) {
        val t = turn
        main.postDelayed({ if (t == turn) listen() }, delayMs)
    }

    private fun listen() {
        if (ended || muted) return
        if (wantWhisper()) {
            destroyRec()
            startCapture()
            if (!busy && !speakingNow) { Store.setOrb(OrbState.LISTENING); status("listening") }
            return
        }
        stopCapture()
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) { end("Neither Groq Whisper nor the phone's speech recognition can run now, sir."); return }
        destroyRec()
        val r = try { SpeechRecognizer.createSpeechRecognizer(ctx) } catch (e: Exception) { null }
        if (r == null) { failed("could not create the recognizer"); return }
        rec = r
        val myTurn = turn
        r.setRecognitionListener(object : RecognitionListener {
            fun stale() = ended || myTurn != turn || rec !== r
            override fun onReadyForSpeech(params: Bundle?) {
                if (stale()) return
                lastCallback = System.currentTimeMillis()
                Store.setOrb(OrbState.LISTENING)
                status("listening")
                main.postDelayed({ quietBeep(false) }, 600)
            }
            override fun onBeginningOfSpeech() { if (!stale()) { lastCallback = System.currentTimeMillis(); errors = 0 } }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { if (!stale()) lastCallback = System.currentTimeMillis() }
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {
                if (stale()) return
                lastCallback = System.currentTimeMillis()
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { Store.partialSpeech.value = it }
            }
            override fun onResults(results: Bundle?) {
                quietBeep(false)
                if (stale()) return
                listening = false
                Store.partialSpeech.value = ""
                errors = 0
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
                if (text.isNullOrBlank()) { quiet(); return }
                quietRestarts = 0
                heard(text, addUser = true)
            }
            override fun onError(error: Int) {
                quietBeep(false)
                if (stale()) return
                listening = false
                Store.partialSpeech.value = ""
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> quiet()
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> end("Hands-free mode needs the microphone permission, sir.")
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                        if (!defaultLanguage) {
                            defaultLanguage = true
                            Store.addMessage("note", "The chosen speech language is not available for recognition; using the phone's default language.")
                        }
                        failed("language $error", 300)
                    }
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER,
                    SpeechRecognizer.ERROR_SERVER_DISCONNECTED, SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> failed("network/server $error", 3000)
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> failed("busy/client $error", 700)
                    else -> failed("error $error", 1000)
                }
            }
        })
        val tag = when (s.speechLang) {
            SpeechLang.EN -> "en-GB"
            SpeechLang.UK -> "uk-UA"
            SpeechLang.AUTO -> Locale.getDefault().toLanguageTag()
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        if (!defaultLanguage) intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
        try {
            quietBeep(true)
            listening = true
            lastCallback = System.currentTimeMillis()
            Store.setOrb(OrbState.LISTENING)
            status("listening")
            r.startListening(intent)
        } catch (e: Exception) {
            quietBeep(false)
            listening = false
            failed("start: ${e.message}", 1000)
        }
    }

    /** Nobody spoke: listen again at once, a little slower after a long quiet spell (fewer beeps, less battery). */
    private fun quiet() {
        quietRestarts++
        listenSoon(if (quietRestarts > 20) 1500 else 150)
    }

    private fun failed(what: String, retryMs: Long = 1000) {
        errors++
        destroyRec()
        if (errors >= 8) {
            end("Speech recognition keeps failing, sir ($what), so I've ended hands-free mode.")
            return
        }
        listenSoon(retryMs)
    }

    private fun destroyRec() {
        val r = rec ?: return
        rec = null
        listening = false
        runCatching { r.cancel() }
        runCatching { r.destroy() }
    }

    /** Mute the recognizer's start beep (only streams that were not already muted; always restored). */
    private fun quietBeep(on: Boolean) {
        val a = am ?: return
        if (on) {
            if (!s.handsFreeQuietBeep || mutedStreams.isNotEmpty()) return
            for (st in listOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM)) {
                val already = runCatching { a.isStreamMute(st) }.getOrDefault(true)
                if (!already && runCatching { a.adjustStreamVolume(st, AudioManager.ADJUST_MUTE, 0) }.isSuccess) mutedStreams += st
            }
        } else {
            mutedStreams.forEach { st -> runCatching { a.adjustStreamVolume(st, AudioManager.ADJUST_UNMUTE, 0) } }
            mutedStreams.clear()
        }
    }

    // ---------------------------------------------------------------- answering

    /** [addUser]: false when the text was typed (Jarvis.send already shows it). */
    private fun heard(text: String, addUser: Boolean) {
        val myTurn = ++turn
        destroyRec()
        quietBeep(false)
        Voice.stop()
        busy = true
        lastHeard = System.currentTimeMillis()
        val history = Store.messages.toList()
        if (addUser) Store.addMessage("user", text)
        Store.setOrb(OrbState.THINKING)
        status("thinking")
        Jarvis.scope.launch {
            val uk = hasCyrillic(text)
            val reply = try {
                withContext(Dispatchers.IO) {
                    Brain.chat(s, history, text, chatContext(ctx, s), image = null, preferGroq = groqMode || s.handsFreePreferGroq,
                        groqModels = if (groqMode) listOf(s.groqVoiceModel, s.groqModel) else null,
                        info = { a -> runBlocking { Info.run(ctx, a, uk) } })
                }
            } catch (e: Exception) {
                Reply(Brain.errorLine(e, uk), emptyList())
            }
            if (ended) return@launch
            val (notes, failed) = Jarvis.runActions(ctx, reply.actions, uk)
            var say = reply.say.ifBlank { notes.ifBlank { if (uk) "Так, сер." else "Very good, sir." } }
            if (failed.isNotBlank()) say += " " + failed
            if (groqMode) groqLimitLine(uk)?.let { say += " $it" }
            Store.addMessage("jarvis", say)
            Store.addSources(reply.sources)
            if (notes.isNotBlank() && reply.say.isNotBlank()) Store.addMessage("note", notes)
            withContext(Dispatchers.IO) { runCatching { Proactive.scheduleEvents(ctx) } }
            if (myTurn == turn) speakThenListen(say, myTurn)
        }
    }

    private fun speakThenListen(text: String, myTurn: Int) {
        status("speaking")
        speakingNow = true
        bargedIn = false
        Jarvis.scope.launch {
            try {
                if (!muted) {
                    Voice.speak(ctx, text, allowGemini = geminiVoice, groqFirst = groqMode)
                    if (geminiVoice && Voice.lastGeminiQuota) {
                        geminiVoice = false
                        Store.addMessage("note", "Gemini voice is out of quota; hands-free continues with the phone voice.")
                    }
                    Voice.lastGroqIssue?.let { why ->
                        if (notedLimits.add("tts-issue:" + why.take(40)))
                            Store.addMessage("note", GroqApi.ttsIssueNote(s, why))
                    }
                }
            } finally {
                speakingNow = false
                if (!bargedIn) ignoreUntil = System.currentTimeMillis() + 350 // do not hear the tail of his own voice
                if (myTurn == turn) busy = false
            }
            if (ended || myTurn != turn) return@launch
            lastHeard = System.currentTimeMillis() // the idle time counts from the end of his answer
            listenSoon(250)
        }
    }

    // ---------------------------------------------------------------- v6: Groq Whisper hearing

    private fun startCapture() {
        if (capThread?.isAlive == true) return
        val token = Any()
        capToken = token
        val t = Thread({ captureLoop(token) }, "groq-mic")
        capThread = t
        t.start()
    }

    private fun stopCapture() {
        capToken = Any()
        val t = capThread ?: return
        capThread = null
        t.interrupt()
        runCatching { t.join(400) }
    }

    /** One AudioRecord for as long as Groq voice mode listens; the gate cuts utterances out of it. */
    @SuppressLint("MissingPermission") // checked in wantWhisper()
    private fun captureLoop(token: Any) {
        val rate = 16000
        val chunk = rate / 25 * 2 // 40 ms
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, chunk * 8))
        } catch (e: Exception) { null }
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            rec?.release()
            Store.runOnMain { if (!ended && token === capToken) whisperUnavailable("the microphone is busy or unavailable") }
            return
        }
        val effects = mutableListOf<android.media.audiofx.AudioEffect>()
        runCatching { if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)?.let { it.enabled = true; effects += it } }
        runCatching { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.let { it.enabled = true; effects += it } }
        runCatching { if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(rec.audioSessionId)?.let { it.enabled = true; effects += it } }
        val cutter = UtteranceCutter(rate, sensitivity = s.liveGateSensitivity)
        val buf = ByteArray(chunk)
        try {
            rec.startRecording()
            while (token === capToken && !ended && !Thread.currentThread().isInterrupted) {
                var n = 0
                while (n < chunk) {
                    val r = rec.read(buf, n, chunk - n)
                    if (r <= 0) break
                    n += r
                }
                if (n <= 0) { Thread.sleep(20); continue }
                val now = System.currentTimeMillis()
                val talking = speakingNow
                // While he speaks without barge-in (or in the echo tail right after), the microphone is read but not used.
                if (muted || now < ignoreUntil || (talking && !s.hfBargeIn)) { cutter.reset(); continue }
                cutter.echoMode = talking
                val res = cutter.feed(buf, n)
                if (res.opened) {
                    lastHeard = now
                    if (talking) { // barge-in: he stops, the user's words are captured
                        bargedIn = true
                        speakingNow = false
                        Voice.stop()
                        Store.setOrb(OrbState.LISTENING)
                    }
                }
                res.utterance?.let { u -> Store.runOnMain { onUtterance(u) } }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            if (!ended && token === capToken) Store.runOnMain { whisperUnavailable("microphone error: ${e.message}") }
        } finally {
            runCatching { rec.stop() }
            effects.forEach { runCatching { it.release() } }
            rec.release()
        }
    }

    /** A finished utterance (main thread): transcribe it with Groq, then answer it like typed text. */
    private fun onUtterance(u: UtteranceCutter.Utterance) {
        if (ended || muted) return
        val myTurn = ++turn // supersedes whatever was still being answered
        busy = true
        lastHeard = System.currentTimeMillis()
        Voice.stop()
        Store.setOrb(OrbState.THINKING)
        status("transcribing")
        Jarvis.scope.launch {
            val text: String? = try {
                withContext(Dispatchers.IO) { GroqApi.transcribe(s, Wav.encode(u.pcm, 16000), u.pcm.size / 32000.0) }
            } catch (e: BrainException) {
                if (ended || myTurn != turn) return@launch
                sttFailed(e)
                return@launch
            } catch (e: Exception) {
                if (ended || myTurn != turn) return@launch
                sttFailed(BrainException(Failure.OTHER, e.message ?: e.javaClass.simpleName))
                return@launch
            }
            if (ended || myTurn != turn) return@launch
            sttFails = 0
            if (text.isNullOrBlank() || Whisper.isJunk(text, u.speechMs, u.peakDb)) {
                // Silence/noise that Whisper turned into a phantom sentence ("Thank you.", "Дякую за перегляд!"): ignore it.
                busy = false
                Store.setOrb(OrbState.LISTENING)
                status("listening")
                return@launch
            }
            Store.partialSpeech.value = ""
            heard(text, addUser = true)
        }
    }

    private fun sttFailed(e: BrainException) {
        val uk = Store.lastLanguageIsUkrainian()
        busy = false
        if (e.kind == Failure.QUOTA) {
            // Groq's limit: the phone recognizer takes over until Groq says it is free again (listen() checks each turn).
            val line = (groqLimitLine(uk) ?: (if (uk) "Ліміт Groq для слуху вичерпано, сер; перемикаюся на розпізнавання телефона."
                else "Groq's limit for hearing is reached, sir; I'm using the phone's recognizer.")) +
                (if (uk) " Повторіть, будь ласка." else " Please say that again.")
            Store.addMessage("jarvis", line)
            stopCapture()
            speakThenListen(line, ++turn)
            return
        }
        sttFails++
        Store.addMessage("note", "Groq Whisper failed: ${e.kind}: ${e.message?.take(160)}")
        if (e.kind == Failure.BAD_KEY || e.kind == Failure.MODEL || sttFails >= 3) {
            whisperUnavailable("Groq Whisper keeps failing (${e.kind})")
            return
        }
        Store.setOrb(OrbState.LISTENING)
        status("listening")
    }

    /** Whisper cannot be used any more this session: carry on with the phone's recognizer. */
    private fun whisperUnavailable(why: String) {
        if (ended) return
        whisperOff = true
        stopCapture()
        busy = false
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) { end("Groq hearing failed ($why) and the phone has no speech recognition, sir."); return }
        Store.addMessage("note", "Groq hearing is off for this session ($why); using the phone's speech recognizer.")
        listenSoon(0)
    }

    /** One spoken/UI line per newly reached Groq limit (daily or per-minute), with what Jarvis does instead. */
    private fun groqLimitLine(uk: Boolean): String? {
        for (b in GroqLimits.active()) {
            if (!notedLimits.add(b.kind + b.until / 60_000)) continue
            val what = when {
                b.kind == "stt" -> if (uk) "слуху" else "hearing"
                b.kind == "tts" -> if (uk) "голосу" else "voice"
                else -> if (uk) "мислення" else "thinking"
            }
            val instead = when {
                b.kind == "stt" -> if (uk) "користуюся розпізнаванням телефона" else "I'm using the phone's recognizer"
                b.kind == "tts" -> if (uk) "користуюся голосом Gemini або телефона" else "I'm using the Gemini or phone voice"
                else -> if (uk) "користуюся Gemini" else "I'm using Gemini"
            }
            val mins = GroqLimits.minutesLeft(b)
            val line = if (uk) "Сер, ${if (b.daily) "денний " else ""}ліміт Groq для $what вичерпано (приблизно ще $mins хв); $instead."
                else "Sir, Groq's ${if (b.daily) "daily " else ""}limit for $what is reached (about $mins min to go); $instead."
            Store.addMessage("note", "Groq limit (${b.kind}): ${b.why}")
            return line
        }
        return null
    }

    override fun sendUserText(text: String): Boolean {
        if (ended) return false
        Store.runOnMain { if (!ended) heard(text, addUser = false) }
        return true
    }

    override fun onMuteChanged() {
        Store.runOnMain {
            if (ended) return@runOnMain
            if (muted) {
                turn++
                busy = false
                stopCapture()
                destroyRec()
                Voice.stop()
                Store.partialSpeech.value = ""
                Store.setOrb(OrbState.IDLE)
                status("muted")
            } else {
                lastHeard = System.currentTimeMillis()
                listenSoon(0)
            }
        }
    }

    override fun end(message: String?) {
        if (ended) return
        ended = true
        turn++
        Store.runOnMain {
            main.removeCallbacks(watchdog)
            stopCapture()
            destroyRec()
            quietBeep(false)
        }
        Voice.stop()
        Store.partialSpeech.value = ""
        if (message != null) Store.addMessage("jarvis", message)
        Store.addMessage("note", if (Store.lastLanguageIsUkrainian()) "Режим без рук вимкнено." else "Hands-free mode ended.")
        Store.setOrb(OrbState.IDLE)
        LiveSession.status.value = ""
        onEnded()
    }
}
