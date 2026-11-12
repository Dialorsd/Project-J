package com.dialorsd.jarvis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Synthetic 16 kHz PCM through [VoiceGate] in 40 ms chunks, as Live.kt feeds it. */
class VoiceGateTest {
    private val rate = 16000
    private val chunkSamples = 640 // 40 ms
    private val chunkMs = 40

    private fun amp(dbfs: Double) = 32768.0 * 10.0.pow(dbfs / 20.0)

    /** White noise with the given RMS level. */
    private fun noise(seconds: Double, dbfs: Double, rnd: Random = Random(7)): DoubleArray {
        // Uniform in [-a, a] has RMS a/sqrt(3).
        val a = amp(dbfs) * sqrt(3.0)
        return DoubleArray((seconds * rate).toInt()) { (rnd.nextDouble() * 2 - 1) * a }
    }

    /** Voiced-speech-like signal: 5 harmonics of 140 Hz, optional 4 Hz syllable envelope, RMS ~ dbfs at full envelope. */
    private fun voice(seconds: Double, dbfs: Double, syllables: Boolean = true): DoubleArray {
        val n = (seconds * rate).toInt()
        val h = 5
        val a = amp(dbfs) / sqrt(h / 2.0)
        return DoubleArray(n) { i ->
            val t = i.toDouble() / rate
            var v = 0.0
            for (k in 1..h) v += sin(2 * PI * 140.0 * k * t) / k * 1.6
            val env = if (syllables) 0.15 + 0.85 * 0.5 * (1 - cos(2 * PI * 4.0 * t)) else 1.0
            v * a * env
        }
    }

    private fun mix(vararg parts: DoubleArray): DoubleArray {
        val n = parts.maxOf { it.size }
        return DoubleArray(n) { i -> parts.sumOf { if (i < it.size) it[i] else 0.0 } }
    }

    private fun concat(vararg parts: DoubleArray): DoubleArray {
        val out = DoubleArray(parts.sumOf { it.size }); var o = 0
        for (p in parts) { System.arraycopy(p, 0, out, o, p.size); o += p.size }
        return out
    }

    private fun silence(seconds: Double) = DoubleArray((seconds * rate).toInt())

    private fun pcm(x: DoubleArray, from: Int, count: Int): ByteArray {
        val b = ByteArray(count * 2)
        for (i in 0 until count) {
            val v = x[from + i].toInt().coerceIn(-32768, 32767)
            b[2 * i] = (v and 0xFF).toByte(); b[2 * i + 1] = (v shr 8).toByte()
        }
        return b
    }

    private data class Step(val tMs: Int, val out: VoiceGate.Out)

    private fun run(g: VoiceGate, x: DoubleArray): List<Step> {
        val steps = mutableListOf<Step>()
        var i = 0
        while (i + chunkSamples <= x.size) {
            steps += Step(i * 1000 / rate, g.process(pcm(x, i, chunkSamples)))
            i += chunkSamples
        }
        return steps
    }

    private fun sentMs(steps: List<Step>) = steps.sumOf { (it.out.send?.size ?: 0) / 2 } * 1000 / rate

    @Test fun digitalSilenceNeverOpens() {
        val steps = run(VoiceGate(), silence(5.0))
        assertTrue(steps.none { it.out.event == VoiceGate.Event.OPENED })
        assertEquals(0, sentMs(steps))
    }

    @Test fun steadyRoomNoiseNeverOpens() {
        for (level in listOf(-70.0, -55.0, -40.0)) {
            val steps = run(VoiceGate(), noise(8.0, level))
            assertTrue("level $level", steps.none { it.out.event == VoiceGate.Event.OPENED })
        }
    }

    @Test fun speechBurstOpensFastWithPreRollAndClosesAfterHangover() {
        val g = VoiceGate()
        val bg = -65.0
        val x = mix(noise(4.0, bg), concat(silence(1.0), voice(1.5, -22.0), silence(1.5)))
        val steps = run(g, x)
        val opened = steps.filter { it.out.event == VoiceGate.Event.OPENED }
        val closed = steps.filter { it.out.event == VoiceGate.Event.CLOSED }
        assertEquals("opens exactly once", 1, opened.size)
        assertEquals("closes exactly once", 1, closed.size)
        val o = opened[0]
        assertTrue("opens within 200 ms of the onset (was ${o.tMs})", o.tMs in 1000..1200)
        // The opening chunk carries the pre-roll: at least 300 ms before it, plus the chunk itself.
        val firstSend = o.out.send
        assertNotNull(firstSend)
        val firstMs = firstSend!!.size / 2 * 1000 / rate
        assertTrue("pre-roll present (first send $firstMs ms)", firstMs >= g.preRollMs + chunkMs)
        // Stays open across the syllable dips, closes 700..900 ms after the speech ends (t = 2500 ms).
        val c = closed[0]
        assertTrue("closed at ${c.tMs}", c.tMs in (2500 + 700 - chunkMs)..(2500 + 950))
        // Far less audio is sent than the 4 s recorded.
        assertTrue("sent ${sentMs(steps)} ms", sentMs(steps) in 1500..3000)
    }

