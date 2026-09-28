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

    private fun tap(clock: TakeClock, live: Boolean, slackMs: Long = 400) =
        WavTap(File(dir, "t.wav"), RATE, clock, live, { warnings += it }, liveSlackMs = slackMs)

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
        c.firstFrame(0)
        t.write(chunk(100, 1))
        now = 500; c.dark()            // photo turn: stop requested
        now = 700; c.segmentEnded(500) // the recorder counted 500ms
        now = 2_000; c.firstFrame(0)      // next segment's first frame
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
        c.firstFrame(0)
        now = 40; t.write(chunk(40, 1))
        now = 80; t.write(chunk(40, 2))
        now = 90; c.dark()
        now = 100; c.segmentEnded(90)
        now = 120; t.write(chunk(40, 9))
        now = 160; t.write(chunk(40, 9))
        now = 200; c.firstFrame(0)
        now = 240; t.write(chunk(40, 3))
        t.close()

        assertEquals(null, span(9))
        // The first chunk after the gap is placed where the video resumed (90ms, the recorder's
        // length), not straight after the last word before it.
        assertEquals(90..129, span(3))
    }

    @Test
    fun aLateReadTheCaptureBufferCoveredLosesNothingAndShiftsNothing() {
        val c = clock()
        val t = tap(c, live = true, slackMs = 400)
        now = 40; t.write(chunk(40, 1))
        // The loop stalled 360ms. The recorder kept those 360ms; they come out now, together.
        now = 440
        repeat(9) { t.write(chunk(40, 2 + it)) }
        t.close()

        assertEquals("no silence invented for audio the buffer kept", 40..79, span(2))
        assertEquals(360..399, span(10))
    }

    @Test
    fun aStallPastTheCaptureBufferLeavesAHoleOnlyForWhatWasLost() {
        val c = clock()
        val t = tap(c, live = true, slackMs = 400)
        now = 40; t.write(chunk(40, 1))
        // 1000ms late: the buffer held only the last 400ms; the 560ms before them are gone.
        now = 1_040; t.write(chunk(40, 2))
        t.close()

        assertEquals("the oldest kept audio was heard 400ms before now", 640..679, span(2))
    }

    @Test
    fun aFrameThatComesAfterTheGiveUpCutsTheLearnerBackToTheVideo() {
        val c = TakeClock(followVideo = true, giveUpAfterMs = 5_000) { now }
        val learner = tap(c, live = true, slackMs = 400)
        // Continuous 40ms reads. 6s without a frame: given up, the voices follow the wall.
        var t = 5_040L
        while (t <= 7_000L) { now = t; learner.write(chunk(40, 9)); t += 40 }
        now = 7_000; c.firstFrame(0)              // the first frame did come: video time 0
        while (t <= 7_400L) { now = t; learner.write(chunk(40, 1)); t += 40 }
        learner.close()

        assertEquals("speech from before the video is cut", null, span(9))
        assertEquals("what follows lines up with the video", 0..399, span(1))
    }

    @Test
    fun aFrameThatComesAfterTheGiveUpCutsTheTutorBackToo() {
        val c = TakeClock(followVideo = true, giveUpAfterMs = 5_000) { now }
        val tutor = tap(c, live = false)
        now = 6_000; tutor.write(chunk(100, 9))   // given up: lands at 6000
        now = 7_000; c.firstFrame(0)
        now = 8_000; tutor.write(chunk(100, 1))   // video time 1000
        tutor.close()

        assertEquals(null, span(9))
        assertEquals(1_000..1_099, span(1))
    }

    @Test
    fun theCutReachesBackToTheResumeEvenWhenTheNextWriteIsLater() {
        val c = TakeClock(followVideo = true, giveUpAfterMs = 5_000) { now }
        val tutor = tap(c, live = false)
        now = 6_000; tutor.write(chunk(1_000, 9)) // given up: 6000..6999, no video under it
        now = 7_000; c.firstFrame(0)              // video resumes at 0
        now = 14_500; tutor.write(chunk(100, 1))  // video time 7500, past the old end of the file
        tutor.close()

        assertEquals("the reply with no video under it is gone", null, span(9))
        assertEquals(7_500..7_599, span(1))
    }

    @Test
    fun aTapThatSleptThroughTwoStepBacksCutsToTheFirst() {
        val c = TakeClock(followVideo = true, giveUpAfterMs = 5_000) { now }
        val tutor = tap(c, live = false)
        now = 6_000; tutor.write(chunk(100, 9))   // given up: 6000..6099, no video under it
        now = 7_000; c.firstFrame(0)              // step back 1: video resumes at 0
        now = 17_000; c.dark()                    // a photo turn after 10s of video, tutor silent
        now = 17_100; c.segmentEnded(10_000)
        now = 22_500; c.stamp()                   // the learner's reads keep stamping: given up again
        now = 23_000; c.firstFrame(0)             // step back 2: video resumes at 10000
        now = 24_000; tutor.write(chunk(100, 1))  // video time 11000
        tutor.close()

        assertEquals("cut back to the first step back, not the second", null, span(9))
        assertEquals(11_000..11_099, span(1))
    }

    @Test
    fun aGivenUpClockStaysOnTheWallWhenAFramelessSegmentEnds() {
        val c = TakeClock(followVideo = true, giveUpAfterMs = 5_000) { now }
        val tutor = tap(c, live = false)
        now = 5_500; tutor.write(chunk(100, 1))   // given up at 5s: wall time
        now = 6_000; c.segmentEnded(0)            // the segment ends with no frame in it
        c.noVideo()                               // and no resume is coming
        now = 6_500; tutor.write(chunk(100, 2))
        tutor.close()

        assertEquals(5_500..5_599, span(1))
        assertEquals("no step back to the empty video", 6_500..6_599, span(2))
    }

    @Test
    fun aTakeWithACameraWaitsForTheFirstFrameNotTheStartEvent() {
        val c = TakeClock(followVideo = true) { now }
        val learner = tap(c, live = true)
        // The recorder's Start event would be here, ~1.5s before any frame is in the file.
        now = 40; learner.write(chunk(40, 9))
        now = 1_500; c.firstFrame(0)              // first Status: data is in the file
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
        c.firstFrame(0)
        now = 1_000; c.dark()              // stop requested at 1000ms of video...
        now = 1_200; c.segmentEnded(1_150) // ...but frames kept landing until 1150ms
        now = 3_000; c.firstFrame(0)
        t.write(chunk(100, 1))
        t.close()

        assertEquals("the next segment starts where the video's does", 1_150..1_249, span(1))
    }

    @Test
    fun aVideoThatNeverShowsAFrameIsGivenUpOnAndTheVoicesKeepTheirTime() {
        val clockWarnings = mutableListOf<String>()
        val c = TakeClock(followVideo = true, giveUpAfterMs = 5_000, warn = { clockWarnings += it }) { now }
        val t = tap(c, live = false)
        now = 2_000; t.write(chunk(100, 9))   // camera still "coming up": dark
        now = 6_000; t.write(chunk(100, 1))   // 6s without a frame: rolls from the start
        t.close()

        assertEquals(6_000..6_099, span(1))
        assertEquals(1, clockWarnings.size)
    }

    @Test
    fun aRecordingThatEndsByItselfLetsTheVoicesCarryOn() {
        val c = TakeClock(followVideo = true) { now }
        val t = tap(c, live = true)
        c.firstFrame(0)
        now = 1_000; c.segmentEnded(1_000) // no stop was asked for: the camera went away
        c.noVideo()                         // what GlassesCamera does when no resume is pending
        now = 1_040; t.write(chunk(40, 1))
        t.close()

        assertEquals(1_000..1_039, span(1))
    }

    @Test
    fun onlyTheFirstFrameOfASegmentAnchorsTheClock() {
        val c = TakeClock(followVideo = true) { now }
        now = 100; c.firstFrame(0)
        now = 600; c.firstFrame(480) // every later Status is ignored
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
        val t = WavTap(File(dir, "t.wav"), RATE, c, false, { warnings += it }, executor = DirectExecutor())
        repeat(25) { now = it * 100L; t.write(chunk(100, 1)) } // 2.5s, never closed

        val h = ByteBuffer.wrap(File(dir, "t.wav").readBytes(), 0, WavTap.HEADER_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        val riff = h.getInt(4)
        val data = h.getInt(40)
        assertEquals("patched at the last whole second", 2_000 * 2, data)
        assertEquals(36 + data, riff)
    }

    @Test
    fun aStampFromBeforeAStepBackArrivingAfterItIsHarmless() {
        val c = TakeClock(followVideo = true, giveUpAfterMs = 5_000) { now }
        val held = HoldingExecutor()
        val tutor = WavTap(File(dir, "t.wav"), RATE, c, false, { warnings += it }, executor = held)
        now = 6_000; tutor.write(chunk(100, 1))   // stamped in epoch 0
        now = 7_000; c.firstFrame(0)              // step back
        now = 8_000; tutor.write(chunk(100, 2))   // stamped in epoch 1
        held.runInReverse()                       // the writer sees epoch 1 first, then epoch 0
        now = 9_000; tutor.close()                // video time 2000
        held.runInReverse()

        assertEquals("the later stamp is placed on the video's clock", 1_000..1_099, span(2))
        assertEquals("the stale one, heard before the video resumed, is dropped — no crash", null, span(1))
        assertEquals("and the file ends where the video does", 2_000, samples().size)
    }

    /** Holds queued writes so a test can run them out of order. */
    private class HoldingExecutor : java.util.concurrent.AbstractExecutorService() {
        private val queue = ArrayList<Runnable>()
        @Volatile private var shut = false
        override fun execute(command: Runnable) {
            if (shut) throw java.util.concurrent.RejectedExecutionException()
            queue += command
        }
        fun runInReverse() {
            val batch = queue.reversed()
            queue.clear()
            batch.forEach { it.run() }
        }
        override fun shutdown() { shut = true }
        override fun shutdownNow(): MutableList<Runnable> { shut = true; return mutableListOf() }
        override fun isShutdown() = shut
        override fun isTerminated() = shut && queue.isEmpty()
        override fun awaitTermination(timeout: Long, unit: java.util.concurrent.TimeUnit) = true
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
