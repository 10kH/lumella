package com.woolab.lumella.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Exhaustiveness and evidence-shape tests for the W-1 / D-4 contract:
 * [SteeringResult] must be exhaustively handled (sealed) and an
 * `Unavailable(COACH_UNSUPPORTED)` result must be distinguishable from any
 * other outcome and must carry its reason.
 */
class SteeringResultTest {

    private fun describe(result: SteeringResult): String = when (result) {
        is SteeringResult.Available -> "available:${result.evidence.corrections.size}"
        is SteeringResult.Unavailable -> "unavailable:${result.reason}"
    }

    @Test
    fun `sealed hierarchy is exhaustively switchable over both variants`() {
        val available = SteeringResult.Available(
            SteeringEvidence(
                corrections = emptyList(),
                hints = emptyList(),
                confidence = 0.0,
                sourceTurnId = 1
            )
        )
        val unavailable = SteeringResult.Unavailable(UnavailableReason.COACH_UNSUPPORTED)

        assertEquals("available:0", describe(available))
        assertEquals("unavailable:COACH_UNSUPPORTED", describe(unavailable))
    }

    @Test
    fun `coach unsupported unavailable result is distinguishable and carries its reason`() {
        val coachUnsupported = SteeringResult.Unavailable(UnavailableReason.COACH_UNSUPPORTED)
        val slowPathDown = SteeringResult.Unavailable(UnavailableReason.SLOW_PATH_UNAVAILABLE)

        assertIs<SteeringResult.Unavailable>(coachUnsupported)
        assertEquals(UnavailableReason.COACH_UNSUPPORTED, coachUnsupported.reason)
        assertTrue(coachUnsupported != slowPathDown)
        assertTrue(coachUnsupported.reason != slowPathDown.reason)
    }

    @Test
    fun `steering evidence constructs with empty corrections and hints`() {
        val evidence = SteeringEvidence(
            corrections = emptyList(),
            hints = emptyList(),
            focusHint = null,
            confidence = 0.42,
            sourceTurnId = 7
        )

        assertTrue(evidence.corrections.isEmpty())
        assertTrue(evidence.hints.isEmpty())
        assertEquals(0.42, evidence.confidence)
        assertEquals(7, evidence.sourceTurnId)
    }
}
