package com.woolab.lumella.audio

import com.woolab.tutor.capture.WavTap
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

/**
 * RayNeo microphone capture at 24kHz PCM16 mono — the OpenAI Realtime API's required input
 * format (ported from `TUTOR/ELLA` MainActivity's `SAMPLE_RATE`/`AudioRecord` setup and
 * `streamAudioToAPI` loop). Streams base64-framed chunks to [onChunk] on a dedicated daemon
 * thread; never touches the WS/transport directly, so it stays independently
 * testable/replaceable.
 *
 * Not exercised by JVM unit tests (real `android.media.AudioRecord`); verified on-device via
 * the P5 smoke pass.
 */
class AudioCapture(
    private val sampleRateHz: Int = 24_000,
    private val onChunk: (base64Pcm16: String) -> Unit,
    private val onError: (String) -> Unit = {},
) {
    private var audioRecord: AudioRecord? = null
    private val recording = AtomicBoolean(false)
    private var thread: Thread? = null

    val isRecording: Boolean get() = recording.get()

    /**
     * The learner's voice for a take, while filming: exactly the PCM the tutor heard, on the
     * same clock as the tutor's tap. Replaces the POV's own sound track, which made CameraX open
     * the microphone a second time and AAC-encode it in software: 30 points of the device's 400%
     * against the same take without it (2026-09-28, artifacts/perf/README.md).
     */
    @Volatile var tap: WavTap? = null

    /** Starts capture. Caller MUST already hold RECORD_AUDIO permission; fails closed otherwise. */
    fun start() {
        if (recording.get()) return
        try {
            val chunkBytes = AudioRecord.getMinBufferSize(
                sampleRateHz,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (chunkBytes <= 0) {
                onError("AudioRecord.getMinBufferSize failed ($chunkBytes)")
                return
            }
            // Reads stay minimum-sized (40ms here), so the realtime API gets audio exactly as
            // soon as before. The buffer behind them is larger: at the minimum, any stall of
            // this thread longer than one read lost the learner's words outright; now a stall
            // up to CAPTURE_BUFFER_MS is caught up on the next reads.
            val bufferBytes = maxOf(chunkBytes, sampleRateHz * 2 * CAPTURE_BUFFER_MS / 1000)
            val record = AudioRecord(
                // Hands-free keeps the microphone open while the tutor is speaking, so a
                // plain MIC source feeds the tutor's own voice back in and server VAD treats
                // it as the learner talking. VOICE_COMMUNICATION applies the platform's
                // echo canceller and noise suppression in hardware, which the glasses
                // declare support for in /vendor/etc/audio_effects.xml.
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRateHz,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                onError("AudioRecord failed to initialize")
                record.release()
                return
            }
            audioRecord = record
            recording.set(true)
            record.startRecording()

            thread = Thread({ streamLoop(record, chunkBytes, bufferBytes) }, "lumella-audio-capture").apply {
                isDaemon = true
                start()
            }
        } catch (e: SecurityException) {
            onError("Missing RECORD_AUDIO permission: ${e.message}")
        } catch (e: Exception) {
            onError("Audio capture start failed: ${e.message}")
        }
    }

    /**
     * Capture-side stall accounting for the perf harness, logged every [PERF_WINDOW_MS] as a
     * "perf: capture" line. Each read takes chunkMs of audio.
     *  - lateReads: the loop came back more than two chunks late — it was not scheduled.
     *  - lostReads: it came back later than the whole AudioRecord buffer, which has overrun by
     *    then: the learner's words in that stretch are gone.
     */
    @Volatile var perfLog: ((String) -> Unit)? = null

    private fun streamLoop(record: AudioRecord, chunkBytes: Int, bufferBytes: Int) {
        // The audio priority the platform gives its own audio threads. Under POV load the
        // camera HAL alone keeps most of a core busy; a normal-priority reader waits behind it.
        // Not fatal, so not onError (which the wearer sees as a mic failure).
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
            .onFailure { Log.w("lumella", "capture thread priority not raised: ${it.message}") }
        val buffer = ByteArray(chunkBytes)
        var reported = false
        val chunkMs = chunkBytes * 1000L / (sampleRateHz * 2L)
        val bufferMs = bufferBytes * 1000L / (sampleRateHz * 2L)
        var windowStart = System.currentTimeMillis()
        var lastReadEnd = 0L
        var reads = 0
        var lateReads = 0
        var lostReads = 0
        var maxGapMs = 0L
        var maxHandleMs = 0L
        while (recording.get()) {
            val readStart = System.currentTimeMillis()
            if (lastReadEnd != 0L) {
                val gap = readStart - lastReadEnd
                if (gap > maxHandleMs) maxHandleMs = gap
            }
            val read = try {
                record.read(buffer, 0, buffer.size)
            } catch (e: Exception) {
                onError("AudioRecord.read threw: ${e.message}")
                return
            }
            val readEnd = System.currentTimeMillis()
            if (lastReadEnd != 0L) {
                val cycle = readEnd - lastReadEnd
                if (cycle > maxGapMs) maxGapMs = cycle
                if (cycle > chunkMs * 2) lateReads++
                if (cycle > bufferMs) lostReads++
            }
            lastReadEnd = readEnd
            reads++
            if (readEnd - windowStart >= PERF_WINDOW_MS) {
                perfLog?.invoke(
                    "perf: capture reads=$reads lateReads=$lateReads lostReads=$lostReads " +
                        "maxCycleMs=$maxGapMs maxHandleMs=$maxHandleMs chunkMs=$chunkMs bufferMs=$bufferMs",
                )
                windowStart = readEnd; reads = 0; lateReads = 0; lostReads = 0; maxGapMs = 0; maxHandleMs = 0
            }
            when {
                read > 0 -> {
                    val pcm = buffer.copyOf(read)
                    tap?.write(pcm)
                    onChunk(Base64.getEncoder().encodeToString(pcm))
                }
                // Negative values are AudioRecord error codes (ERROR_INVALID_OPERATION -3,
                // ERROR_BAD_VALUE -2, ERROR_DEAD_OBJECT -6). These were silently treated as
                // "no data", so a dead mic looked identical to a quiet room and turns just
                // reported zero audio with no explanation. Report once per session.
                read < 0 -> {
                    if (!reported) {
                        reported = true
                        onError("AudioRecord.read error code $read")
                    }
                    return
                }
            }
        }
    }

    /** Stops capture, releasing the AudioRecord. Safe to call repeatedly / before start. */
    fun stop() {
        if (!recording.getAndSet(false)) return
        thread?.join(1_000)
        thread = null
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
            // best-effort
        }
        audioRecord?.release()
        audioRecord = null
    }

    companion object {
        const val PERF_WINDOW_MS = 5_000L
        /** Audio the AudioRecord holds behind a late read; a live tap's slack ([WavTap]). */
        const val CAPTURE_BUFFER_MS = 400
    }
}
