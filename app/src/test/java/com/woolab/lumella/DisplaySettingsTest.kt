package com.woolab.lumella

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplaySettingsTest {

    @Test
    fun `the default state is the pre-existing look`() {
        val s = DisplaySettings().current()
        assertTrue(s.subtitleVisible)
        assertEquals(DisplaySettings.SubtitleSize.LARGE, s.subtitleSize)
        assertTrue(s.echoVisible)
        assertTrue(s.hintsVisible)
    }

    @Test
    fun `small shrinks the tutor text AND hides the echo - the coupling is the requirement`() {
        val s = DisplaySettings().applyTextMode("small")!!
        assertTrue(s.subtitleVisible)
        assertEquals(DisplaySettings.SubtitleSize.SMALL, s.subtitleSize)
        assertFalse("작게 hides the learner echo too", s.echoVisible)
    }

    @Test
    fun `off hides everything textual, on restores the full default`() {
        val settings = DisplaySettings()
        val off = settings.applyTextMode("off")!!
        assertFalse(off.subtitleVisible)
        assertFalse(off.echoVisible)

        val on = settings.applyTextMode("on")!!
        assertTrue(on.subtitleVisible)
        assertEquals("켜줘 restores the DEFAULT, not the last combination", DisplaySettings.SubtitleSize.LARGE, on.subtitleSize)
        assertTrue(on.echoVisible)
    }

    @Test
    fun `text commands leave the hints alone, and vice versa`() {
        val settings = DisplaySettings()
        settings.applyHintsVisible(false)
        val s = settings.applyTextMode("small")!!
        assertFalse("텍스트 작게 must not resurrect hidden hints", s.hintsVisible)

        val t = settings.applyHintsVisible(true)
        assertEquals("설명 켜줘 must not change text size", DisplaySettings.SubtitleSize.SMALL, t.subtitleSize)
        assertFalse(t.echoVisible)
    }

    @Test
    fun `an unknown mode is refused, not guessed`() {
        val settings = DisplaySettings()
        settings.applyTextMode("small")
        assertNull(settings.applyTextMode("tiny"))
        assertEquals(
            "a refused mode must not disturb the current state",
            DisplaySettings.SubtitleSize.SMALL,
            settings.current().subtitleSize,
        )
    }

    @Test
    fun `off remembers nothing about size on purpose`() {
        // 꺼줘 then 켜줘 lands on the default large view: the wearer who turned text off and
        // on again asked for "text", not for a memory test.
        val settings = DisplaySettings()
        settings.applyTextMode("small")
        settings.applyTextMode("off")
        val on = settings.applyTextMode("on")!!
        assertEquals(DisplaySettings.SubtitleSize.LARGE, on.subtitleSize)
        assertTrue(on.echoVisible)
    }
}
