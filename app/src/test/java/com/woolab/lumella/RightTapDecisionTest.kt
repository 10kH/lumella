package com.woolab.lumella

import org.junit.Assert.assertEquals
import org.junit.Test

class RightTapDecisionTest {

    private fun decide(contact: Long, gap: Long, now: Long = 10_000L, armedUntil: Long = 0L) =
        RightTapRules.decide(contact, gap, now, armedUntil)

    @Test
    fun `a deliberate single tap ends the turn`() {
        assertEquals(RightTap.EndTurn, decide(contact = 120, gap = 5_000))
    }

    @Test
    fun `a 6ms contact is the pad bouncing, not a person`() {
        // Measured on-device: bounces come in at 6ms, deliberate taps at 87-222ms.
        assertEquals(RightTap.Ignore, decide(contact = 6, gap = 30))
        assertEquals(RightTap.EndTurn, decide(contact = 87, gap = 5_000))
    }

    @Test
    fun `a bounce is never mistaken for the second half of a double tap`() {
        // The bounce arrives milliseconds after a real tap, which is exactly the shape of a
        // double tap. Judging by the gap alone would have quit the app.
        assertEquals(RightTap.Ignore, decide(contact = 6, gap = 12, armedUntil = Long.MAX_VALUE))
    }

    @Test
    fun `a double tap asks before quitting`() {
        // Exit is irreversible and hands-free removed the reason to tap at all, so the
        // gesture is now mostly reached by accident.
        assertEquals(RightTap.ArmExit, decide(contact = 100, gap = 200))
    }

    @Test
    fun `a tap inside the confirmation window quits`() {
        val armed = 12_000L
        assertEquals(RightTap.ConfirmExit, decide(contact = 100, gap = 200, now = 11_000, armedUntil = armed))
    }

    @Test
    fun `the confirmation expires rather than lingering`() {
        val armed = 12_000L
        // Same gesture, after the window: asks again instead of quitting on an old intent.
        assertEquals(RightTap.ArmExit, decide(contact = 100, gap = 200, now = 12_001, armedUntil = armed))
    }

    @Test
    fun `an armed exit does not hijack an ordinary later tap`() {
        // Someone who armed exit by accident and then goes back to talking must not quit by
        // tapping to end their next turn.
        assertEquals(
            RightTap.EndTurn,
            decide(contact = 100, gap = 5_000, now = 11_000, armedUntil = 12_000),
        )
    }

    @Test
    fun `a long press is not a tap`() {
        assertEquals(RightTap.Ignore, decide(contact = RightTapRules.MAX_CONTACT_MS, gap = 5_000))
        assertEquals(RightTap.EndTurn, decide(contact = RightTapRules.MAX_CONTACT_MS - 1, gap = 5_000))
    }

    @Test
    fun `quitting always takes at least three deliberate taps`() {
        // Regression guard for the reported "it just closed on me": no two-contact sequence
        // may reach ConfirmExit from an unarmed state.
        var armedUntil = 0L
        var now = 0L
        var confirmed = false
        // A first tap after a pause, then a fast second: the shape of the reported accident.
        val gaps = listOf(5_000L, 150L)
        for (gap in gaps) {
            now += gap
            when (RightTapRules.decide(contactMs = 100, sinceLastTapMs = gap, nowMs = now, exitArmedUntilMs = armedUntil)) {
                RightTap.ArmExit -> armedUntil = now + RightTapRules.EXIT_CONFIRM_WINDOW_MS
                RightTap.ConfirmExit -> confirmed = true
                else -> Unit
            }
        }
        assertEquals(false, confirmed)
    }
}