    @Test fun hangoverBridgesShortPausesButNotLongOnes() {
        val g = VoiceGate()
        val x = mix(noise(6.0, -65.0),
            concat(silence(0.5), voice(0.8, -25.0, false), silence(0.4), voice(0.8, -25.0, false), silence(3.5)))
        val steps = run(g, x)
        assertEquals("a 400 ms pause does not close it", 1, steps.count { it.out.event == VoiceGate.Event.OPENED })
        val c = steps.single { it.out.event == VoiceGate.Event.CLOSED }
        val speechEnd = 500 + 800 + 400 + 800
        assertTrue("still open 600 ms after speech", steps.filter { it.tMs in speechEnd..speechEnd + 600 }.all { it.out.send != null })
        assertTrue("closed at ${c.tMs}", c.tMs <= speechEnd + 900)
        assertTrue("nothing sent after close", steps.filter { it.tMs > c.tMs }.all { it.out.send == null })
    }

    @Test fun suddenSteadyNoiseClosesAgain() {
        val g = VoiceGate()
        val x = concat(noise(2.0, -70.0, Random(1)), noise(8.0, -35.0, Random(2)))
        val steps = run(g, x)
        val lastClose = steps.lastOrNull { it.out.event == VoiceGate.Event.CLOSED }
        assertNotNull("must close again", lastClose)
        assertTrue("closed by ${lastClose!!.tMs}", lastClose.tMs <= 2000 + 3000)
        assertFalse(g.isOpen)
        assertTrue("stays closed afterwards", steps.filter { it.tMs > 6000 }.all { it.out.send == null })
    }

    @Test fun echoModeIgnoresQuietEchoButLetsTheUserBargeIn() {
        val echo = mix(noise(3.0, -65.0), concat(silence(1.0), voice(2.0, -50.0)))
        val normal = run(VoiceGate(), echo)
        assertTrue("normal mode hears the -50 dB voice", normal.any { it.out.event == VoiceGate.Event.OPENED })

        val g = VoiceGate().apply { echoMode = true }
        assertTrue("echo mode ignores it", run(g, echo).none { it.out.event == VoiceGate.Event.OPENED })

        val g2 = VoiceGate().apply { echoMode = true }
        val user = mix(noise(3.0, -65.0), concat(silence(1.0), voice(1.5, -22.0)))
        val st = run(g2, user)
        val o = st.firstOrNull { it.out.event == VoiceGate.Event.OPENED }
        assertNotNull("user over the echo opens it", o)
        assertTrue("barge-in within 300 ms (was ${o!!.tMs})", o.tMs <= 1300)
    }

    @Test fun sensitivityChangesWhatCounts() {
        val quiet = mix(noise(3.0, -75.0), concat(silence(1.0), voice(1.5, -56.0, false)))
        assertTrue(run(VoiceGate(sensitivity = 0f), quiet).none { it.out.event == VoiceGate.Event.OPENED })
        assertTrue(run(VoiceGate(sensitivity = 1f), quiet).any { it.out.event == VoiceGate.Event.OPENED })
    }

    @Test fun resetDropsState() {
        val g = VoiceGate()
        run(g, mix(noise(2.0, -65.0), concat(silence(0.5), voice(1.5, -22.0))))
        assertTrue(g.isOpen)
        g.reset()
        assertFalse(g.isOpen)
        assertNull(g.process(ByteArray(1280)).send)
    }

    @Test fun analyseLevels() {
        val (db, zcr) = VoiceGate.analyse(pcm(noise(0.1, -30.0), 0, 1600), 3200)
        assertEquals(-30.0, db, 0.7)
        assertTrue("white noise zcr $zcr", zcr > 0.4)
        val (vdb, vz) = VoiceGate.analyse(pcm(voice(0.1, -20.0, false), 0, 1600), 3200)
        assertEquals(-20.0, vdb, 2.0)
        assertTrue("voiced zcr $vz", vz < VoiceGate.ZCR_MAX)
    }

    @Test fun pacificMidnight() {
        // 2026-11-12 10:00 UTC = 02:00 PST -> next reset 2026-11-13 00:00 PST = 08:00 UTC.
        val now = Instant.parse("2026-11-12T10:00:00Z").toEpochMilli()
        assertEquals(Instant.parse("2026-11-13T08:00:00Z").toEpochMilli(), QuotaClock.nextPacificMidnight(now))
        assertEquals("2026-11-12", QuotaClock.pacificDay(now))
        // 2026-11-12 07:59 UTC is still 2026-11-11 in Pacific time.
        assertEquals("2026-11-11", QuotaClock.pacificDay(Instant.parse("2026-11-12T07:59:00Z").toEpochMilli()))
        // Summer (PDT, UTC-7).
        val summer = Instant.parse("2026-07-01T12:00:00Z").toEpochMilli()
        assertEquals(Instant.parse("2026-07-02T07:00:00Z").toEpochMilli(), QuotaClock.nextPacificMidnight(summer))
    }
}
