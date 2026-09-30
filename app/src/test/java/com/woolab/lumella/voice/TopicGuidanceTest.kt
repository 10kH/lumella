package com.woolab.lumella.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TopicGuidanceTest {
    @Test
    fun openingAsksWhatToTalkAboutAndOffersTheLearnersOwnTopics() {
        val o = TopicGuidance.openingInstruction(listOf("여행", "K-컬처", "음식"))
        assertTrue(o.contains("오늘은 어떤 얘기할까요?"))
        assertTrue(o.contains("\"여행\"") && o.contains("\"K-컬처\""))
        assertFalse("two examples at most", o.contains("음식"))
        assertTrue("no tool on the opening turn", o.contains("Do not call any tool"))
    }

    @Test
    fun openingWithoutAProfileStillOffersExamples() {
        assertTrue(TopicGuidance.openingInstruction(emptyList()).contains("어제 한 일"))
    }

    @Test
    fun spokenTopicsAreTidiedAndBlankMeansNone() {
        assertEquals("여행", TopicGuidance.normalizeTopic("  \"여행\"  "))
        assertEquals("어제 친구와 한 일", TopicGuidance.normalizeTopic("어제 친구와 한 일\n두 번째 줄"))
        assertNull(TopicGuidance.normalizeTopic("   "))
        assertNull(TopicGuidance.normalizeTopic(null))
        assertEquals(TopicGuidance.MAX_TOPIC_CHARS, TopicGuidance.normalizeTopic("가".repeat(80))!!.length)
    }

    @Test
    fun theSessionDeclaresSetTopicAndThePersonaSaysWhenToCallIt() {
        val json = OpenAiRealtimeTransport.DEFAULT_SESSION_INSTRUCTIONS
        assertTrue(json.contains("set_topic"))
        assertTrue("only when the learner chooses", json.contains("Never call it for a passing mention"))
    }
}
