package com.dialorsd.jarvis

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

/**
 * Groq over plain HTTPS (OpenAI-compatible): chat, Whisper transcription, Orpheus text to speech.
 * Docs (checked 2026-11-12): console.groq.com/docs/speech-to-text, /text-to-speech/orpheus, /rate-limits, /reasoning.
 * Blocking: call off the main thread. Keys are never logged.
 */

/** Limits Groq told us about (429): kind is "stt", "tts" or "chat:<model>". In memory only. */
object GroqLimits {
    class Block(val kind: String, val until: Long, val daily: Boolean, val why: String)

    private val blocks = ConcurrentHashMap<String, Block>()

    /** Why this kind is blocked right now, or null. */
    fun check(kind: String): String? {
        val b = blocks[kind] ?: return null
        if (System.currentTimeMillis() >= b.until) { blocks.remove(kind); return null }
        return b.why
    }

    fun hit(kind: String, waitMs: Long?, daily: Boolean, why: String) {
        val wait = (waitMs ?: if (daily) 30 * 60_000L else 60_000L).coerceIn(1_000L, 24 * 3_600_000L)
        blocks[kind] = Block(kind, System.currentTimeMillis() + wait, daily, why.take(200))
    }

    fun active(): List<Block> {
        val now = System.currentTimeMillis()
        return blocks.values.filter { it.until > now }.sortedBy { it.kind }
    }

    fun clear() = blocks.clear()

    fun minutesLeft(b: Block): Long = ((b.until - System.currentTimeMillis()) / 60_000L).coerceAtLeast(1)
}

/** Today's Groq counters (UTC day, assumed: Groq's docs do not say when the daily limits reset). */
object GroqUsage {
    data class Day(val day: String, val stt: Int, val sttSec: Double, val chat: Int, val tts: Int, val ttsChars: Int,
                   val err: String, val errAt: Long)

    private fun today() = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()

    @Synchronized private fun load(s: Settings): JSONObject {
        val o = runCatching { JSONObject(s.groqUsage) }.getOrDefault(JSONObject())
        if (o.optString("day") != today()) {
            o.put("day", today()).put("stt", 0).put("sttSec", 0.0).put("chat", 0).put("tts", 0).put("ttsChars", 0)
        }
        return o
    }

    @Synchronized private fun edit(s: Settings, f: (JSONObject) -> Unit) {
        val o = load(s); f(o); s.groqUsage = o.toString()
    }

    fun addStt(s: Settings, seconds: Double) = edit(s) { it.put("stt", it.optInt("stt") + 1).put("sttSec", it.optDouble("sttSec") + seconds) }
    fun addChat(s: Settings) = edit(s) { it.put("chat", it.optInt("chat") + 1) }
    fun addTts(s: Settings, chars: Int) = edit(s) { it.put("tts", it.optInt("tts") + 1).put("ttsChars", it.optInt("ttsChars") + chars) }
    fun error(s: Settings, text: String) = edit(s) { it.put("err", text.take(300)).put("errAt", System.currentTimeMillis()) }

    fun today(s: Settings): Day {
        val o = load(s)
        return Day(o.optString("day"), o.optInt("stt"), o.optDouble("sttSec"), o.optInt("chat"), o.optInt("tts"),
            o.optInt("ttsChars"), o.optString("err"), o.optLong("errAt"))
    }
}

object GroqApi {
    private class Resp(val code: Int, val bytes: ByteArray, val retryAfter: String?) {
        val text: String get() = String(bytes, Charsets.UTF_8)
    }

    private fun rawPost(url: String, key: String, contentType: String, payload: ByteArray, timeoutMs: Int): Resp {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = timeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", contentType)
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setFixedLengthStreamingMode(payload.size)
            conn.outputStream.use { it.write(payload) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            return Resp(code, bytes, conn.getHeaderField("retry-after"))
        } finally {
            conn.disconnect()
        }
    }

