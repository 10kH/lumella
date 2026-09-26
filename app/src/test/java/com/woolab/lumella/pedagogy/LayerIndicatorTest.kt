package com.woolab.lumella.pedagogy

import com.woolab.lumella.state.LearnerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The indicator is the only place the paper's two-layer claim is visible to a viewer, so its
 * failure mode is dishonesty rather than a crash: naming the coach on a turn where the coach
 * did nothing would misrepresent the system on film.
 */
class LayerIndicatorTest {

    @Test
    fun voiceIsAlwaysTheRealtimeModel() {
        assertTrue(LayerIndicator.render(LearnerState()).contains("voice  realtime"))
        assertTrue(
            LayerIndicator.render(LearnerState(ruleGap = "irregular past")).contains("voice  realtime"),
        )
    }

    @Test
    fun coachNamesLunaOnlyWhileADiagnosisIsOnRecord() {
        val steering = LearnerState(ruleGap = "irregular past tense", practiceTargets = listOf("went"))
        assertTrue(LayerIndicator.render(steering).contains("coach  luna"))
        assertTrue(LayerIndicator.isCoachEngaged(steering))
    }

    @Test
    fun coachIsIdleWithNoDiagnosis() {
        for (s in listOf(LearnerState(), LearnerState(ruleGap = null), LearnerState(ruleGap = "  "))) {
            val text = LayerIndicator.render(s)
            assertFalse("must not name the model when it contributed nothing", text.contains("luna"))
            assertTrue(text.contains("coach  ·"))
            assertFalse(LayerIndicator.isCoachEngaged(s))
        }
    }

    @Test
    fun aClearedDiagnosisTurnsTheCoachBackOff() {
        // The learner stopped making the mistake; the slow layer is no longer steering, and the
        // indicator has to say so or it would claim credit for a turn it did not shape.
        val before = LearnerState(ruleGap = "irregular past tense", practiceTargets = listOf("went"), lastConsolidatedTurnId = 3)
        val after = before.copy(ruleGap = null, practiceTargets = emptyList(), lastConsolidatedTurnId = 6)
        assertTrue(LayerIndicator.render(before).contains("coach  luna"))
        assertTrue(LayerIndicator.render(after).contains("coach  ·"))
    }

    @Test
    fun rendersTwoAlignedLines() {
        val lines = LayerIndicator.render(LearnerState(ruleGap = "x")).split("\n")
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("voice"))
        assertTrue(lines[1].startsWith("coach"))
    }
}
