package com.dialorsd.jarvis

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Pure logic of Groq voice mode (GroqLogic.kt): WAV, multipart, limits, phantom transcripts, utterance cutting, TTS text. */
class GroqLogicTest {
    private val rate = 16000
    private val chunk = 640 // 40 ms

    // ---------------------------------------------------------------- WAV

    @Test fun wavHeaderIsCanonical() {
        val h = Wav.header(32000, 16000)
        assertEquals(44, h.size)
        assertEquals("RIFF", String(h, 0, 4))
        assertEquals("WAVE", String(h, 8, 4))
        assertEquals("fmt ", String(h, 12, 4))
        assertEquals("data", String(h, 36, 4))
        fun le32(i: Int) = (h[i].toInt() and 0xFF) or ((h[i + 1].toInt() and 0xFF) shl 8) or ((h[i + 2].toInt() and 0xFF) shl 16) or ((h[i + 3].toInt() and 0xFF) shl 24)
        fun le16(i: Int) = (h[i].toInt() and 0xFF) or ((h[i + 1].toInt() and 0xFF) shl 8)
        assertEquals(36 + 32000, le32(4))
        assertEquals(16, le32(16))
        assertEquals(1, le16(20))      // PCM
        assertEquals(1, le16(22))      // mono
        assertEquals(16000, le32(24))
        assertEquals(32000, le32(28))  // byte rate
        assertEquals(2, le16(32))      // block align
        assertEquals(16, le16(34))
        assertEquals(32000, le32(40))
    }

    @Test fun encodeThenParseRoundTrips() {
        val pcm = ByteArray(1000) { (it * 7).toByte() }
        val w = Wav.encode(pcm, 16000)
        assertEquals(1044, w.size)
        val a = Wav.parse(w)
        assertNotNull(a)
        assertEquals(16000, a!!.rate)
        assertArrayEquals(pcm, a.pcm)
    }

    @Test fun encodeDropsAHalfSample() {
        assertEquals(44 + 10, Wav.encode(ByteArray(11)).size)
    }

    @Test fun parseToleratesStreamingHeaderLengths() {
        val pcm = ByteArray(200) { it.toByte() }
        for (bogus in listOf(0, -1, 1_000_000)) {
            val w = Wav.encode(pcm, 24000)
            for (i in 0..3) w[40 + i] = ((bogus ushr (8 * i)) and 0xFF).toByte()
            val a = Wav.parse(w)
            assertNotNull("data length $bogus", a)
            assertEquals(24000, a!!.rate)
            assertEquals(200, a.pcm.size)
        }
    }

