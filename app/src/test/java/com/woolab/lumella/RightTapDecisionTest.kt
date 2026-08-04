package com.woolab.lumella

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun `the window really is the advertised length, not the double-tap gap`() {
        // The screen says "한 번 더 누르면 종료". A human reaction is 500-800ms, which is well
        // outside the 400ms double-tap gap — so if the armed check sits below that gap the
        // prompt is a lie and the tap becomes an ordinary turn.
        val armedUntil = 10_000L + RightTapRules.EXIT_CONFIRM_WINDOW_MS
        for (reactionMs in listOf(500L, 800L, 1_500L, 2_400L)) {
            assertEquals(
                "a tap ${reactionMs}ms after the prompt must still confirm",
                RightTap.ConfirmExit,
                decide(contact = 100, gap = reactionMs, now = 10_000 + reactionMs, armedUntil = armedUntil),
            )
        }
    }

    @Test
    fun `the confirmation expires rather than lingering`() {
        val armed = 12_000L
        // Same gesture, after the window: asks again instead of quitting on an old intent.
        assertEquals(RightTap.ArmExit, decide(contact = 100, gap = 200, now = 12_001, armedUntil = armed))
    }

    @Test
    fun `an armed exit does not outlive its window`() {
        // Someone who armed exit by accident and then goes back to talking must not quit by
        // tapping to end their next turn. The caller clears the arm on EndTurn; past the
        // deadline the rules refuse on their own.
        assertEquals(
            RightTap.EndTurn,
            decide(contact = 100, gap = 5_000, now = 13_000, armedUntil = 12_000),
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

    // --- Exhaustive-ish state walk (regression guard, item 5): no reachable sequence of up
    // to four taps with fewer than three deliberate (accepted, non-bounce, non-long-press)
    // contacts may reach ConfirmExit. Contact classes: bounce, boundary-low-deliberate,
    // mid-deliberate, boundary-high-deliberate. Gap classes: fast (double-tap window) and
    // slow (ordinary turn gap). armedUntil/lastAcceptedGap are threaded exactly the way the
    // one production caller (MainActivity#dispatchTouchEvent) threads them: EndTurn/ArmExit
    // update the "since last accepted tap" clock, Ignore does not (a bounce is never recorded
    // as a tap), and a cold session's very first tap is always compared against a same-huge
    // gap (lastRightTapTimeMs starts at 0, real device time does not).
    @Test
    fun `no sequence of up to four taps with fewer than three deliberate contacts reaches ConfirmExit`() {
        val contacts = listOf(6L, RightTapRules.MIN_CONTACT_MS, 120L, RightTapRules.MAX_CONTACT_MS - 1)
        val gaps = listOf(150L, RightTapRules.DOUBLE_TAP_INTERVAL_MS, 1_000L)

        val combos = contacts.flatMap { c -> gaps.map { g -> c to g } }

        fun sequencesUpTo(maxLen: Int): List<List<Pair<Long, Long>>> {
            var frontier: List<List<Pair<Long, Long>>> = listOf(emptyList())
            val all = mutableListOf<List<Pair<Long, Long>>>()
            repeat(maxLen) {
                val next = mutableListOf<List<Pair<Long, Long>>>()
                for (seq in frontier) {
                    for (combo in combos) {
                        val extended = seq + combo
                        all.add(extended)
                        next.add(extended)
                    }
                }
                frontier = next
            }
            return all
        }

        var checked = 0
        for (sequence in sequencesUpTo(4)) {
            var now = 0L
            var armedUntil = 0L
            // A cold session's first tap has no real predecessor: lastRightTapTimeMs starts
            // at 0 in MainActivity while nowMs is real device time, so the gap is enormous.
            var sinceLastAccepted = Long.MAX_VALUE / 2
            var deliberateCount = 0
            for ((contact, gap) in sequence) {
                now += gap
                sinceLastAccepted += gap
                val result = RightTapRules.decide(contact, sinceLastAccepted, now, armedUntil)
                when (result) {
                    RightTap.Ignore -> Unit // not recorded as a tap
                    RightTap.EndTurn -> {
                        deliberateCount++
                        armedUntil = 0L
                        sinceLastAccepted = 0L
                    }
                    RightTap.ArmExit -> {
                        deliberateCount++
                        armedUntil = now + RightTapRules.EXIT_CONFIRM_WINDOW_MS
                        sinceLastAccepted = 0L
                    }
                    RightTap.ConfirmExit -> {
                        deliberateCount++
                        sinceLastAccepted = 0L
                        assertTrue(
                            "reached ConfirmExit after only $deliberateCount deliberate contact(s): $sequence",
                            deliberateCount >= 3,
                        )
                    }
                }
            }
            checked++
        }
        assertTrue("the enumeration must actually run sequences", checked > 0)
    }
}
