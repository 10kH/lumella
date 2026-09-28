package com.woolab.lumella.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class GlassesCameraSegmentNameTest {
    @Test
    fun aPlainNameGetsItsSecondSegment() {
        assertEquals("take-2.mp4", GlassesCamera.nextSegmentName("take.mp4"))
    }

    @Test
    fun segmentsCountUp() {
        assertEquals("take-3.mp4", GlassesCamera.nextSegmentName("take-2.mp4"))
        assertEquals("take-10.mp4", GlassesCamera.nextSegmentName("take-9.mp4"))
    }

    @Test
    fun aHyphenatedNameKeepsItsTail() {
        // The bug: "perf-baseline-pov" became "perf-baseline-2", and take.sh never found it.
        assertEquals("perf-baseline-pov-2.mp4", GlassesCamera.nextSegmentName("perf-baseline-pov.mp4"))
        assertEquals("six-sentences-2.mp4", GlassesCamera.nextSegmentName("six-sentences.mp4"))
        assertEquals("six-sentences-3.mp4", GlassesCamera.nextSegmentName("six-sentences-2.mp4"))
    }

    @Test
    fun aNumericNameIsNotMistakenForASegmentIndex() {
        // No hyphen: "2026" is the name, not segment 2026.
        assertEquals("2026-2.mp4", GlassesCamera.nextSegmentName("2026.mp4"))
    }
}
