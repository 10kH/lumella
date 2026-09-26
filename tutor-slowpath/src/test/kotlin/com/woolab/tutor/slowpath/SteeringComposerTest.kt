package com.woolab.tutor.slowpath

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteeringComposerTest {

    private val persona = "You are Lumella, a warm Korean-language tutor."

    @Test
    fun detectsKoreanCodeSwitching() {
        assertTrue(TutorLanguage.containsHangul("이거 영어로 뭐라고 해?"))
        assertTrue(TutorLanguage.containsHangul("I want 사과"))
        assertFalse(TutorLanguage.containsHangul("I want an apple"))
    }

    @Test
    fun nonKoreanUtteranceTriggersEncourageKoreanScaffold() {
        // v1 Korean tutoring: the scaffold fires when the learner AVOIDED Korean.
        val text = SteeringComposer.compose(
            language = TutorLanguage.KOREAN,            personaSummary = persona,
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
            language = TutorLanguage.KOREAN,            personaSummary = persona,
            state = LearnerState(),
            corrections = emptyList(),
            lastUserUtterance = "어제 공원에 갔었어요",
        )
        assertFalse(text.contains("without using Korean"))
    }

    @Test
    fun blankUtteranceDoesNotTriggerCodeSwitchScaffold() {
        val text = SteeringComposer.compose(
            language = TutorLanguage.KOREAN,            personaSummary = persona,
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
        val text = SteeringComposer.compose(TutorLanguage.KOREAN, persona, state, emptyList(), lastUserUtterance = "what is this")
        assertTrue(text.contains("a red apple on a wooden table"))
        assertTrue(text.contains("apple"))
        assertTrue(text.contains("Ground vocabulary"))
    }

    @Test
    fun ruleGapBecomesLongitudinalSteeringWithPracticeForms() {
        val state = LearnerState(
            ruleGap = "irregular past tense: adds -ed to strong verbs",
            practiceTargets = listOf("went", "ate", "bought"),
        )
        val text = SteeringComposer.compose(TutorLanguage.KOREAN, persona, state, corrections = emptyList(), lastUserUtterance = "It was fun")
        assertTrue(text.contains("keeps making the same mistake: irregular past tense"))
        assertTrue(text.contains("model the correct pattern"))
        assertTrue(text.contains("went, ate, bought fit this learner"))
        assertTrue(text.contains("Do not point at the mistake"))
        // No per-turn recast rides along when the slow layer carries a diagnosis instead.
        assertTrue(!text.contains("Try:"))
    }

    @Test
    fun ruleGapIsSuppressedWhenLearnerStateIsOff() {
        val state = LearnerState(ruleGap = "articles: drops 'the'", practiceTargets = listOf("the bus"))
        val text = SteeringComposer.compose(TutorLanguage.KOREAN, persona, state, emptyList(), lastUserUtterance = "ok", useLearnerState = false)
        assertTrue(!text.contains("keeps making"))
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
        val text = SteeringComposer.compose(TutorLanguage.KOREAN, persona, state, corrections, lastUserUtterance = "I goed")
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
            language = TutorLanguage.KOREAN,            persona, state, corrections, lastUserUtterance = "I goed", useLearnerState = true,
        )
        val buffer = SteeringComposer.compose(
            language = TutorLanguage.KOREAN,            persona, state, corrections, lastUserUtterance = "I goed", useLearnerState = false,
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
