package com.woolab.lumella.voice

import com.woolab.lumella.voice.TopicGuidance.Opening
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TopicGuidanceTest {
    @get:Rule val tmp = TemporaryFolder()

    // --- how a conversation opens ---

    @Test
    fun withoutATopicTheTutorAsksWhatToTalkAboutWithTheLearnersOwnTopics() {
        val o = TopicGuidance.openingInstruction(null, returning = false, lastTopic = null,
            favorites = listOf("travel", "K-컬처", "음식"))
        assertTrue(o.contains("오늘은 어떤 얘기할까요?"))
        assertTrue(o.contains("examples: \"travel\", \"K-컬처\" —"))
        assertFalse("two examples at most", o.contains("음식"))
        assertTrue("English profile topics are said in Korean", o.contains("never an English word"))
        assertTrue("no tool on the opening turn", o.contains("Do not call any tool"))
    }

    @Test
    fun theLastChosenTopicIsOfferedFirst() {
        val o = TopicGuidance.openingInstruction(null, returning = false, lastTopic = "어제 친구랑 한 일",
            favorites = listOf("travel", "daily life"))
        assertTrue(o.contains("examples: \"어제 친구랑 한 일\", \"travel\" —"))
        assertTrue(o.contains("last time"))
    }

    @Test
    fun withoutAProfileOrHistoryItOffersEverydayTopics() {
        val o = TopicGuidance.openingInstruction(null, returning = false, lastTopic = null, favorites = emptyList())
        assertTrue(o.contains("어제 한 일"))
        assertFalse(o.contains("last time"))
    }

    @Test
    fun aPresetTopicOpensWithAQuestionOnIt() {
        assertEquals(Opening.ON_TOPIC, TopicGuidance.openingKind("여행", returning = false))
        val o = TopicGuidance.openingInstruction("여행", returning = false, lastTopic = "음식", favorites = listOf("x"))
        assertTrue(o.contains("Today's topic is \"여행\""))
        assertFalse("no menu of other topics", o.contains("음식"))
    }

    @Test
    fun aLearnerBackAfterAPauseIsAskedWhetherToKeepTheTopic() {
        assertEquals(Opening.WELCOME_BACK, TopicGuidance.openingKind("여행", returning = true))
        assertEquals(Opening.CHOOSE, TopicGuidance.openingKind(null, returning = true))
        val o = TopicGuidance.openingInstruction("여행", returning = true, lastTopic = null, favorites = emptyList())
        assertTrue(o.contains("back after a pause") && o.contains("\"여행\"") && o.contains("something else"))
    }

    @Test
    fun theOpeningNoteSaysWhatHappened() {
        assertTrue(TopicGuidance.openingNote(Opening.CHOOSE).contains("put the glasses on"))
        assertTrue(TopicGuidance.openingNote(Opening.WELCOME_BACK).contains("back after a pause"))
    }

    // --- topics ---

    @Test
    fun spokenTopicsAreTidiedAndBlankMeansNone() {
        assertEquals("여행", TopicGuidance.normalizeTopic("  \"여행\"  "))
        assertEquals("어제 친구와 한 일", TopicGuidance.normalizeTopic("어제 친구와 한 일\n두 번째 줄"))
        assertNull(TopicGuidance.normalizeTopic("   "))
        assertNull(TopicGuidance.normalizeTopic(null))
        assertEquals(TopicGuidance.MAX_TOPIC_CHARS, TopicGuidance.normalizeTopic("가".repeat(80))!!.length)
    }

    @Test
    fun topicMemoryKeepsTheLastChoiceMovesARepeatToTheEndAndForgetsOldOnes() {
        var clock = 1_000L
        val mem = TopicMemory(tmp.newFile("recent-topics.txt"), now = { clock })
        assertNull(mem.last())
        mem.remember("여행"); clock += 10
        mem.remember("음식"); clock += 10
        mem.remember("여행")
        assertEquals("여행", mem.last())
        val lines = tmp.root.resolve("recent-topics.txt").readLines()
        assertEquals(listOf("음식", "여행"), lines.map { it.substringAfter('\t') })
        clock += TopicMemory.DEFAULT_MAX_AGE_MS + 1
        assertNull("a week-old topic is not offered as last time's", mem.last())
    }

    @Test
    fun topicMemoryKeepsAtMostFiveAndSurvivesAGarbledFile() {
        val file = tmp.newFile("recent-topics.txt")
        file.writeText("garbage\n\t\nabc\tnot-a-time\n")
        var clock = 0L
        val mem = TopicMemory(file, now = { clock })
        assertNull(mem.last())
        for (i in 1..7) { clock += 1; mem.remember("주제$i") }
        assertEquals(TopicMemory.MAX_ENTRIES, file.readLines().size)
        assertEquals("주제7", mem.last())
    }

    @Test
    fun theSessionDeclaresSetTopicAndThePersonaSaysWhenToCallIt() {
        val p = OpenAiRealtimeTransport.DEFAULT_SESSION_INSTRUCTIONS
        assertTrue(p.contains("set_topic"))
        assertTrue("only when the learner chooses", p.contains("Never call it for a passing mention"))
        assertTrue("changing the subject without naming one: ask", p.contains("다른 얘기 하고 싶어요"))
    }
}
