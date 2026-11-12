package com.dialorsd.jarvis

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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
 */
class HandsFreeEngine(private val ctx: Context, private val announce: String?, private val onEnded: () -> Unit) : Conversation {
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

    private fun status(t: String) { LiveSession.status.value = "hands-free · $t" }

    override fun begin() {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            end("Speech recognition is not available on this phone, sir, so hands-free mode cannot run."); return
        }
        Voice.init(ctx)
        val brain = if (s.handsFreePreferGroq && s.groqKey.isNotBlank()) "Groq, then Gemini" else "Gemini, then Groq"
        Store.addMessage("note", "Hands-free mode on (phone speech recognition; brain: $brain). No Live quota is used. Tap END to stop.")
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
            if (!muted && now - lastHeard > s.liveIdleMinutes * 60_000L && listening) {
                val m = s.liveIdleMinutes
                end(if (Store.lastLanguageIsUkrainian()) "Тиша вже $m хв, сер; вимикаю режим без рук." else "$m minutes of silence, sir; I've ended hands-free mode.")
                return
            }
            if (listening && now - lastCallback > 30_000) listenSoon(0)
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
        lastHeard = System.currentTimeMillis()
        val history = Store.messages.toList()
        if (addUser) Store.addMessage("user", text)
        Store.setOrb(OrbState.THINKING)
        status("thinking")
        Jarvis.scope.launch {
            val uk = hasCyrillic(text)
            val reply = try {
                withContext(Dispatchers.IO) {
                    Brain.chat(s, history, text, chatContext(ctx, s), image = null, preferGroq = s.handsFreePreferGroq,
                        info = { a -> runBlocking { Info.run(ctx, a, uk) } })
                }
            } catch (e: Exception) {
                Reply(Brain.errorLine(e, uk), emptyList())
            }
            if (ended) return@launch
            val (notes, failed) = Jarvis.runActions(ctx, reply.actions, uk)
            var say = reply.say.ifBlank { notes.ifBlank { if (uk) "Так, сер." else "Very good, sir." } }
            if (failed.isNotBlank()) say += " " + failed
            Store.addMessage("jarvis", say)
            Store.addSources(reply.sources)
            if (notes.isNotBlank() && reply.say.isNotBlank()) Store.addMessage("note", notes)
            withContext(Dispatchers.IO) { runCatching { Proactive.scheduleEvents(ctx) } }
            if (myTurn == turn) speakThenListen(say, myTurn)
        }
    }

    private fun speakThenListen(text: String, myTurn: Int) {
        status("speaking")
        Jarvis.scope.launch {
            if (!muted) {
                Voice.speak(ctx, text, allowGemini = geminiVoice)
                if (geminiVoice && Voice.lastGeminiQuota) {
                    geminiVoice = false
                    Store.addMessage("note", "Gemini voice is out of quota; hands-free continues with the phone voice.")
                }
            }
            if (ended || myTurn != turn) return@launch
            lastHeard = System.currentTimeMillis() // the idle time counts from the end of his answer
            listenSoon(250)
        }
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
