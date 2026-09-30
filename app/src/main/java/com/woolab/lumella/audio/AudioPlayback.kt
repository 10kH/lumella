package com.woolab.lumella.audio

import com.woolab.tutor.capture.WavTap
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import java.util.Base64

/**
 * RayNeo speaker playback of realtime `response.audio.delta` chunks: 24kHz PCM16 mono,
 * streamed via [AudioTrack.MODE_STREAM] (ported from `TUTOR/ELLA` MainActivity's
 * `initAudioTrack`).
 *
 * Two deliberate choices keep the glasses' media stack out of this:
 *  - [AudioAttributes.USAGE_ASSISTANT], not `USAGE_MEDIA`. This is a tutor's voice, not
 *    music; declaring it as media made RayNeo's BLE music bridge treat the app as a player
 *    and pull up YouTube Music on launch (reported on-device 2026-07-28).
 *  - [AudioTrack.play] is deferred until the first audio chunk actually arrives. Entering
 *    PLAYING at startup looks exactly like "playback started" to that same bridge.
 *
 * Not exercised by JVM unit tests (real `android.media.AudioTrack`); verified on-device via
 * the P5 smoke pass.
 */
class AudioPlayback(private val sampleRateHz: Int = 24_000) {
    private var audioTrack: AudioTrack? = null

    /**
     * The tutor's voice for a take, while filming. The POV has no sound track, and the mic that
     * the learner tap reads is echo-cancelled so the tutor is deliberately absent from it; the
     * tutor's PCM is in hand here on its way to the speaker, so it is written out in parallel
     * rather than recovered acoustically. Queued only — see [WavTap] for why the disk write must
     * not happen on this thread.
     */
    @Volatile var tap: WavTap? = null

