package com.dialorsd.jarvis

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Speaking: Gemini TTS (PCM through AudioTrack) with Android TextToSpeech as the fallback.
 * Ukrainian (Cyrillic) text goes to the phone voice, because Ukrainian is not listed as a supported
 * Gemini TTS language.
 */
object Voice {
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    @Volatile private var track: AudioTrack? = null
    @Volatile private var stopped = false

    fun init(context: Context) {
        if (tts != null) return
        val app = context.applicationContext
        tts = TextToSpeech(app) { status -> ttsReady = status == TextToSpeech.SUCCESS }
    }

    fun stop() {
        stopped = true
        runCatching { track?.pause(); track?.flush(); track?.stop() }
        runCatching { tts?.stop() }
    }

    /** Speaks [text] and suspends until finished (or failed). Never throws. */
    suspend fun speak(context: Context, text: String) {
        val clean = text.replace(Regex("[*_#`]"), "").trim()
        if (clean.isEmpty()) return
        val s = Settings(context)
        if (s.voiceMode == VoiceMode.MUTED) return
        stopped = false
        Store.setOrb(OrbState.SPEAKING)
        try {
            var done = false
            if (s.voiceMode == VoiceMode.GEMINI && !hasCyrillic(clean) && s.geminiKey.isNotBlank()) {
                done = runCatching {
                    val (pcm, rate) = withContext(Dispatchers.IO) { synth(s, s.ttsVoice, s.ttsStyle, clean) }
                    withContext(Dispatchers.IO) { playPcm(pcm, rate) }
                }.getOrDefault(false)
            }
            if (!done && !stopped) phoneSpeak(context, clean)
        } finally {
            Store.setOrb(OrbState.IDLE)
        }
    }

    /**
     * Settings "Preview": speaks a sample with the given Gemini voice. Returns null on success or a
     * description of why Gemini TTS failed (it then does NOT fall back, so the user can tell).
     */
    suspend fun preview(context: Context, voice: String, style: String): String? {
        val s = Settings(context)
        if (s.geminiKey.isBlank()) return "No Gemini API key saved."
        stop()
        stopped = false
        Store.setOrb(OrbState.SPEAKING)
        return try {
            val (pcm, rate) = withContext(Dispatchers.IO) {
                synth(s, voice, style, "Good evening, sir. Everything is in order, and the kettle is, I assure you, already on.")
            }
            if (withContext(Dispatchers.IO) { playPcm(pcm, rate) }) null else "Audio playback failed on this phone."
        } catch (e: Exception) {
            e.message ?: e.toString()
        } finally {
            Store.setOrb(OrbState.IDLE)
        }
    }

    // ---------- Gemini TTS ----------

    /**
     * Returns (16-bit mono PCM, sample rate). Tries, in order: the Interactions API (current docs,
     * style via speech_metadata), then legacy generateContent with the same model, then legacy
     * generateContent with the fallback TTS model. Throws with all error messages if all fail.
     */
    private fun synth(s: Settings, voice: String, style: String, text: String): Pair<ByteArray, Int> {
        val errors = mutableListOf<String>()
        val attempts = listOf<Pair<String, () -> Pair<ByteArray, Int>>>(
            "interactions ${s.ttsModel}" to { interactionsTts(s, s.ttsModel, voice, style, text) },
            "generateContent ${s.ttsModel}" to { legacyTts(s, s.ttsModel, voice, style, text) },
            "generateContent ${Defaults.GEMINI_TTS_FALLBACK_MODEL}" to { legacyTts(s, Defaults.GEMINI_TTS_FALLBACK_MODEL, voice, style, text) },
        ).distinctBy { it.first }
        for ((name, call) in attempts) {
            if (stopped) break
            try {
                return call()
            } catch (e: BrainException) {
                errors += "$name: ${e.kind} ${e.message}"
                if (e.kind == Failure.BAD_KEY || e.kind == Failure.NETWORK) break
            } catch (e: Exception) {
                errors += "$name: ${e.message}"
            }
        }
        throw IllegalStateException("Gemini voice failed. " + errors.joinToString(" | ").take(600))
    }

