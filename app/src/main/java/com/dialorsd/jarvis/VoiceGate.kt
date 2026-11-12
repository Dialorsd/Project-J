package com.dialorsd.jarvis

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/*
 * Pure Kotlin (no Android imports) so it is unit-tested on the JVM (app/src/test).
 */

/**
 * On-device voice gate for Live mode: decides which microphone audio is worth sending to Gemini.
 * Input is 16-bit little-endian mono PCM in chunks of any size (Live.kt feeds 40 ms chunks).
 *
 *  - energy (dBFS) against an adaptive noise floor, plus the zero-crossing rate to tell voiced speech from hiss;
 *  - opens after [attackMs] of speech-like audio and then also releases the last [preRollMs] of audio,
 *    so the start of the first word is not clipped;
 *  - closes after [hangoverMs] without speech (short pauses between words keep it open);
 *  - [echoMode] (Jarvis is talking on the loudspeaker): a higher threshold and a longer attack, so only the
 *    user talking over him opens the gate (barge-in), not the leftover echo of his own voice.
 *  - steady noise that appears suddenly (a fan, a tap) can open it once, but the floor then catches up
 *    (rolling minimum while open) and it closes again.
 */
class VoiceGate(
    private val sampleRate: Int = 16000,
    val preRollMs: Int = 300,
    val hangoverMs: Int = 700,
    sensitivity: Float = 0.5f,
    private val attackMs: Int = 80,
    private val echoAttackMs: Int = 160,
    private val echoExtraDb: Double = 10.0,
) {
    enum class Event { NONE, OPENED, CLOSED }

    /** [send]: audio to stream now (pre-roll + this chunk when it opens), or null. */
    class Out(val send: ByteArray?, val event: Event, val db: Double, val speech: Boolean)

    /** 0 = only loud, close speech opens it; 1 = very sensitive. */
    var sensitivity: Float = sensitivity.coerceIn(0f, 1f)
        set(v) { field = v.coerceIn(0f, 1f) }

    /** True while Jarvis's own voice is playing. */
    var echoMode = false

    var isOpen = false
        private set

    /** Current noise floor estimate in dBFS (for tests / diagnostics). */
    var noiseFloorDb = Double.NaN
        private set

    private val preRoll = ArrayDeque<ByteArray>()
    private var preRollBytes = 0
    private var speechMs = 0.0      // consecutive speech-like time while closed
    private var silentMs = 0.0      // time without speech while open
    private var openMs = 0.0
    private val openDbs = ArrayDeque<Pair<Double, Double>>() // (dB, ms) over the last ROLL_MS while open
    private var openDbsMs = 0.0

    fun reset() {
        isOpen = false
        preRoll.clear(); preRollBytes = 0
        speechMs = 0.0; silentMs = 0.0; openMs = 0.0
        openDbs.clear(); openDbsMs = 0.0
    }

    /** Margin above the noise floor needed to count as speech: 18 dB (least sensitive) .. 6 dB (most). */
    fun marginDb(): Double = 18.0 - 12.0 * sensitivity + if (echoMode) echoExtraDb else 0.0

    /** Speech quieter than this is ignored whatever the floor: -50 dBFS (least sensitive) .. -62 dBFS. */
    private fun absMinDb(): Double = -50.0 - 12.0 * sensitivity + if (echoMode) echoExtraDb else 0.0

    fun process(pcm: ByteArray, len: Int = pcm.size): Out {
        val n = len - (len % 2)
        if (n <= 0) return Out(null, Event.NONE, -120.0, false)
        val ms = (n / 2) * 1000.0 / sampleRate
        val (db, zcr) = analyse(pcm, n)
        if (noiseFloorDb.isNaN()) noiseFloorDb = db.coerceIn(FLOOR_MIN, FLOOR_MAX)

        val threshold = max(noiseFloorDb + marginDb(), absMinDb())
        // Voiced speech has a low zero-crossing rate; broadband hiss is around 0.5. Very loud frames count
        // regardless (fricatives inside words).
        val speech = db >= threshold && (zcr < ZCR_MAX || db >= threshold + 10.0)
        val chunk = pcm.copyOf(n)

        if (!isOpen) {
            if (!speech) {
                adaptFloor(db)
                speechMs = 0.0
            } else speechMs += ms
            val need = if (echoMode) echoAttackMs else attackMs
            if (speech && speechMs >= need) {
                isOpen = true
                silentMs = 0.0; openMs = 0.0
                openDbs.clear(); openDbsMs = 0.0
                val out = ByteArray(preRollBytes + n)
                var off = 0
                for (b in preRoll) { System.arraycopy(b, 0, out, off, b.size); off += b.size }
                System.arraycopy(chunk, 0, out, off, n)
                preRoll.clear(); preRollBytes = 0
                trackOpen(db, ms)
                return Out(out, Event.OPENED, db, true)
            }
            pushPreRoll(chunk)
            return Out(null, Event.NONE, db, speech)
        }

        // open
        openMs += ms
        trackOpen(db, ms)
        if (speech) silentMs = 0.0 else silentMs += ms
        if (silentMs >= hangoverMs) {
            isOpen = false
            speechMs = 0.0
            return Out(chunk, Event.CLOSED, db, speech)
        }
        return Out(chunk, Event.NONE, db, speech)
    }

    private fun adaptFloor(db: Double) {
        val f = noiseFloorDb
        // His own voice (echo) must not lift the floor, or barge-in would get harder while he talks.
        if (echoMode && db > f) return
        // Falls quickly (quieter room), rises slowly (so the tail of speech does not lift it).
        val a = if (db < f) 0.25 else 0.03
        noiseFloorDb = (f + a * (db - f)).coerceIn(FLOOR_MIN, FLOOR_MAX)
    }

    /** While open: if even the quietest moment of the last ROLL_MS is loud, it is steady noise, not speech. */
    private fun trackOpen(db: Double, ms: Double) {
        openDbs.addLast(db to ms); openDbsMs += ms
        while (openDbsMs - openDbs.first().second >= ROLL_MS) { openDbsMs -= openDbs.removeFirst().second }
        if (openDbsMs >= ROLL_MS - 1) {
            val minDb = openDbs.minOf { it.first }
            if (minDb - 3.0 > noiseFloorDb) noiseFloorDb = (minDb - 3.0).coerceIn(FLOOR_MIN, FLOOR_MAX)
        }
    }

    private fun pushPreRoll(chunk: ByteArray) {
        preRoll.addLast(chunk); preRollBytes += chunk.size
        val max = sampleRate * 2 * preRollMs / 1000
        while (preRollBytes - preRoll.first().size >= max) { preRollBytes -= preRoll.removeFirst().size }
    }

    companion object {
        const val FLOOR_MIN = -90.0
        const val FLOOR_MAX = -20.0
        const val ZCR_MAX = 0.35
        const val ROLL_MS = 1500.0

        /** (level in dBFS, zero-crossing rate 0..1) of 16-bit LE PCM. */
        fun analyse(pcm: ByteArray, n: Int): Pair<Double, Double> {
            val samples = n / 2
            if (samples == 0) return -120.0 to 0.0
            var sum = 0.0
            var crossings = 0
            var prev = 0
            for (i in 0 until samples) {
                val v = (pcm[2 * i].toInt() and 0xFF) or (pcm[2 * i + 1].toInt() shl 8)
                sum += v.toDouble() * v
                if (i > 0 && ((v >= 0) != (prev >= 0))) crossings++
                prev = v
            }
            val rms = sqrt(sum / samples)
            val db = if (rms < 1e-9) -120.0 else 20.0 * log10(rms / 32768.0)
            return db to crossings.toDouble() / samples
        }
    }
}

/** Free-tier daily quotas reset at midnight Pacific time (ai.google.dev/gemini-api/docs/rate-limits). */
object QuotaClock {
    private val PACIFIC: ZoneId = ZoneId.of("America/Los_Angeles")

    /** Epoch ms of the next midnight in Pacific time after [nowMs]. */
    fun nextPacificMidnight(nowMs: Long): Long {
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), PACIFIC)
        return now.toLocalDate().plusDays(1).atStartOfDay(PACIFIC).toInstant().toEpochMilli()
    }

    /** "yyyy-MM-dd" of the Pacific-time day (the quota day) containing [nowMs]. */
    fun pacificDay(nowMs: Long): String =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), PACIFIC).toLocalDate().toString()
}