    @Test fun parseSkipsExtraChunksAndMixesStereoDown() {
        // fmt (stereo 22050) + a LIST chunk with odd length + data of 2 frames
        val frames = byteArrayOf(0x10, 0x00, 0x30, 0x00,  0x00, 0x01, 0x00, 0x03) // (16,48) and (256,768)
        val out = java.io.ByteArrayOutputStream()
        fun le32(v: Int) { for (i in 0..3) out.write((v ushr (8 * i)) and 0xFF) }
        fun le16(v: Int) { out.write(v and 0xFF); out.write((v ushr 8) and 0xFF) }
        out.write("RIFF".toByteArray()); le32(0); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); le32(16); le16(1); le16(2); le32(22050); le32(22050 * 4); le16(4); le16(16)
        out.write("LIST".toByteArray()); le32(3); out.write(byteArrayOf(1, 2, 3)); out.write(0) // odd length + pad byte
        out.write("data".toByteArray()); le32(frames.size); out.write(frames)
        val a = Wav.parse(out.toByteArray())
        assertNotNull(a)
        assertEquals(22050, a!!.rate)
        assertEquals(4, a.pcm.size)
        fun s(i: Int) = ((a.pcm[i].toInt() and 0xFF) or (a.pcm[i + 1].toInt() shl 8)).toShort().toInt()
        assertEquals(32, s(0))
        assertEquals(512, s(2))
    }

    @Test fun parseRejectsWhatItCannotPlay() {
        assertNull(Wav.parse(ByteArray(10)))
        assertNull(Wav.parse(ByteArray(100)))
        val w8 = Wav.encode(ByteArray(100), 16000)
        w8[34] = 8 // 8 bits per sample
        assertNull(Wav.parse(w8))
        val mp3ish = "ID3".toByteArray() + ByteArray(100)
        assertNull(Wav.parse(mp3ish))
    }

    // ---------------------------------------------------------------- multipart

    @Test fun multipartBodyHasFieldsAndTheFileBytes() {
        val file = byteArrayOf(1, 2, 3, 4, 5)
        val body = Multipart.build("BOUND", listOf("model" to "whisper-large-v3", "temperature" to "0"),
            "file", "audio.wav", "audio/wav", file)
        val text = String(body, Charsets.ISO_8859_1)
        assertTrue(text.startsWith("--BOUND\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\nwhisper-large-v3\r\n"))
        assertTrue(text.contains("name=\"temperature\"\r\n\r\n0\r\n"))
        assertTrue(text.contains("name=\"file\"; filename=\"audio.wav\"\r\nContent-Type: audio/wav\r\n\r\n"))
        assertTrue(text.endsWith("\r\n--BOUND--\r\n"))
        val start = text.indexOf("Content-Type: audio/wav\r\n\r\n") + "Content-Type: audio/wav\r\n\r\n".length
        assertArrayEquals(file, body.copyOfRange(start, start + file.size))
    }

    // ---------------------------------------------------------------- 429 handling

    @Test fun durationsParse() {
        assertEquals(432_500L, GroqLimit.parseDurationMs("7m12.5s"))
        assertEquals(2000L, GroqLimit.parseDurationMs("2"))
        assertEquals(1500L, GroqLimit.parseDurationMs("1.5"))
        assertEquals(350L, GroqLimit.parseDurationMs("350ms"))
        assertEquals(3_723_000L, GroqLimit.parseDurationMs("1h2m3s"))
        assertEquals(2000L, GroqLimit.parseDurationMs("2s."))
        assertEquals(59_560L, GroqLimit.parseDurationMs("59.56s"))
        assertNull(GroqLimit.parseDurationMs(""))
        assertNull(GroqLimit.parseDurationMs("soon"))
        assertNull(GroqLimit.parseDurationMs("5x"))
    }

    @Test fun retryAfterPrefersTheHeaderThenTheBody() {
        assertEquals(2000L, GroqLimit.retryAfterMs("2", "Please try again in 7m12.5s."))
        assertEquals(432_500L, GroqLimit.retryAfterMs(null, "Rate limit reached for model `x` ... Please try again in 7m12.5s. Need more tokens?"))
        assertEquals(1500L, GroqLimit.retryAfterMs("  ", "Rate limit ... try again in 1.5s"))
        assertNull(GroqLimit.retryAfterMs(null, "Rate limit reached"))
        assertNull(GroqLimit.retryAfterMs(null, null))
    }

    @Test fun dailyLimitsAreRecognised() {
        assertTrue(GroqLimit.isDaily("Rate limit reached for model `whisper-large-v3` on seconds of audio per day (ASD): Limit 28800"))
        assertTrue(GroqLimit.isDaily("... on requests per day (RPD): Limit 1000, Used 1000"))
        assertTrue(GroqLimit.isDaily("tokens per day (TPD)"))
        assertFalse(GroqLimit.isDaily("Rate limit reached on requests per minute (RPM): Limit 20"))
        assertFalse(GroqLimit.isDaily(null))
    }

    @Test fun termsErrorIsRecognised() {
        assertTrue(GroqLimit.isTermsError("""{"error":{"message":"The model requires terms acceptance. Please accept at the playground","code":"model_terms_required"}}"""))
        assertTrue(GroqLimit.isTermsError("you must accept the terms of this model"))
        assertFalse(GroqLimit.isTermsError("""{"error":{"message":"Invalid API Key","code":"invalid_api_key"}}"""))
        assertFalse(GroqLimit.isTermsError(null))
    }

    // ---------------------------------------------------------------- phantom transcripts

    @Test fun silenceHallucinationsAreDroppedWhenShortOrQuiet() {
        assertTrue(Whisper.isJunk("Thank you.", 400, -30.0))      // very short
        assertTrue(Whisper.isJunk("Thank you.", 2000, -50.0))     // quiet
        assertTrue(Whisper.isJunk("Дякую.", 500, -25.0))
        assertTrue(Whisper.isJunk("  you  ", 300, -30.0))
        assertFalse("a real, loud 'thank you'", Whisper.isJunk("Thank you.", 1500, -25.0))
    }

    @Test fun videoOutrosAreAlwaysDropped() {
        assertTrue(Whisper.isJunk("Дякую за перегляд!", 3000, -20.0))
        assertTrue(Whisper.isJunk("Thanks for watching!", 3000, -20.0))
        assertTrue(Whisper.isJunk("Subtitles by the Amara.org community", 3000, -20.0))
        assertTrue(Whisper.isJunk("Продовження слідує...", 3000, -20.0))
    }

    @Test fun emptyAndDegenerateTranscriptsAreDropped() {
        assertTrue(Whisper.isJunk("", 3000, -20.0))
        assertTrue(Whisper.isJunk("...", 3000, -20.0))
        assertTrue(Whisper.isJunk("a", 3000, -20.0))
        assertTrue(Whisper.isJunk("you you you you you you", 3000, -20.0))
    }

    @Test fun realSpeechIsKept() {
        assertFalse(Whisper.isJunk("Set a timer for ten minutes", 1800, -24.0))
        assertFalse(Whisper.isJunk("Постав таймер на десять хвилин", 1800, -24.0))
        assertFalse(Whisper.isJunk("What is the weather like today?", 600, -45.0))
        assertFalse(Whisper.isJunk("Thank you Jarvis, now start the research task", 700, -50.0))
    }

    // ---------------------------------------------------------------- utterance cutting

    private fun noise(seconds: Double, dbfs: Double): DoubleArray {
        val rnd = Random(7)
        val a = 32768.0 * 10.0.pow(dbfs / 20.0) * sqrt(3.0)
        return DoubleArray((seconds * rate).toInt()) { (rnd.nextDouble() * 2 - 1) * a }
    }

    private fun voice(seconds: Double, dbfs: Double, syllables: Boolean = true): DoubleArray {
        val n = (seconds * rate).toInt()
        val h = 5
        val a = 32768.0 * 10.0.pow(dbfs / 20.0) / sqrt(h / 2.0)
        return DoubleArray(n) { i ->
            val t = i.toDouble() / rate
            var v = 0.0
            for (k in 1..h) v += sin(2 * PI * 140.0 * k * t) / k * 1.6
            val env = if (syllables) 0.15 + 0.85 * 0.5 * (1 - cos(2 * PI * 4.0 * t)) else 1.0
            v * a * env
        }
    }

    private fun silence(seconds: Double) = DoubleArray((seconds * rate).toInt())

    private fun concat(vararg parts: DoubleArray): DoubleArray {
        val out = DoubleArray(parts.sumOf { it.size }); var o = 0
        for (p in parts) { System.arraycopy(p, 0, out, o, p.size); o += p.size }
        return out
    }

    private fun mix(a: DoubleArray, b: DoubleArray): DoubleArray {
        val n = maxOf(a.size, b.size)
        return DoubleArray(n) { i -> (if (i < a.size) a[i] else 0.0) + (if (i < b.size) b[i] else 0.0) }
    }

    private fun feedAll(c: UtteranceCutter, x: DoubleArray): List<UtteranceCutter.Result> {
        val out = mutableListOf<UtteranceCutter.Result>()
        var i = 0
        while (i + chunk <= x.size) {
            val b = ByteArray(chunk * 2)
            for (k in 0 until chunk) {
                val v = x[i + k].toInt().coerceIn(-32768, 32767)
                b[2 * k] = (v and 0xFF).toByte(); b[2 * k + 1] = (v shr 8).toByte()
            }
            out += c.feed(b)
            i += chunk
        }
        return out
    }

    @Test fun oneSentenceBecomesOneUtteranceWithPreRollAndHangover() {
        val x = mix(noise(5.0, -65.0), concat(silence(1.0), voice(1.5, -22.0), silence(2.0)))
        val rs = feedAll(UtteranceCutter(), x)
        val us = rs.mapNotNull { it.utterance }
        assertEquals(1, us.size)
        val u = us[0]
        assertFalse(u.forced)
        val ms = u.pcm.size / 2 * 1000 / rate
        assertTrue("utterance $ms ms (speech 1500 + pre-roll 300 + hangover 700)", ms in 2000..3200)
        assertTrue("speech ${u.speechMs} ms", u.speechMs in 1000..1700)
        assertTrue("peak ${u.peakDb} dB", u.peakDb > -35.0)
        assertEquals(1, rs.count { it.opened })
        assertFalse(Whisper.isJunk("Thank you.", u.speechMs, u.peakDb))
    }

    @Test fun silenceAndRoomNoiseProduceNothing() {
        for (level in listOf(-90.0, -65.0, -45.0)) {
            val rs = feedAll(UtteranceCutter(), noise(8.0, level))
            assertTrue("level $level", rs.none { it.utterance != null || it.opened })
        }
    }

    @Test fun aClickIsTooShortToSend() {
        val x = mix(noise(5.0, -65.0), concat(silence(1.0), voice(0.12, -20.0, false), silence(2.5)))
        val rs = feedAll(UtteranceCutter(), x)
        assertTrue("no utterance", rs.none { it.utterance != null })
        assertTrue("dropped as too short", rs.any { it.tooShort })
    }

    @Test fun aVeryShortWordIsKeptButFlaggedByItsSpeechTime() {
        val x = mix(noise(5.0, -65.0), concat(silence(1.0), voice(0.45, -24.0, false), silence(2.5)))
        val u = feedAll(UtteranceCutter(), x).mapNotNull { it.utterance }.single()
        assertTrue("speech ${u.speechMs} ms", u.speechMs in 300..700)
        assertTrue(Whisper.isJunk("Thank you.", u.speechMs, u.peakDb))
    }

    @Test fun veryLongSpeechIsCutAtTheCap() {
        val x = mix(noise(14.0, -65.0), concat(silence(0.5), voice(12.0, -22.0, false), silence(1.5)))
        val us = feedAll(UtteranceCutter(maxMs = 5000), x).mapNotNull { it.utterance }
        assertTrue("got ${us.size}", us.size >= 2)
        assertTrue(us[0].forced)
        val ms = us[0].pcm.size / 2 * 1000 / rate
        assertTrue("first piece $ms ms", ms in 4900..5200)
    }

    @Test fun twoSentencesWithALongPauseAreTwoUtterances() {
        val x = mix(noise(10.0, -65.0), concat(silence(0.8), voice(1.0, -22.0), silence(2.0), voice(1.0, -22.0), silence(2.0)))
        val us = feedAll(UtteranceCutter(), x).mapNotNull { it.utterance }
        assertEquals(2, us.size)
    }

    // ---------------------------------------------------------------- TTS text

    @Test fun ttsTextIsCleanedAndSplitUnderTheLimit() {
        assertEquals(emptyList<String>(), TtsText.split("  **  "))
        val short = TtsText.split("Very good, sir. The kettle is **on**!")
        assertEquals(listOf("Very good, sir. The kettle is on!"), short)

        val long = (1..12).joinToString(" ") { "This is sentence number $it of a rather long reply, sir." }
        val parts = TtsText.split(long)
        assertTrue(parts.size > 3)
        assertTrue(parts.all { it.length <= TtsText.MAX_CHARS })
        assertEquals(long, parts.joinToString(" "))
    }

    @Test fun aLongSentenceIsCutAtACommaOrSpace() {
        val s = "word ".repeat(80).trim()
        val parts = TtsText.split(s)
        assertTrue(parts.all { it.length <= TtsText.MAX_CHARS })
        assertEquals(s, parts.joinToString(" "))
        val withCommas = (1..40).joinToString(", ") { "item$it" }
        val p2 = TtsText.split(withCommas)
        assertTrue(p2.all { it.length <= TtsText.MAX_CHARS })
        assertEquals(withCommas.replace(", ", ","), p2.joinToString("").replace(" ", ""))
    }

    @Test fun linksAreNotSpoken() {
        assertEquals(listOf("See for details."), TtsText.split("See https://example.com/a/b?c=1 for details."))
    }
}
