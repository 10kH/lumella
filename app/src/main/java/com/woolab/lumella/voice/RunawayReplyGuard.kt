package com.woolab.lumella.voice

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Stops a reply that has turned into sound that is not speech.
 *
 * 2026-09-30 19:27 (long take lt7, on camera): after a normal ~5 s reply the realtime model kept
 * streaming a steady hum at -28 dBFS for over 30 s. It played through the glasses' speaker loud
 * enough that the wearer took the glasses off; it only ended because the websocket reader, blocked
 * on AudioTrack.write, missed a pong and the socket was torn down.
 *
 * Speech rises and falls with every syllable and pauses between words; that hum did not. Over the
 * tutor voice of every take recorded so far (15 takes, ~/shots/ *-tutor.wav, 9/30), no 2-second
 * stretch of continuous sound stayed within 7 dB of loudness; the hum stayed within 0.4 dB. So a
 * reply is cut when either:
 * - [steadyWindowMs] of continuous sound (every 100 ms frame above [soundFloorDb]) varies by less
 *   than [steadySpreadDb] (a hum, a tone, static), or
 * - the reply's audio passes [maxReplyMs] (tutor replies are 1-2 sentences; the longest measured
 *   reply was 13.2 s).
 *
 * An earlier version cut on "audio without a transcript delta"; measured on spoken replies the
 * transcript runs ahead and the last words arrive up to 3.95 s after it, too close to any
 * threshold that would still catch the hum quickly. Websocket reader thread only.
 */
class RunawayReplyGuard(
    private val sampleRateHz: Int = 24_000,
    private val steadyWindowMs: Int = 2_000,
    private val steadySpreadDb: Double = 3.0,
    private val soundFloorDb: Double = -45.0,
    private val maxReplyMs: Long = 20_000,
) {
    enum class Cut { STEADY_SOUND, TOO_LONG }

    private val frameSamples = sampleRateHz / 10
    private val window = DoubleArray(steadyWindowMs / 100)
    private var framesInWindow = 0
    private var next = 0
    private var frameSumSq = 0.0
    private var frameCount = 0
    private var replySamples = 0L
    private var cut = false

    /** A new response started: everything resets, audio flows again. */
    fun onResponseStarted() {
        framesInWindow = 0
        next = 0
        frameSumSq = 0.0
        frameCount = 0
        replySamples = 0
        cut = false
    }

    /** True while the current reply has been cut: its remaining audio is not played. */
    fun isCut(): Boolean = cut

    /** Feeds one delta of 16-bit little-endian mono PCM; returns why to cut now (once), or null. */
    fun onPcm(pcm: ByteArray): Cut? {
        if (cut) return null
        var i = 0
        while (i + 1 < pcm.size) {
            val s = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xff)).toShort().toDouble()
            frameSumSq += s * s
            frameCount++
            if (frameCount == frameSamples) closeFrame()
            i += 2
        }
        replySamples += pcm.size / 2
        val why = when {
            framesInWindow == window.size && steady() -> Cut.STEADY_SOUND
            replySamples * 1000 / sampleRateHz > maxReplyMs -> Cut.TOO_LONG
            else -> null
        }
        if (why != null) cut = true
        return why
    }

    private fun closeFrame() {
        val rms = sqrt(frameSumSq / frameCount)
        window[next] = 20 * log10(rms / 32768.0 + 1e-12)
        next = (next + 1) % window.size
        if (framesInWindow < window.size) framesInWindow++
        frameSumSq = 0.0
        frameCount = 0
    }

    private fun steady(): Boolean {
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        for (db in window) {
            if (db <= soundFloorDb) return false
            if (db < lo) lo = db
            if (db > hi) hi = db
        }
        return hi - lo < steadySpreadDb
    }
}
