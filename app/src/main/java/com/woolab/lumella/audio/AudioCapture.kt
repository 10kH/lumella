package com.woolab.lumella.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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

    /** Starts capture. Caller MUST already hold RECORD_AUDIO permission; fails closed otherwise. */
    fun start() {
        if (recording.get()) return
        try {
            val bufferSize = AudioRecord.getMinBufferSize(
                sampleRateHz,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (bufferSize <= 0) {
                onError("AudioRecord.getMinBufferSize failed ($bufferSize)")
                return
            }
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
                bufferSize,
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                onError("AudioRecord failed to initialize")
                record.release()
                return
            }
            audioRecord = record
            recording.set(true)
            record.startRecording()

            thread = Thread({ streamLoop(record, bufferSize) }, "lumella-audio-capture").apply {
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
     * Capture-side stall accounting for the perf harness. One buffer holds bufferMs of audio;
     * if the loop comes back to read() much later than that, the platform's ring buffer has been
     * filling while we were busy (encoding, sending) and past its size it overruns — the
     * learner's words are lost. Logged every [PERF_WINDOW_MS] as a "perf: capture" line.
     */
    @Volatile var perfLog: ((String) -> Unit)? = null

    private fun streamLoop(record: AudioRecord, bufferSize: Int) {
        val buffer = ByteArray(bufferSize)
        var reported = false
        val bufferMs = bufferSize * 1000L / (sampleRateHz * 2L)
        var windowStart = System.currentTimeMillis()
        var lastReadEnd = 0L
        var reads = 0
        var lateReads = 0
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
                if (cycle > bufferMs * 2) lateReads++
            }
            lastReadEnd = readEnd
            reads++
            if (readEnd - windowStart >= PERF_WINDOW_MS) {
                perfLog?.invoke(
                    "perf: capture reads=$reads lateReads=$lateReads maxCycleMs=$maxGapMs " +
                        "maxHandleMs=$maxHandleMs bufferMs=$bufferMs",
                )
                windowStart = readEnd; reads = 0; lateReads = 0; maxGapMs = 0; maxHandleMs = 0
            }
            when {
                read > 0 -> onChunk(Base64.getEncoder().encodeToString(buffer.copyOf(read)))
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
    }
}
