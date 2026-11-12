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
                done = runCatching { withContext(Dispatchers.IO) { geminiSpeak(s, clean) } }.getOrDefault(false)
            }
            if (!done && !stopped) phoneSpeak(context, clean)
        } finally {
            Store.setOrb(OrbState.IDLE)
        }
    }

    // ---------- Gemini TTS ----------

    private suspend fun geminiSpeak(s: Settings, text: String): Boolean {
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject()
                .put("text", "Say in a calm, measured, dry British butler voice: $text")))))
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject().put("voiceConfig", JSONObject()
                    .put("prebuiltVoiceConfig", JSONObject().put("voiceName", s.ttsVoice)))))
        val resp = Brain.post(Defaults.GEMINI_BASE + s.ttsModel + ":generateContent", body.toString(),
            mapOf("x-goog-api-key" to s.geminiKey), readTimeoutMs = 45_000)
        val parts = JSONObject(resp).optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts") ?: return false
        var b64: String? = null
        var mime = ""
        for (i in 0 until parts.length()) {
            val d = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
            b64 = d.optString("data"); mime = d.optString("mimeType"); break
        }
        if (b64.isNullOrEmpty() || stopped) return false
        var pcm = Base64.decode(b64, Base64.DEFAULT)
        var rate = Regex("rate=(\\d+)").find(mime)?.groupValues?.get(1)?.toIntOrNull() ?: 24000
        // Some responses are WAV (RIFF header) instead of raw 16-bit PCM.
        if (pcm.size > 44 && String(pcm, 0, 4, Charsets.US_ASCII) == "RIFF") {
            rate = (pcm[24].toInt() and 0xFF) or ((pcm[25].toInt() and 0xFF) shl 8) or
                ((pcm[26].toInt() and 0xFF) shl 16) or ((pcm[27].toInt() and 0xFF) shl 24)
            var off = 12
            var dataStart = 44
            var dataLen = pcm.size - 44
            while (off + 8 <= pcm.size) {
                val id = String(pcm, off, 4, Charsets.US_ASCII)
                val len = (pcm[off + 4].toInt() and 0xFF) or ((pcm[off + 5].toInt() and 0xFF) shl 8) or
                    ((pcm[off + 6].toInt() and 0xFF) shl 16) or ((pcm[off + 7].toInt() and 0xFF) shl 24)
                if (id == "data") { dataStart = off + 8; dataLen = minOf(len, pcm.size - dataStart); break }
                off += 8 + len
            }
            pcm = pcm.copyOfRange(dataStart, dataStart + dataLen)
            if (rate !in 8000..48000) rate = 24000
        }
        if (pcm.size < 2) return false
        return playPcm(pcm, rate)
    }

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