    /** Allocates the streaming AudioTrack WITHOUT starting playback. Safe to call repeatedly. */
    fun start() {
        if (audioTrack != null) return
        val minBytes = AudioTrack.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        // Well above the minimum. The minimum here is 1928 frames, 80ms, and write() is called
        // from the websocket reader, which the realtime API keeps blocked in it for ~90% of
        // every reply (it delivers faster than real time). Each time write() returns, that
        // thread has what is left in the buffer to decode and bring the next chunk before the
        // speaker runs dry. With POV recording loading the device to 255-322% of 400%, 80ms was
        // missed 11 times in one run (2026-09-28, artifacts/perf). More buffer is more slack for
        // the same thread; it does not delay the first word, because the start threshold below
        // stays at the minimum.
        val bufferBytes = maxOf(minBytes, sampleRateHz * 2 * PLAYBACK_BUFFER_MS / 1000)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRateHz)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // A streaming track otherwise waits for its whole buffer before it starts.
            runCatching { track.setStartThresholdInFrames(minBytes / 2) }
        }
        audioTrack = track
    }

    /** Enters PLAYING lazily, on the first real chunk, so merely opening the app is silent. */
    private fun ensurePlaying(track: AudioTrack) {
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
            runCatching { track.play() }
        }
    }

    /**
     * What one tutor response cost the playback path. Opened on the first delta of a response,
     * closed by [endResponseStats]. The harness (ops/perf-run.sh) reads the "perf:" log line.
     *
     * - underruns: AudioTrack.getUnderrunCount delta — the track ran dry and the wearer heard a gap.
     * - audioMs: PCM received for this response, as audio time.
     * - wallMs: first delta to last delta. wallMs well above audioMs means the reply was
     *   delivered (and so played) slower than real time: the "stretched speech" symptom.
     * - maxWriteMs / sumWriteMs: how long track.write blocked this thread. write() blocks when
     *   the track's buffer is full, so large values mean playback is the bottleneck; near zero
     *   with underruns means the data arrived too late.
     * - maxTapMs: what handing the chunk to the voice tap cost this thread (a queue, so ~0).
     * - minLeadMs / maxGapMs / minLeadAtChunk: audio queued so far (preroll included) minus time
     *   since the first delta, at each delta's arrival; the longest wait between deltas; and
     *   which delta the lowest lead was at. A negative lead means the reply reached this app
     *   slower than it plays. Underruns with the lead positive throughout would be this
     *   device's fault: the thread writing to the track did not get to run.
     */
    private class ResponseStats(val underrunsAtStart: Int, val prerollMs: Long) {
        val startedAtMs = System.currentTimeMillis()
        var lastDeltaAtMs = startedAtMs
        var audioBytes = 0L
        var chunks = 0
        var maxWriteMs = 0L
        var sumWriteMs = 0L
        var maxTapMs = 0L
        var minLeadMs = Long.MAX_VALUE
        var minLeadAtChunk = 0
        var maxGapMs = 0L
    }
    @Volatile private var stats: ResponseStats? = null

    /** Frames handed to the track since it was created (preroll included). */
    @Volatile private var framesWritten = 0L

    /**
     * Logs, [delayMs] after a reply's last audio, how much of what was written the speaker has
     * not played. Streaming tracks leave a remainder below the mixer's minimum sitting in the
     * buffer until more data or a stop arrives; the wearer then does not hear a reply's last
     * syllables until the next reply. Measures it rather than assuming; see [checkDrained].
     */
    fun checkDrainedAfter(delayMs: Long, log: (String) -> Unit) {
        val writtenAtEnd = framesWritten
        Thread {
            Thread.sleep(delayMs)
            log(checkDrained(writtenAtEnd))
        }.apply { isDaemon = true; name = "lumella-drain-check" }.start()
    }

    private fun checkDrained(writtenAtEnd: Long): String {
        val track = audioTrack ?: return "perf: drain track=none"
        // playbackHeadPosition is an unsigned 32-bit frame count that wraps; a take is far
        // shorter than the ~50h it takes to wrap at 24kHz.
        val head = track.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
        val unplayed = writtenAtEnd - head
        return "perf: drain unplayedMs=${unplayed * 1000 / sampleRateHz} writtenFrames=$writtenAtEnd headFrames=$head " +
            "playState=${track.playState}"
    }

    /** Closes the current response's stats and returns them as one log-ready line, or null if none. */
    fun endResponseStats(): String? {
        val st = stats ?: return null
        stats = null
        val track = audioTrack
        val underruns = if (track != null) track.underrunCount - st.underrunsAtStart else -1
        val audioMs = st.audioBytes * 1000L / (sampleRateHz * 2L)
        val wallMs = st.lastDeltaAtMs - st.startedAtMs
        return "perf: playback underruns=$underruns audioMs=$audioMs wallMs=$wallMs chunks=${st.chunks} " +
            "maxWriteMs=${st.maxWriteMs} sumWriteMs=${st.sumWriteMs} maxTapMs=${st.maxTapMs} " +
            "bufferFrames=${track?.bufferSizeInFrames ?: -1} " +
            "minLeadMs=${if (st.minLeadMs == Long.MAX_VALUE) 0 else st.minLeadMs} maxGapMs=${st.maxGapMs} " +
            "minLeadAtChunk=${st.minLeadAtChunk}"
    }

    /** Decodes and enqueues a base64 PCM16 delta chunk for streaming playback. Tolerant of malformed input. */
    fun playDelta(base64Pcm16: String) {
        val bytes = try {
            Base64.getDecoder().decode(base64Pcm16)
        } catch (_: IllegalArgumentException) {
            return
        }
        val track = audioTrack ?: return
        ensurePlaying(track)
        val st = stats ?: startResponse(track)
        val arrivedAtMs = System.currentTimeMillis()
        if (st.chunks > 0) {
            val lead = st.prerollMs + st.audioBytes * 1000L / (sampleRateHz * 2L) - (arrivedAtMs - st.startedAtMs)
            if (lead < st.minLeadMs) { st.minLeadMs = lead; st.minLeadAtChunk = st.chunks }
            val gap = arrivedAtMs - st.lastDeltaAtMs
            if (gap > st.maxGapMs) st.maxGapMs = gap
        }
        val t0 = System.nanoTime()
        val wrote = track.write(bytes, 0, bytes.size)
        if (wrote > 0) framesWritten += wrote / 2
        val writeMs = (System.nanoTime() - t0) / 1_000_000
        st.chunks++
        st.audioBytes += bytes.size
        st.lastDeltaAtMs = System.currentTimeMillis()
        st.sumWriteMs += writeMs
        if (writeMs > st.maxWriteMs) st.maxWriteMs = writeMs
        tap?.let { t ->
            val t1 = System.nanoTime()
            t.write(bytes)
            val tapMs = (System.nanoTime() - t1) / 1_000_000
            if (tapMs > st.maxTapMs) st.maxTapMs = tapMs
        }
    }

    /**
     * Opens a reply with [PREROLL_MS] of silence ahead of its first word.
     *
     * A reply's first delta is small (~100ms of speech) and the next, ~250ms, comes 110-240ms
     * later, so the speaker runs dry inside the first word. Measured 2026-09-28 with NO
     * recording running and the device at 62% of 400% (artifacts/perf/lead-none.json): 4 of 7
     * replies underran. In three of them the reply had fallen behind the speaker by 91-146ms at
     * its second delta; the fourth stalled mid-reply (-794ms after a 1.2s gap in delivery, which
     * no preroll covers). No buffer size cures that — the data is not here yet — and it was most
     * of what was left of the stutter once POV load was dealt with. Starting each reply that
     * much later is the jitter buffer. It costs the wearer [PREROLL_MS] before the first word, on
     * top of the 0.6-1s pause a reply takes anyway.
     *
     * The tap gets the same silence, so the tutor's voice lands in the take when it was heard.
     */
    private fun startResponse(track: AudioTrack): ResponseStats {
        val st = ResponseStats(track.underrunCount, PREROLL_MS.toLong())
        stats = st
        val silence = ByteArray(sampleRateHz * 2 * PREROLL_MS / 1000)
        val wrote = track.write(silence, 0, silence.size)
        if (wrote > 0) framesWritten += wrote / 2
        tap?.write(silence)
        return st
    }

    /**
     * Drops whatever is queued and not yet heard (a reply cut off by RunawayReplyGuard). The
     * track stays open; the next reply's first delta plays it again.
     */
    fun flush() {
        val track = audioTrack ?: return
        runCatching {
            track.pause()
            track.flush()
        }
    }

    fun stop() {
        try {
            audioTrack?.stop()
        } catch (_: Exception) {
            // best-effort
        }
        audioTrack?.release()
        audioTrack = null
    }

    private companion object {
        const val PLAYBACK_BUFFER_MS = 500
        const val PREROLL_MS = 150
    }
}