    private fun interactionsTts(s: Settings, model: String, voice: String, style: String, text: String): Pair<ByteArray, Int> {
        val content = JSONObject().put("type", "text").put("text", text)
        if (style.isNotBlank()) content.put("annotations", JSONArray().put(JSONObject().put("type", "speech_metadata").put("style", style)))
        val body = JSONObject()
            .put("model", model)
            .put("input", JSONArray().put(JSONObject().put("type", "user_input").put("content", JSONArray().put(content))))
            .put("response_format", JSONObject().put("type", "audio"))
            .put("generation_config", JSONObject().put("speech_config", JSONArray().put(JSONObject().put("voice", voice))))
        val resp = Brain.post(Defaults.GEMINI_INTERACTIONS, body.toString(), mapOf("x-goog-api-key" to s.geminiKey), readTimeoutMs = 60_000)
        // Find the last {"type":"audio","data":"..."} anywhere in the response (steps[].content[]).
        var found: JSONObject? = null
        fun walk(v: Any?) {
            when (v) {
                is JSONObject -> {
                    if (v.optString("type") == "audio" && v.optString("data").isNotEmpty()) found = v
                    v.keys().forEach { walk(v.opt(it)) }
                }
                is JSONArray -> for (i in 0 until v.length()) walk(v.opt(i))
            }
        }
        walk(JSONObject(resp))
        val a = found ?: throw IllegalStateException("no audio in response")
        return decode(a.optString("data"), a.optString("mime_type", a.optString("mimeType")))
    }

    private fun legacyTts(s: Settings, model: String, voice: String, style: String, text: String): Pair<ByteArray, Int> {
        val prompt = if (style.isNotBlank()) "Say in a $style voice: $text" else text
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject().put("voiceConfig", JSONObject()
                    .put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice)))))
        val resp = Brain.post(Defaults.GEMINI_BASE + model + ":generateContent", body.toString(),
            mapOf("x-goog-api-key" to s.geminiKey), readTimeoutMs = 60_000)
        val parts = JSONObject(resp).optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts") ?: throw IllegalStateException("no audio in response")
        for (i in 0 until parts.length()) {
            val d = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
            return decode(d.optString("data"), d.optString("mimeType"))
        }
        throw IllegalStateException("no audio in response")
    }

    /** Base64 audio (WAV or raw 16-bit PCM) -> (PCM, rate). */
    private fun decode(b64: String, mime: String): Pair<ByteArray, Int> {
        if (b64.isEmpty()) throw IllegalStateException("empty audio")
        var pcm = Base64.decode(b64, Base64.DEFAULT)
        var rate = Regex("rate=(\\d+)").find(mime)?.groupValues?.get(1)?.toIntOrNull() ?: 24000
        if (pcm.size > 44 && String(pcm, 0, 4, Charsets.US_ASCII) == "RIFF") {
            rate = le32(pcm, 24)
            var off = 12
            var dataStart = 44
            var dataLen = pcm.size - 44
            while (off + 8 <= pcm.size) {
                val id = String(pcm, off, 4, Charsets.US_ASCII)
                val len = le32(pcm, off + 4)
                if (id == "data") { dataStart = off + 8; dataLen = minOf(len, pcm.size - dataStart); break }
                if (len < 0) break
                off += 8 + len
            }
            pcm = pcm.copyOfRange(dataStart, dataStart + dataLen)
            if (rate !in 8000..48000) rate = 24000
        }
        if (pcm.size < 2) throw IllegalStateException("empty audio")
        return pcm to rate
    }

    private fun le32(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or
            ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)

    private suspend fun playPcm(pcm: ByteArray, rate: Int): Boolean {
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return false
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(minBuf * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (t.state != AudioTrack.STATE_INITIALIZED) { t.release(); return false }
        track = t
        try {
            t.play()
            var off = 0
            val even = pcm.size - (pcm.size % 2)
            while (off < even && !stopped) {
                val n = t.write(pcm, off, minOf(8192, even - off))
                if (n <= 0) break
                off += n
            }
            val frames = even / 2
            val maxWait = frames * 1000L / rate + 2000
            var waited = 0L
            while (!stopped && t.playbackHeadPosition < frames && waited < maxWait) {
                delay(50); waited += 50
            }
            return true
        } catch (e: Exception) {
            return false
        } finally {
            track = null
            runCatching { t.stop() }
            t.release()
        }
    }

    // ---------- Android TextToSpeech ----------

    private suspend fun phoneSpeak(context: Context, text: String) {
        init(context)
        // Wait briefly for the engine to initialise.
        withTimeoutOrNull(4000) { while (!ttsReady) delay(100) }
        val engine = tts ?: return
        if (!ttsReady) return
        val locale = if (hasCyrillic(text)) Locale.forLanguageTag("uk-UA") else Locale.UK
        runCatching { engine.language = locale }
        val id = "u" + System.nanoTime()
        withTimeoutOrNull(120_000) {
            suspendCancellableCoroutine { cont ->
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(Unit) }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(Unit) }
                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        if (utteranceId == id && cont.isActive) cont.resume(Unit)
                    }
                })
                val r = engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
                if (r != TextToSpeech.SUCCESS && cont.isActive) cont.resume(Unit)
                cont.invokeOnCancellation { runCatching { engine.stop() } }
            }
        }
    }
}
