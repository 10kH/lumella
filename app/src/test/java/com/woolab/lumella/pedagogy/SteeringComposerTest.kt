package com.woolab.lumella.pedagogy

import com.woolab.lumella.state.Correction
import com.woolab.lumella.state.LearnerState
import com.woolab.lumella.state.VisualContextItem
import com.woolab.lumella.state.VocabTarget
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteeringComposerTest {

    private val persona = "You are Lumella, a warm Korean-language tutor."

    @Test
    fun detectsKoreanCodeSwitching() {
        assertTrue(SteeringComposer.containsKorean("이거 영어로 뭐라고 해?"))
        assertTrue(SteeringComposer.containsKorean("I want 사과"))
        assertFalse(SteeringComposer.containsKorean("I want an apple"))
    }

    @Test
    fun nonKoreanUtteranceTriggersEncourageKoreanScaffold() {
        // v1 Korean tutoring: the scaffold fires when the learner AVOIDED Korean.
        val text = SteeringComposer.compose(
            personaSummary = persona,
            state = LearnerState(),
            corrections = emptyList(),
            lastUserUtterance = "How do I say that?",
        )
        assertTrue(text.contains("without using Korean"))
        assertTrue(text.contains("encourage them to try in Korean"))
    }

    @Test
    fun koreanUtteranceDoesNotTriggerCodeSwitchScaffold() {
        val text = SteeringComposer.compose(
            personaSummary = persona,
            state = LearnerState(),
            corrections = emptyList(),
            lastUserUtterance = "어제 공원에 갔었어요",
        )
        assertFalse(text.contains("without using Korean"))
    }

    @Test
    fun blankUtteranceDoesNotTriggerCodeSwitchScaffold() {
        val text = SteeringComposer.compose(
            personaSummary = persona,
            state = LearnerState(),
            corrections = emptyList(),
            lastUserUtterance = "  ",
        )
        assertFalse(text.contains("without using Korean"))
    }

    @Test
    fun ac6_visualContextGroundsInstruction() {
        val state = LearnerState(
            visualContext = listOf(
                VisualContextItem(turnId = 3, caption = "a red apple on a wooden table", groundedObjects = listOf("apple", "table")),
            ),
        )
        val text = SteeringComposer.compose(persona, state, emptyList(), lastUserUtterance = "what is this")
        assertTrue(text.contains("a red apple on a wooden table"))
        assertTrue(text.contains("apple"))
        assertTrue(text.contains("Ground vocabulary"))
    }

    @Test
    fun composesVocabTargetsAndPrioritizedCorrections() {
        val state = LearnerState(
            vocabTargets = listOf(VocabTarget("orchard", "fruit farm"), VocabTarget("harvest", "picking")),
        )
        val corrections = listOf(
            Correction("Try: \"I went\" (tense)", priority = 2, sourceAgent = "grammar", turnId = 1),
            Correction("Watch the th sound", priority = 1, sourceAgent = "pronunciation", turnId = 1),
        )
        val text = SteeringComposer.compose(persona, state, corrections, lastUserUtterance = "I goed")
        assertTrue(text.contains("orchard"))
        // priority 1 (pronunciation) ordered before priority 2 (grammar)
        assertTrue(text.indexOf("th sound") < text.indexOf("I went"))
    }
    @Test
    fun b0_useLearnerStateFalseSuppressesStructuredVocabAndVisualButKeepsCorrectionBuffer() {
        val state = LearnerState(
            vocabTargets = listOf(VocabTarget("orchard", "fruit farm")),
            visualContext = listOf(
                VisualContextItem(turnId = 1, caption = "a red apple", groundedObjects = listOf("apple")),
            ),
        )
        val corrections = listOf(Correction("Try: \"I went\"", priority = 2, sourceAgent = "grammar", turnId = 1))
        val structural = SteeringComposer.compose(
            persona, state, corrections, lastUserUtterance = "I goed", useLearnerState = true,
        )
        val buffer = SteeringComposer.compose(
            persona, state, corrections, lastUserUtterance = "I goed", useLearnerState = false,
        )
        // Structured cross-turn signals appear only when learner-state is on (FULL).
        assertTrue(structural.contains("orchard"))
        assertTrue(structural.contains("a red apple"))
        assertFalse("buffer mode suppresses vocab targets", buffer.contains("orchard"))
        assertFalse("buffer mode suppresses visual grounding", buffer.contains("a red apple"))
        // The ephemeral correction buffer is still surfaced in both modes.
        assertTrue(buffer.contains("I went"))
    }

}