    private fun errorMessage(body: String): String? = runCatching {
        JSONObject(body).optJSONObject("error")?.optString("message")
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * POST with Groq's limit rules: a 429 is retried once if retry-after is at most [maxWaitMs] and it is not a daily
     * limit; otherwise the kind is blocked until the time Groq gave (so callers fall back at once) and QUOTA is thrown.
     */
    private fun send(s: Settings, kind: String, url: String, contentType: String, payload: ByteArray, timeoutMs: Int,
                     maxWaitMs: Long): Resp {
        GroqLimits.check(kind)?.let { throw BrainException(Failure.QUOTA, it) }
        var retried = false
        while (true) {
            val r = try {
                rawPost(url, s.groqKey, contentType, payload, timeoutMs)
            } catch (e: UnknownHostException) {
                throw BrainException(Failure.NETWORK, "No internet")
            } catch (e: SocketTimeoutException) {
                throw BrainException(Failure.SERVER, "Groq timed out")
            } catch (e: IOException) {
                throw BrainException(Failure.NETWORK, e.message ?: "network")
            }
            if (r.code in 200..299) return r
            val body = r.text
            val msg = errorMessage(body) ?: "HTTP ${r.code}"
            if (r.code == 429) {
                val waitMs = GroqLimit.retryAfterMs(r.retryAfter, body)
                val daily = GroqLimit.isDaily(body)
                if (!daily && !retried && waitMs != null && waitMs <= maxWaitMs) {
                    retried = true
                    try { Thread.sleep(waitMs + 150) } catch (_: InterruptedException) { throw BrainException(Failure.OTHER, "interrupted") }
                    continue
                }
                GroqLimits.hit(kind, waitMs, daily, msg)
                GroqUsage.error(s, "$kind: ${if (daily) "daily limit" else "rate limit"}: $msg")
                throw BrainException(Failure.QUOTA, (if (daily) "Groq daily limit: " else "Groq rate limit: ") + msg.take(220))
            }
            GroqUsage.error(s, "$kind: HTTP ${r.code}: $msg")
            if (GroqLimit.isTermsError(body)) throw BrainException(Failure.OTHER, "TERMS_REQUIRED: " + msg.take(220))
            val k = when {
                r.code == 401 || r.code == 403 -> Failure.BAD_KEY
                r.code == 404 -> Failure.MODEL
                r.code == 400 && "model" in msg.lowercase() -> Failure.MODEL
                r.code >= 500 -> Failure.SERVER
                else -> Failure.OTHER
            }
            throw BrainException(k, msg)
        }
    }

    // ---------------------------------------------------------------- chat

    /** One chat completion (also the text brain's Groq backup). gpt-oss models get low reasoning effort and no reasoning text. */
    fun chat(s: Settings, model: String, system: String, turns: List<Pair<String, String>>): String {
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        turns.forEach { (r, t) -> msgs.put(JSONObject().put("role", if (r == "model") "assistant" else "user").put("content", t)) }
        val body = JSONObject().put("model", model).put("messages", msgs)
        if ("gpt-oss" in model) body.put("reasoning_effort", "low").put("include_reasoning", false)
        val resp = send(s, "chat:$model", Defaults.GROQ_URL, "application/json; charset=utf-8",
            body.toString().toByteArray(Charsets.UTF_8), 60_000, maxWaitMs = 3_000)
        GroqUsage.addChat(s)
        return JSONObject(resp.text).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
            ?.takeIf { it.isNotBlank() } ?: throw BrainException(Failure.OTHER, "Empty answer from Groq")
    }

    // ---------------------------------------------------------------- speech to text

    /**
     * Whisper transcription of a WAV clip. [seconds] is only for the usage counter. Language is auto-detected.
     * Tries the chosen model, then the turbo model if the first is refused as unknown.
     */
    fun transcribe(s: Settings, wav: ByteArray, seconds: Double): String {
        var last: BrainException? = null
        for (model in listOf(s.groqSttModel, Defaults.GROQ_STT_FALLBACK_MODEL).distinct()) {
            val boundary = "----jarvis" + System.nanoTime().toString(16)
            val body = Multipart.build(boundary,
                listOf("model" to model, "response_format" to "json", "temperature" to "0"),
                "file", "audio.wav", "audio/wav", wav)
            try {
                val r = send(s, "stt", Defaults.GROQ_BASE + "/audio/transcriptions", "multipart/form-data; boundary=$boundary",
                    body, 60_000, maxWaitMs = 8_000)
                GroqUsage.addStt(s, seconds)
                return JSONObject(r.text).optString("text").trim()
            } catch (e: BrainException) {
                last = e
                if (e.kind != Failure.MODEL) throw e
            }
        }
        throw last ?: BrainException(Failure.OTHER, "No Whisper model configured")
    }

    // ---------------------------------------------------------------- text to speech

    /** WAV bytes for one piece of text (under 200 characters). Records the one-time terms requirement when Groq asks. */
    fun tts(s: Settings, text: String, voice: String = s.groqTtsVoice): ByteArray {
        val body = JSONObject().put("model", s.groqTtsModel).put("input", text).put("voice", voice).put("response_format", "wav")
        try {
            val r = send(s, "tts", Defaults.GROQ_BASE + "/audio/speech", "application/json; charset=utf-8",
                body.toString().toByteArray(Charsets.UTF_8), 60_000, maxWaitMs = 6_000)
            GroqUsage.addTts(s, text.length)
            if (s.groqTtsTerms.isNotBlank()) s.groqTtsTerms = ""
            if (r.bytes.size < 64) throw BrainException(Failure.OTHER, "Groq returned no audio")
            return r.bytes
        } catch (e: BrainException) {
            if (e.message?.startsWith("TERMS_REQUIRED") == true) s.groqTtsTerms = e.message!!.removePrefix("TERMS_REQUIRED: ")
            throw e
        }
    }

    /** One line for Settings when the TTS model needs its terms accepted in the Groq console (null = no such problem). */
    fun termsHint(s: Settings): String? = s.groqTtsTerms.takeIf { it.isNotBlank() }?.let {
        "Groq voice needs a one-time OK: open console.groq.com/playground, pick ${s.groqTtsModel}, accept the terms shown " +
            "(as the account owner), then try again. Groq said: $it"
    }

    /** UI note when Groq's voice could not be used for a reply (the next voice in the chain spoke instead). */
    fun ttsIssueNote(s: Settings, why: String): String =
        termsHint(s) ?: "Groq voice not used ($why); the Gemini or phone voice spoke instead."
}

/** Diagnostics helpers: a few seconds of microphone audio, or a generated tone when the microphone is unavailable. */
object GroqDiag {
    /** [seconds] of 16 kHz mono PCM from the microphone, or null if the permission is missing or the microphone is busy. */
    @android.annotation.SuppressLint("MissingPermission")
    fun record(ctx: android.content.Context, seconds: Int): ByteArray? {
        if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) return null
        val rate = 16000
        val total = rate * 2 * seconds
        val min = android.media.AudioRecord.getMinBufferSize(rate, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            android.media.AudioRecord(android.media.MediaRecorder.AudioSource.MIC, rate, android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8192))
        } catch (e: Exception) { return null }
        if (rec.state != android.media.AudioRecord.STATE_INITIALIZED) { rec.release(); return null }
        val out = ByteArray(total)
        var n = 0
        try {
            rec.startRecording()
            while (n < total) {
                val r = rec.read(out, n, minOf(4096, total - n))
                if (r <= 0) break
                n += r
            }
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
        return if (n >= rate) out.copyOf(n - n % 2) else null
    }

    /** A 440 Hz tone at about -20 dBFS: enough to prove the endpoint, key and model respond. */
    fun tone(seconds: Double, rate: Int = 16000): ByteArray {
        val n = (seconds * rate).toInt()
        val b = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = (3277 * Math.sin(2 * Math.PI * 440.0 * i / rate)).toInt()
            b[2 * i] = (v and 0xFF).toByte(); b[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return b
    }
}
