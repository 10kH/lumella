package com.woolab.lumella.orchestration

import com.woolab.lumella.state.Correction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** AC4: staleness guard never delivers a correction verbatim when age > K. */
class StalenessGuardTest {

    private fun correction(turnId: Int) =
        Correction(text = "fix", priority = 2, sourceAgent = "grammar", turnId = turnId)

    private val guard = StalenessGuard(maxAgeTurns = 2, reAnchorWindowTurns = 4)

    @Test
    fun freshCorrectionWithinKIsDelivered() {
        val d = guard.evaluate(listOf(correction(turnId = 8)), currentTurnId = 9).single()
        assertEquals(StalenessOutcome.DELIVER, d.outcome)
        assertEquals(1, d.ageAtDecision)
        assertEquals(1, d.delivered?.age)
    }

    @Test
    fun staleWithinWindowIsReAnchoredNotVerbatim() {
        val d = guard.evaluate(listOf(correction(turnId = 4)), currentTurnId = 8).single() // age 4 == window
        assertEquals(StalenessOutcome.RE_ANCHOR, d.outcome)
        assertEquals(4, d.ageAtDecision)
        // Re-anchored: delivered with age reset to 0 and moved to current turn (NOT verbatim age 4).
        assertEquals(0, d.delivered?.age)
        assertEquals(8, d.delivered?.turnId)
    }

    @Test
    fun beyondWindowIsDropped() {
        val d = guard.evaluate(listOf(correction(turnId = 1)), currentTurnId = 9).single() // age 8 > 4
        assertEquals(StalenessOutcome.DROP, d.outcome)
        assertNull(d.delivered)
    }

    @Test
    fun noDeliveredCorrectionHasAgeAboveK() {
        val corrections = (1..10).map { correction(turnId = it) }
        val decisions = guard.evaluate(corrections, currentTurnId = 12)
        for (d in decisions) {
            d.delivered?.let {
                assertTrue("delivered correction must not carry age > K", it.age <= 2)
            }
        }
    }

    @Test
    fun metricRecordsOutcomesAndAges() {
        val metric = StalenessMetric()
        // ages at turn 9: 9-8=1 (DELIVER), 9-5=4==window (RE_ANCHOR), 9-1=8>window (DROP)
        metric.record(guard.evaluate(listOf(correction(8), correction(5), correction(1)), currentTurnId = 9))
        assertEquals(3, metric.totalEvaluated())
        assertEquals(1, metric.count(StalenessOutcome.DELIVER))
        assertEquals(1, metric.count(StalenessOutcome.RE_ANCHOR))
        assertEquals(1, metric.count(StalenessOutcome.DROP))
        assertEquals(setOf(1, 4, 8), metric.ageHistogram().keys)
    }
}
