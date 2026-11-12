package com.dialorsd.jarvis

import java.io.ByteArrayOutputStream

/*
 * Pure Kotlin (no Android imports) so it is unit-tested on the JVM (app/src/test/.../GroqLogicTest.kt).
 * Groq facts these helpers rely on (docs checked 2026-11-12):
 *  - speech to text: https://console.groq.com/docs/speech-to-text (multipart upload, wav accepted, 25 MB free-tier limit)
 *  - text to speech: https://console.groq.com/docs/text-to-speech/orpheus (input under 200 characters, wav only)
 *  - rate limits and the retry-after header: https://console.groq.com/docs/rate-limits
 */

/** 16-bit PCM <-> WAV (RIFF) container. */
object Wav {
    class Audio(val pcm: ByteArray, val rate: Int)

    /** The 44-byte header of a canonical PCM WAV file. */
    fun header(dataLen: Int, rate: Int, channels: Int = 1): ByteArray {
        val h = ByteArray(44)
        fun str(o: Int, s: String) { for (i in s.indices) h[o + i] = s[i].code.toByte() }
        fun le32(o: Int, v: Int) { for (i in 0..3) h[o + i] = ((v ushr (8 * i)) and 0xFF).toByte() }
        fun le16(o: Int, v: Int) { h[o] = (v and 0xFF).toByte(); h[o + 1] = ((v ushr 8) and 0xFF).toByte() }
        str(0, "RIFF"); le32(4, 36 + dataLen); str(8, "WAVE")
        str(12, "fmt "); le32(16, 16); le16(20, 1); le16(22, channels); le32(24, rate)
        le32(28, rate * channels * 2); le16(32, channels * 2); le16(34, 16)
        str(36, "data"); le32(40, dataLen)
        return h
    }

    /** A complete WAV file from 16-bit little-endian mono PCM. */
    fun encode(pcm: ByteArray, rate: Int = 16000): ByteArray {
        val even = pcm.size - (pcm.size % 2)
        val out = ByteArray(44 + even)
        System.arraycopy(header(even, rate), 0, out, 0, 44)
        System.arraycopy(pcm, 0, out, 44, even)
        return out
    }

