package com.woolab.lumella

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleRetentionTest {

    @Test
    fun `the timer armed by a speech start is allowed to clear`() {
        val retention = SubtitleRetention()
        val token = retention.onSpeechStarted()
        assertTrue(retention.mayClear(token))
    }

    @Test
    fun `a new tutor reply invalidates the pending clear`() {
        // The failure this exists to prevent: the wearer asks something, the tutor answers,
        // and eight seconds after the QUESTION a stale timer wipes the ANSWER mid-read.
        val retention = SubtitleRetention()
        val token = retention.onSpeechStarted()
        retention.onNewTutorText()
        assertFalse("the text this timer was armed for is gone", retention.mayClear(token))
    }

    @Test
    fun `a session reset invalidates the pending clear`() {
        // A reconnect blanks the screen itself; a timer from the dead session must not fire
        // into the new one and wipe whatever it is showing.
        val retention = SubtitleRetention()
        val token = retention.onSpeechStarted()
        retention.onSessionReset()
        assertFalse(retention.mayClear(token))
    }

    @Test
    fun `only the newest speech start owns the clear`() {
        // Two utterances inside one window: the first timer is stale, the second is live.
        val retention = SubtitleRetention()
        val first = retention.onSpeechStarted()
        val second = retention.onSpeechStarted()
        assertFalse(retention.mayClear(first))
        assertTrue(retention.mayClear(second))
    }

    @Test
    fun `a stale token never becomes valid again`() {
        val retention = SubtitleRetention()
        val token = retention.onSpeechStarted()
        retention.onNewTutorText()
        retention.onSpeechStarted()
        retention.onSessionReset()
        assertFalse(retention.mayClear(token))
    }

    @Test
    fun `the default window is long enough to read and short enough to pass`() {
        // Pinned so a drive-by tuning change is a visible, deliberate act.
        assertTrue(SubtitleRetention.DEFAULT_RETENTION_MS in 5_000L..15_000L)
    }
}
