package com.woolab.lumella.voice

import com.woolab.lumella.voice.RunawayReplyGuard.Cut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class RunawayReplyGuardTest {
    private val sr = 24_000

    /** [ms] of PCM16 mono: a tone at [amp] whose loudness follows [envelope] (0..1) over time. */
    private fun pcm(ms: Int, amp: Double, startMs: Int = 0, envelope: (Double) -> Double = { 1.0 }): ByteArray {
        val n = sr * ms / 1000
        val out = ByteArray(n * 2)
        for (k in 0 until n) {
            val t = (startMs + k * 1000.0 / sr) / 1000.0
            val v = (amp * envelope(t) * sin(2 * PI * 180 * t)).toInt().coerceIn(-32768, 32767)
            out[2 * k] = (v and 0xff).toByte()
            out[2 * k + 1] = (v shr 8).toByte()
        }
        return out
    }

    /** Speech-like: a syllable envelope (4 per second) with a short pause every 1.2 s. */
    private val syllables: (Double) -> Double = { t ->
        val inPause = (t % 1.2) > 1.0
        if (inPause) 0.01 else 0.15 + 0.85 * kotlin.math.abs(sin(PI * 4 * t))
    }

    private fun feed(g: RunawayReplyGuard, totalMs: Int, amp: Double, env: (Double) -> Double, startMs: Int = 0): Pair<Cut?, Int> {
        var at = startMs
        while (at < startMs + totalMs) {
            g.onPcm(pcm(250, amp, at, env))?.let { return it to at + 250 }
            at += 250
        }
        return null to at
    }

    @Test
    fun speechIsNeverCut() {
        val g = RunawayReplyGuard()
        g.onResponseStarted()
        val (why, _) = feed(g, 13_000, 8_000.0, syllables)
        assertNull(why)
        assertFalse(g.isCut())
    }

    /** 9/30 19:27: ~5 s of speech, then a steady hum (-28 dBFS). Cut about 2 s into the hum. */
    @Test
    fun aSteadyHumAfterSpeechIsCutWithinAboutTwoSeconds() {
        val g = RunawayReplyGuard()
        g.onResponseStarted()
        assertNull(feed(g, 5_000, 8_000.0, syllables).first)
        val (why, at) = feed(g, 30_000, 1_300.0, { 1.0 }, startMs = 5_000)
        assertEquals(Cut.STEADY_SOUND, why)
        assertTrue("cut ${at - 5_000} ms into the hum", at - 5_000 in 2_000..2_500)
        assertNull("reported once", g.onPcm(pcm(250, 1_300.0)))
        assertTrue(g.isCut())
    }

    @Test
    fun silenceIsNotSteadySound() {
        val g = RunawayReplyGuard()
        g.onResponseStarted()
        assertNull(feed(g, 5_000, 0.0, { 1.0 }).first)
    }

    @Test
    fun aReplyFarPastAnyNormalLengthIsCut() {
        val g = RunawayReplyGuard()
        g.onResponseStarted()
        val (why, at) = feed(g, 60_000, 8_000.0, syllables)
        assertEquals(Cut.TOO_LONG, why)
        assertEquals(20_250, at)
    }

    @Test
    fun theNextResponseStartsFresh() {
        val g = RunawayReplyGuard()
        g.onResponseStarted()
        feed(g, 3_000, 1_300.0, { 1.0 })
        assertTrue(g.isCut())
        g.onResponseStarted()
        assertFalse(g.isCut())
        assertNull(g.onPcm(pcm(250, 8_000.0, 0, syllables)))
    }
}