    private fun u16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, i: Int): Long =
        (u16(b, i).toLong()) or (u16(b, i + 2).toLong() shl 16)

    /**
     * Mono 16-bit PCM from a WAV file, or null if it is not 16-bit PCM. Stereo is averaged to mono.
     * Tolerates streaming headers whose data length is 0 / 0xFFFFFFFF / too large (uses what is there).
     */
    fun parse(w: ByteArray): Audio? {
        if (w.size < 44) return null
        if (String(w, 0, 4, Charsets.US_ASCII) != "RIFF" || String(w, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        var off = 12
        var channels = 0; var rate = 0; var bits = 0; var tag = 0
        while (off + 8 <= w.size) {
            val id = String(w, off, 4, Charsets.US_ASCII)
            val len = u32(w, off + 4)
            val body = off + 8
            if (id == "fmt " && body + 16 <= w.size) {
                tag = u16(w, body); channels = u16(w, body + 2); rate = u32(w, body + 4).toInt(); bits = u16(w, body + 14)
            } else if (id == "data") {
                if (channels < 1 || rate !in 4000..192000 || bits != 16 || (tag != 1 && tag != 0xFFFE)) return null
                val avail = (w.size - body).toLong()
                var n = if (len == 0L || len > avail) avail else len
                val frame = 2 * channels
                n -= n % frame
                if (n <= 0) return null
                val data = w.copyOfRange(body, body + n.toInt())
                if (channels == 1) return Audio(data, rate)
                val frames = n.toInt() / frame
                val mono = ByteArray(frames * 2)
                for (f in 0 until frames) {
                    var sum = 0
                    for (c in 0 until channels) {
                        val p = f * frame + c * 2
                        sum += ((data[p].toInt() and 0xFF) or (data[p + 1].toInt() shl 8)).toShort().toInt()
                    }
                    val v = sum / channels
                    mono[2 * f] = (v and 0xFF).toByte(); mono[2 * f + 1] = ((v shr 8) and 0xFF).toByte()
                }
                return Audio(mono, rate)
            }
            if (len > Int.MAX_VALUE) return null
            off = body + len.toInt() + (len.toInt() and 1)
        }
        return null
    }
}

/** multipart/form-data body (what the OpenAI-compatible /audio/transcriptions endpoint takes). */
object Multipart {
    fun build(boundary: String, fields: List<Pair<String, String>>, fileField: String, fileName: String,
              fileType: String, file: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(file.size + 1024)
        fun w(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
        for ((k, v) in fields) {
            w("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n")
        }
        w("--$boundary\r\nContent-Disposition: form-data; name=\"$fileField\"; filename=\"$fileName\"\r\nContent-Type: $fileType\r\n\r\n")
        out.write(file)
        w("\r\n--$boundary--\r\n")
        return out.toByteArray()
    }
}

/** Groq 429 handling: retry-after header, "try again in 7m12.5s" bodies, daily-versus-per-minute. */
object GroqLimit {
    private val PART = Regex("""(\d+(?:\.\d+)?)(ms|h|m|s)""")
    private val AGAIN = Regex("""try again in\s+([0-9hms.]+)""", RegexOption.IGNORE_CASE)

    /** "7m12.5s", "2s", "1h2m3s", "350ms" or a bare number of seconds ("2", "1.5") -> milliseconds. */
    fun parseDurationMs(text: String): Long? {
        val t = text.trim().trimEnd('.').lowercase()
        if (t.isEmpty()) return null
        t.toDoubleOrNull()?.let { return if (it < 0) null else Math.round(it * 1000.0) }
        var total = 0.0
        var matched = 0
        var consumed = 0
        for (m in PART.findAll(t)) {
            val v = m.groupValues[1].toDouble()
            total += when (m.groupValues[2]) { "ms" -> v; "s" -> v * 1000; "m" -> v * 60_000; else -> v * 3_600_000 }
            matched++; consumed += m.value.length
        }
        return if (matched > 0 && consumed == t.length) Math.round(total) else null
    }

    /** The retry-after header (seconds) if present, else the "try again in ..." sentence of the error body. */
    fun retryAfterMs(header: String?, body: String?): Long? {
        header?.let { h -> parseDurationMs(h)?.let { return it } }
        val m = body?.let { AGAIN.find(it) } ?: return null
        return parseDurationMs(m.groupValues[1])
    }

    /** True when the limit that was hit is a daily one (requests, tokens or audio seconds per day). */
    fun isDaily(body: String?): Boolean {
        val b = body?.lowercase() ?: return false
        return "per day" in b || "(rpd)" in b || "(tpd)" in b || "(asd)" in b
    }

    /** Groq asks for a one-time acceptance of a model's terms (console playground) before TTS works. */
    fun isTermsError(body: String?): Boolean {
        val b = body?.lowercase() ?: return false
        return "model_terms_required" in b || ("terms" in b && "accept" in b)
    }
}

/** Whisper's well-known phantom transcripts of silence and noise. */
object Whisper {
    /** Phrases that are phantom whatever the loudness (video outros, subtitle credits). */
    private val STRONG = listOf(
        "thanks for watching", "thank you for watching", "subtitles by", "subtitled by", "amara org",
        "like and subscribe", "please subscribe", "дякую за перегляд", "дякуємо за перегляд", "підписуйтесь на канал",
        "підписуйтеся на канал", "продовження слідує", "субтитри", "субтитры", "редактор субтитров", "спасибо за просмотр",
        "i ll see you in the next video", "see you in the next video",
    )
    /** Short phrases that are phantom only when the sound was very short or quiet. */
    private val WEAK = setOf(
        "thank you", "thank you very much", "thanks", "you", "bye", "bye bye", "goodbye", "the end", "so", "okay", "ok",
        "дякую", "дякую вам", "дякуємо", "до побачення", "до зустрічі", "всім до побачення", "добре", "ага", "угу", "спасибо",
    )

    /** Lower case, letters and digits only, single spaces. */
    fun normalize(t: String): String {
        val sb = StringBuilder()
        var space = true
        for (c in t.lowercase()) {
            if (c.isLetterOrDigit()) { sb.append(c); space = false }
            else if (!space) { sb.append(' '); space = true }
        }
        return sb.toString().trim()
    }

    /**
     * [speechMs]: time the voice gate judged to be speech in the clip; [peakDb]: loudest speech frame (dBFS).
     * Empty or one-letter transcripts and a word repeated over and over are always junk.
     */
    fun isJunk(text: String, speechMs: Int, peakDb: Double): Boolean {
        val n = normalize(text)
        if (n.count { it.isLetterOrDigit() } < 2) return true
        if (STRONG.any { it in n }) return true
        val words = n.split(' ')
        if (words.size >= 5 && words.toSet().size == 1) return true
        val tiny = speechMs < 900 || peakDb < -42.0
        return tiny && n in WEAK
    }
}

/** Cuts single utterances out of a continuous 16 kHz mono PCM stream with [VoiceGate]. */
class UtteranceCutter(rate: Int = 16000, sensitivity: Float = 0.5f, private val maxMs: Int = 30_000,
                      private val minSpeechMs: Int = 300) {
    class Utterance(val pcm: ByteArray, val speechMs: Int, val peakDb: Double, val forced: Boolean)
    /** [opened]: the gate just opened; [utterance]: a finished one; [tooShort]: a noise burst was dropped. */
    class Result(val opened: Boolean, val utterance: Utterance?, val tooShort: Boolean)

    private val gate = VoiceGate(rate, sensitivity = sensitivity)
    private val sampleRate = rate
    private var buf = ByteArrayOutputStream()
    private var speechMs = 0.0
    private var peak = -120.0

    var echoMode: Boolean
        get() = gate.echoMode
        set(v) { gate.echoMode = v }
    val isOpen: Boolean get() = gate.isOpen
    val noiseFloorDb: Double get() = gate.noiseFloorDb

    fun reset() {
        gate.reset()
        buf = ByteArrayOutputStream(); speechMs = 0.0; peak = -120.0
    }

    fun feed(pcm: ByteArray, len: Int = pcm.size): Result {
        val out = gate.process(pcm, len)
        var opened = false
        if (out.event == VoiceGate.Event.OPENED) {
            opened = true
            buf = ByteArrayOutputStream(); speechMs = 0.0; peak = -120.0
        }
        val send = out.send ?: return Result(false, null, false)
        buf.write(send)
        if (out.speech) {
            speechMs += ((len - len % 2) / 2) * 1000.0 / sampleRate.coerceAtLeast(1)
            if (out.db > peak) peak = out.db
        }
        val ms = buf.size() / 2 * 1000 / sampleRate
        val closed = out.event == VoiceGate.Event.CLOSED
        if (closed || ms >= maxMs) {
            val u = Utterance(buf.toByteArray(), speechMs.toInt(), peak, forced = !closed)
            buf = ByteArrayOutputStream(); speechMs = 0.0; peak = -120.0
            if (!closed) gate.reset()
            return if (u.speechMs < minSpeechMs) Result(opened, null, true) else Result(opened, u, false)
        }
        return Result(opened, null, false)
    }
}

/** Text for Groq TTS: markdown removed and cut into requests under the 200-character limit. */
object TtsText {
    const val MAX_CHARS = 190

    fun clean(text: String): String =
        text.replace(Regex("[*_#`]"), "").replace(Regex("""https?://\S+"""), "").replace(Regex("""\s+"""), " ").trim()

    /** Sentence-wise pieces of at most [max] characters (long sentences are cut at a comma or space). */
    fun split(text: String, max: Int = MAX_CHARS): List<String> {
        val t = clean(text)
        if (t.isEmpty()) return emptyList()
        val sentences = Regex("""[^.!?…]+[.!?…]*""").findAll(t).map { it.value.trim() }.filter { it.isNotEmpty() }.toList()
        val out = mutableListOf<String>()
        var cur = StringBuilder()
        fun flush() { if (cur.isNotEmpty()) { out += cur.toString().trim(); cur = StringBuilder() } }
        for (s0 in sentences) {
            var s = s0
            while (s.length > max) {
                var cut = s.lastIndexOf(',', max - 1)
                if (cut < max / 3) cut = s.lastIndexOf(' ', max - 1)
                if (cut < max / 3) cut = max - 1
                flush()
                out += s.substring(0, cut + 1).trim()
                s = s.substring(cut + 1).trim()
            }
            if (s.isEmpty()) continue
            if (cur.isNotEmpty() && cur.length + 1 + s.length > max) flush()
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(s)
        }
        flush()
        return out.filter { it.isNotBlank() }
    }
}
