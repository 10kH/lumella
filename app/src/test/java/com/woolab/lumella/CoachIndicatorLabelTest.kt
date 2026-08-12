package com.woolab.lumella

import com.woolab.lumella.contract.CoachIndicator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoachIndicatorLabelTest {

    @Test
    fun `provider etri maps to SLM-TANGO`() {
        assertEquals("SLM-TANGO", CoachIndicatorLabel.providerLabel("etri"))
    }

    @Test
    fun `provider openai maps to LLM-GPT`() {
        assertEquals("LLM-GPT", CoachIndicatorLabel.providerLabel("openai"))
    }

    @Test
    fun `unknown provider falls back to the uppercased raw value`() {
        assertEquals("MYSTERY-PROVIDER", CoachIndicatorLabel.providerLabel("mystery-provider"))
    }

    @Test
    fun `all documented routes map to their Korean names`() {
        val expected = mapOf(
            "free_chat" to "자유대화",
            "reading" to "읽기",
            "scenario" to "상황극",
            "gpt_qa" to "질문답변",
            "image_talk" to "사진대화",
            "topic_chat" to "주제대화",
            "writing_handoff" to "쓰기",
            "fallback_tutor" to "기본",
        )
        for ((route, korean) in expected) {
            assertEquals(korean, CoachIndicatorLabel.routeName(route))
        }
    }

    @Test
    fun `unknown route falls back to the raw route string`() {
        assertEquals("brand_new_route", CoachIndicatorLabel.routeName("brand_new_route"))
    }

    @Test
    fun `unknown non-empty provider never returns an empty label`() {
        assertTrue(CoachIndicatorLabel.providerLabel("some_new_provider").isNotEmpty())
        assertTrue(CoachIndicatorLabel.routeName("some_new_route").isNotEmpty())
    }

    @Test
    fun `null indicator leaves the base hint unchanged`() {
        assertEquals("기존 힌트", CoachIndicatorLabel.hintLine(null, "기존 힌트"))
    }

    @Test
    fun `present indicator always carries the hardcoded voice segment first`() {
        val line = CoachIndicatorLabel.hintLine(
            CoachIndicator(route = "free_chat", provider = "etri"),
            "기존 힌트",
        )
        assertEquals("음성 LLM-GPT · 코치 SLM-TANGO(자유대화) · 기존 힌트", line)
        assertTrue(line.startsWith("음성 LLM-GPT"))
    }

    @Test
    fun `openai coach provider still carries the voice segment - two different LLM-GPT roles are not conflated away`() {
        val line = CoachIndicatorLabel.hintLine(
            CoachIndicator(route = "gpt_qa", provider = "openai"),
            "기존 힌트",
        )
        assertEquals("음성 LLM-GPT · 코치 LLM-GPT(질문답변) · 기존 힌트", line)
    }

    @Test
    fun `unknown route and unknown provider both surface raw values without crashing`() {
        val line = CoachIndicatorLabel.hintLine(
            CoachIndicator(route = "some_new_route", provider = "some_new_provider"),
            "기존 힌트",
        )
        assertEquals("음성 LLM-GPT · 코치 SOME_NEW_PROVIDER(some_new_route) · 기존 힌트", line)
    }
}
