package com.woolab.lumella.audio

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * One voice of a take as a mono PCM16 WAV, kept on the take's clock ([TakeClock]) and written
 * on its own thread.
 *
 * **Why its own thread.** The first tap wrote to disk inline, on the websocket thread that also
 * feeds the speaker. Measured 2026-09-28 (artifacts/perf) a single tap write blocked up to 66ms —
 * inside an 80ms playback buffer, while the POV encoder was flushing to the same storage — and
 * the replies where that happened were the ones that underran. The audio thread now stamps the
 * chunk and queues it; the disk is someone else's problem.
 *
 * **Two kinds of voice, two rules** ([live]):
 *  - The tutor arrives in bursts, faster than real time, with silences between replies. Each
 *    chunk is placed where the video was when it arrived, padding the silence before it. Nothing
 *    is ever trimmed: a reply that plays long pushes the file ahead and the next silence absorbs
 *    it. (The first version wrote only speech, and a 172.3s take gave a 68.7s file.)
 *  - The learner is the microphone: continuous and real time. Its chunks are written back to
 *    back. Re-placing each one by its arrival time would pad or cut a few milliseconds of
 *    scheduling jitter at every chunk, which is a click every 40ms. The clock is consulted only
 *    to drop what was heard while the video was dark, and to pad over a capture stall longer
 *    than [LIVE_SLACK_MS].
 *
 * **The header is kept current**, once per second of audio. It used to be written only on
 * close, so an app killed mid-take left `RIFF` and `data` sizes of 0 and ffprobe refused the
 * file outright ("Invalid data found", measured 2026-09-28) — the voices lost along with the
 * video, whose moov atom a kill also never writes. Now at most the last second is unaccounted.
 *
 * Failures are reported once through [warn] and never reach the caller: a broken tap must not
 * interrupt the conversation being filmed.
 */
class WavTap(
    file: File,
    private val sampleRateHz: Int,
    private val clock: TakeClock,
    private val live: Boolean,
    private val warn: (String) -> Unit,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "lumella-wav-tap").apply { isDaemon = true }
    },
) {
    private val name = file.name
    private val out: RandomAccessFile? = try {
        RandomAccessFile(file, "rw").apply {
            setLength(0)
            write(ByteArray(HEADER_BYTES)) // sizes are patched on close
        }
    } catch (e: IOException) {
        warn("tap $name: cannot open: ${e.message}")
        null
    }

    // Writer thread only.
    private var dataBytes = 0L
    private var headerBytes = 0L
    private var broken = false
    private var finished = false

    @Volatile private var closed = false

    /**
     * Queues [pcm] (PCM16 mono at [sampleRateHz]) stamped with the take's clock. Returns at once.
     * The caller must not reuse the array.
     */
    fun write(pcm: ByteArray) {
        if (closed || out == null) return
        val at = clock.stamp()
        try {
            executor.execute { append(at, pcm) }
        } catch (_: RejectedExecutionException) {
            // Raced with close(); the take is over.
        }
    }

    /**
     * Pads the file to the take's end so it is level with the video, finalises the header and
     * closes. Waits for queued chunks, bounded. Idempotent.
     */
    fun close() {
        if (closed) return
        closed = true
        val at = clock.stamp()
        try {
            executor.execute { finish(at) }
        } catch (_: RejectedExecutionException) {
            // Already shut down.
        }
        executor.shutdown()
        if (!executor.awaitTermination(CLOSE_WAIT_MS, TimeUnit.MILLISECONDS)) {
            warn("tap $name: writer did not drain within ${CLOSE_WAIT_MS}ms; header may be stale")
        }
    }

    private fun append(at: TakeClock.Stamp, pcm: ByteArray) {
        val raf = out ?: return
        if (broken || finished) return
        try {
            if (live) {
                if (at.dark) return
                // Where this chunk would start if it ended now. Behind that by more than the
                // slack means the capture stalled and the mic lost that stretch: leave the hole
                // as silence so what follows stays on the video's clock.
                val chunkStart = bytesAt(at.elapsedMs) - pcm.size
                if (chunkStart - dataBytes > bytesAt(LIVE_SLACK_MS)) padTo(raf, chunkStart)
            } else {
                padTo(raf, bytesAt(at.elapsedMs))
            }
            raf.write(pcm)
            dataBytes += pcm.size
            if (dataBytes - headerBytes >= bytesAt(HEADER_EVERY_MS)) {
                val end = raf.filePointer
                raf.seek(0)
                raf.write(header(dataBytes))
                raf.seek(end)
                headerBytes = dataBytes
            }
        } catch (e: IOException) {
            broken = true
            warn("tap $name: write failed, tap stopped: ${e.message}")
        }
    }

    private fun finish(at: TakeClock.Stamp) {
        val raf = out ?: return
        if (finished) return
        finished = true
        try {
            if (!broken) padTo(raf, bytesAt(at.elapsedMs))
            raf.seek(0)
            raf.write(header(dataBytes))
        } catch (e: IOException) {
            warn("tap $name: finalise failed: ${e.message}")
        } finally {
            runCatching { raf.close() }
        }
    }

    /** Byte offset of [ms] of audio; always a whole sample. */
    private fun bytesAt(ms: Long): Long = (ms * sampleRateHz / 1000L) * 2L

    /** Writes silence up to [target]. Never trims. */
    private fun padTo(raf: RandomAccessFile, target: Long) {
        var remaining = target - dataBytes
        while (remaining > 0) {
            val n = minOf(remaining, SILENCE.size.toLong()).toInt()
            raf.write(SILENCE, 0, n)
            dataBytes += n
            remaining -= n
        }
    }

    private fun header(dataBytes: Long): ByteArray {
        val data = dataBytes.coerceAtMost(Int.MAX_VALUE - 36L).toInt()
        return ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + data)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)                 // PCM chunk size
            putShort(1)                // PCM
            putShort(1)                // mono
            putInt(sampleRateHz)
            putInt(sampleRateHz * 2)   // byte rate, 16-bit mono
            putShort(2)                // block align
            putShort(16)               // bits per sample
            put("data".toByteArray())
            putInt(data)
        }.array()
    }

    companion object {
        const val HEADER_BYTES = 44
        /** A capture gap below this is scheduling jitter, not lost audio. */
        const val LIVE_SLACK_MS = 100L
        /** How much audio a kill mid-take can leave outside the header's count. */
        const val HEADER_EVERY_MS = 1_000L
        private const val CLOSE_WAIT_MS = 2_000L
        private val SILENCE = ByteArray(16 * 1024)
    }
}
