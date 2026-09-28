package com.woolab.lumella.audio

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The taps exist so a take's voices line up with its video in the edit. These pin where each
 * chunk lands, in milliseconds of file, for the two kinds of voice and across dark stretches.
 * 1kHz keeps the arithmetic readable: 1ms = 2 bytes.
 */
class WavTapTest {
    private var now = 0L
    private lateinit var dir: File
    private val warnings = mutableListOf<String>()

    @Before
    fun setUp() {
        dir = java.nio.file.Files.createTempDirectory("wavtap").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    /** A take without a camera: the wall clock. */
    private fun clock() = TakeClock(followVideo = false) { now }

    private fun tap(clock: TakeClock, live: Boolean) =
        WavTap(File(dir, "t.wav"), RATE, clock, live, { warnings += it })

    /** [ms] of audio, every sample set to [mark] so its position can be found afterwards. */
    private fun chunk(ms: Int, mark: Int): ByteArray {
        val b = ByteBuffer.allocate(ms * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(ms) { b.putShort(mark.toShort()) }
        return b.array()
    }

    private fun samples(): ShortArray {
        val bytes = File(dir, "t.wav").readBytes()
        val bb = ByteBuffer.wrap(bytes, WavTap.HEADER_BYTES, bytes.size - WavTap.HEADER_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray((bytes.size - WavTap.HEADER_BYTES) / 2) { bb.short }
    }

    /** First and last millisecond holding [mark], or null. */
    private fun span(mark: Int): IntRange? {
        val s = samples()
        val first = s.indexOfFirst { it.toInt() == mark }
        if (first < 0) return null
        return first..s.indexOfLast { it.toInt() == mark }
    }

    @Test
    fun tutorSilenceBetweenRepliesIsPaddedToTheVideosClock() {
        val c = clock()
        val t = tap(c, live = false)
        t.write(chunk(100, 1))
        now = 1_000
        t.write(chunk(100, 2))
        now = 1_500
        t.close()

        assertEquals(0..99, span(1))
        assertEquals(1_000..1_099, span(2))
        assertEquals("ends level with the video", 1_500, samples().size)
    }

    @Test
    fun tutorBurstFasterThanRealTimeIsNeverTrimmed() {
        val c = clock()
        val t = tap(c, live = false)
        repeat(5) { t.write(chunk(100, it + 1)) } // a whole reply arriving at once
        now = 200
        t.close()

        assertEquals(400..499, span(5))
        assertEquals("the reply is kept whole, not cut to the clock", 500, samples().size)
    }

    @Test
    fun aPhotoTurnsGapIsNotCountedAsRecordedTime() {
        val c = TakeClock(followVideo = true) { now }
        val t = tap(c, live = false)
        c.rolling(0)
        t.write(chunk(100, 1))
        now = 500; c.dark()            // photo turn: stop requested
        now = 700; c.segmentEnded(500) // the recorder counted 500ms
        now = 2_000; c.rolling(0)      // next segment's first frame
        now = 2_500
        t.write(chunk(100, 2))
        t.close()

        // 2500ms of wall time, 1500ms of it dark: the video is at 1000ms.
        assertEquals(1_000..1_099, span(2))
    }

    @Test
    fun learnerChunksAreWrittenBackToBackDespiteArrivalJitter() {
        val c = clock()
        val t = tap(c, live = true)
        // 40ms chunks arriving at jittered times, as a real read loop delivers them.
        for ((i, at) in listOf(40L, 75L, 125L, 160L).withIndex()) {
            now = at
            t.write(chunk(40, i + 1))
        }
        t.close()

        assertEquals(0..39, span(1))
        assertEquals(40..79, span(2))
        assertEquals(80..119, span(3))
        assertEquals(120..159, span(4))
        assertTrue("no silence padded between chunks", samples().none { it.toInt() == 0 })
    }

    @Test
    fun learnerAudioHeardWhileTheVideoWasDarkIsDropped() {
        val c = TakeClock(followVideo = true) { now }
        val t = tap(c, live = true)
        c.rolling(0)
        now = 40; t.write(chunk(40, 1))
        now = 80; t.write(chunk(40, 2))
        now = 90; c.dark()
        now = 100; c.segmentEnded(90)
        now = 120; t.write(chunk(40, 9))
        now = 160; t.write(chunk(40, 9))
        now = 200; c.rolling(0)
        now = 240; t.write(chunk(40, 3))
        t.close()

        assertEquals(null, span(9))
        assertEquals(80..119, span(3))
    }

    @Test
    fun learnerCaptureStallLongerThanTheSlackIsPaddedNotSqueezed() {
        val c = clock()
        val t = tap(c, live = true)
        now = 40; t.write(chunk(40, 1))
        now = 440; t.write(chunk(40, 2)) // 360ms the mic never delivered
        t.close()

        assertEquals("the next words land where the video has them", 400..439, span(2))
    }

    @Test
    fun aTakeWithACameraWaitsForTheFirstFrameNotTheStartEvent() {
        val c = TakeClock(followVideo = true) { now }
        val learner = tap(c, live = true)
        // The recorder's Start event would be here, ~1.5s before any frame is in the file.
        now = 40; learner.write(chunk(40, 9))
        now = 1_500; c.rolling(0)              // first Status: data is in the file
        now = 1_540; learner.write(chunk(40, 1))
        now = 1_800
        learner.close()

        assertEquals(null, span(9))
        assertEquals(0..39, span(1))
        assertEquals("the file is as long as the video, not the wall", 300, samples().size)
    }

    @Test
    fun theRecordersOwnSegmentLengthCorrectsTheEstimate() {
        val c = TakeClock(followVideo = true) { now }
        val t = tap(c, live = false)
        c.rolling(0)
        now = 1_000; c.dark()              // stop requested at 1000ms of video...
        now = 1_200; c.segmentEnded(1_150) // ...but frames kept landing until 1150ms
        now = 3_000; c.rolling(0)
        t.write(chunk(100, 1))
        t.close()

        assertEquals("the next segment starts where the video's does", 1_150..1_249, span(1))
    }

    @Test
    fun onlyTheFirstFrameOfASegmentAnchorsTheClock() {
        val c = TakeClock(followVideo = true) { now }
        now = 100; c.rolling(0)
        now = 600; c.rolling(480) // every later Status is ignored
        assertEquals(500L, c.stamp().elapsedMs)
    }

    @Test
    fun headerDescribesMonoPcm16AndTheDataWritten() {
        val c = clock()
        val t = tap(c, live = false)
        t.write(chunk(250, 1))
        t.close()

        val h = ByteBuffer.wrap(File(dir, "t.wav").readBytes(), 0, WavTap.HEADER_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        val tag = ByteArray(4)
        h.get(tag); assertEquals("RIFF", String(tag))
        assertEquals(36 + 500, h.int)
        h.get(tag); assertEquals("WAVE", String(tag))
        h.position(22)
        assertEquals(1, h.short.toInt())       // channels
        assertEquals(RATE, h.int)               // sample rate
        h.position(34)
        assertEquals(16, h.short.toInt())      // bits
        h.position(40)
        assertEquals(500, h.int)                // data bytes
    }

    @Test
    fun writesAfterCloseAreIgnored() {
        val c = clock()
        val t = tap(c, live = false)
        t.write(chunk(10, 1))
        t.close()
        t.write(chunk(10, 2))
        t.close()

        assertEquals(null, span(2))
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun aTapKilledMidTakeStillHasAReadableHeader() {
        // Direct executor: the file is inspected while the tap is still open, as a kill leaves it.
        val c = clock()
        val t = WavTap(File(dir, "t.wav"), RATE, c, false, { warnings += it }, DirectExecutor())
        repeat(25) { now = it * 100L; t.write(chunk(100, 1)) } // 2.5s, never closed

        val h = ByteBuffer.wrap(File(dir, "t.wav").readBytes(), 0, WavTap.HEADER_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        val riff = h.getInt(4)
        val data = h.getInt(40)
        assertEquals("patched at the last whole second", 2_000 * 2, data)
        assertEquals(36 + data, riff)
    }

    /** Runs each write inline, so the file can be read mid-take. */
    private class DirectExecutor : java.util.concurrent.AbstractExecutorService() {
        @Volatile private var shut = false
        override fun execute(command: Runnable) {
            if (shut) throw java.util.concurrent.RejectedExecutionException()
            command.run()
        }
        override fun shutdown() { shut = true }
        override fun shutdownNow(): MutableList<Runnable> { shut = true; return mutableListOf() }
        override fun isShutdown() = shut
        override fun isTerminated() = shut
        override fun awaitTermination(timeout: Long, unit: java.util.concurrent.TimeUnit) = true
    }

    private companion object {
        const val RATE = 1_000
    }
}
