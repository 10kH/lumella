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
 *    scheduling jitter at every chunk, which is a click every 40ms. The clock is consulted to
 *    drop what was heard while the video was dark, to place the first chunk after a dark
 *    stretch (where the video resumes), and to leave a hole where audio was really lost. A
 *    late read loses nothing until it is later than the capture buffer ([liveSlackMs]) — the
 *    buffered audio comes out on the next reads — so only a stall past that is padded, and only
 *    by the part the buffer could not hold.
 *
 * **When the clock steps back** — a video that came after the clock gave up on it
 * ([TakeClock.Stamp.epoch]) — both taps cut the file back to the new position and carry on from
 * there. What they wrote in between was voices with no video under them, placed ahead of a video
 * that now starts; keeping it would leave the whole rest of the take out of step.
 *
 * **The header is kept current**, once per second of audio ([HEADER_EVERY_MS]). It used to be written only on
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
    /** Live taps: how much audio the source buffers behind a late read. */
    private val liveSlackMs: Long = 0L,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "lumella-wav-tap").apply { isDaemon = true }
    },
) {
    private val name = file.name
    private val out: RandomAccessFile? = try {
        RandomAccessFile(file, "rw").apply {
            setLength(0)
            write(ByteArray(HEADER_BYTES)) // sizes patched every HEADER_EVERY_MS and on close
        }
    } catch (e: IOException) {
        warn("tap $name: cannot open: ${e.message}")
        null
    }

    // Writer thread only.
    private var dataBytes = 0L
    private var headerBytes = 0L
    /** Live taps: the next chunk follows a dark stretch (or is the first), so the clock places it. */
    private var resync = true
    /** The clock's step-back count this file was written under. */
    private var epoch = 0
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

    /**
     * Closed and nothing left to write. False while a writer that outlived [close]'s wait still
     * holds the file — a new tap on the same name must not truncate it under that writer.
     */
    val drained: Boolean get() = closed && executor.isTerminated

    private fun append(at: TakeClock.Stamp, pcm: ByteArray) {
        val raf = out ?: return
        if (broken || finished) return
        try {
            stepBackIfNeeded(raf, at)
            if (live) {
                if (at.dark) {
                    resync = true
                    return
                }
                val now = bytesAt(at.elapsedMs)
                if (resync) {
                    // Where this chunk would start if it had just been heard.
                    padTo(raf, now - pcm.size)
                    resync = false
                } else {
                    // The oldest audio the source still held when this read came back. Past the
                    // end of the file means the stall outlasted the buffer: what came between is
                    // gone, and this chunk starts there.
                    val oldestKept = now - bytesAt(liveSlackMs)
                    if (oldestKept > dataBytes) padTo(raf, oldestKept)
                }
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
            if (!broken) {
                stepBackIfNeeded(raf, at)
                padTo(raf, bytesAt(at.elapsedMs))
            }
            raf.seek(0)
            raf.write(header(dataBytes))
        } catch (e: IOException) {
            warn("tap $name: finalise failed: ${e.message}")
        } finally {
            runCatching { raf.close() }
        }
    }

    /**
     * The clock stepped back since the last write: cut the file back to where the video resumed.
     * Not to the current position — by the next write the clock may have run past the old end of
     * the file, and nothing would be cut. The usual placement then fills up to now.
     */
    private fun stepBackIfNeeded(raf: RandomAccessFile, at: TakeClock.Stamp) {
        if (at.epoch == epoch) return
        // The first step back this file has not seen: nothing after it had video under it.
        val target = bytesAt(at.stepBacks[epoch])
        epoch = at.epoch
        resync = true
        if (target >= dataBytes) return
        warn("tap $name: clock stepped back ${(dataBytes - target) / 2 * 1000 / sampleRateHz}ms; cutting the file back")
        raf.setLength(HEADER_BYTES + target)
        raf.seek(HEADER_BYTES + target)
        dataBytes = target
        headerBytes = 0L
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
        /** How much audio a kill mid-take can leave outside the header's count. */
        const val HEADER_EVERY_MS = 1_000L
        private const val CLOSE_WAIT_MS = 2_000L
        private val SILENCE = ByteArray(16 * 1024)
    }
}
