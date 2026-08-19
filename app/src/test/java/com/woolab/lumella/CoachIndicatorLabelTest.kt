package com.woolab.lumella

import com.woolab.lumella.contract.CoachIndicator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    // --- Adversarial: label torture (Ultragoal red-team, 08/12) ---

    @Test
    fun `empty string route and provider never crash and preserve the voice segment and base hint`() {
        val line = CoachIndicatorLabel.hintLine(CoachIndicator(route = "", provider = ""), "기존 힌트")
        assertEquals("음성 LLM-GPT · 코치 () · 기존 힌트", line)
        assertTrue(line.contains("음성 LLM-GPT"))
        assertTrue(line.contains("기존 힌트"))
        assertFalse(line.contains("\n"))
    }

    @Test
    fun `whitespace-only route and provider are flattened without crashing`() {
        // The adapter refuses blank pairs upstream (isNotBlank guards), so this layer only
        // has to stay harmless: no crash, no line break, voice segment and hint intact.
        val line = CoachIndicatorLabel.hintLine(CoachIndicator(route = "   ", provider = " "), "기존 힌트")
        assertTrue(line.contains("음성 LLM-GPT"))
        assertTrue(line.contains("기존 힌트"))
        assertFalse(line.contains("\n"))
    }

    @Test
    fun `10KB route string does not crash and is preserved verbatim without newlines`() {
        val huge = "x".repeat(10_000)
        val line = CoachIndicatorLabel.hintLine(CoachIndicator(route = huge, provider = "etri"), "기존 힌트")
        assertTrue(line.contains(huge))
        assertTrue(line.contains("SLM-TANGO"))
        assertTrue(line.contains("기존 힌트"))
        assertFalse(line.contains("\n"))
    }

    @Test
    fun `RTL route text renders raw without crashing`() {
        val rtl = "مرحبا بالعالم"
        val line = CoachIndicatorLabel.hintLine(CoachIndicator(route = rtl, provider = "openai"), "기존 힌트")
        assertTrue(line.contains(rtl))
        assertTrue(line.contains("음성 LLM-GPT"))
        assertTrue(line.contains("LLM-GPT"))
        assertFalse(line.contains("\n"))
    }

    /**
     * These used to PIN a MEDIUM defect: control characters flowed into the hint line
     * unfiltered, breaking the one-physical-line hint bar. Fixed: oneLine() collapses ISO
     * control characters to spaces in the raw-fallback paths (known enum values never
     * contain them by construction).
     */
    @Test
    fun `a route containing a newline is flattened to keep the hint on one line`() {
        val line = CoachIndicatorLabel.hintLine(CoachIndicator(route = "line1\nline2", provider = "etri"), "기존 힌트")
        assertFalse("a control character must never split the hint bar", line.contains("\n"))
        assertTrue("the flattened raw route still renders", line.contains("line1 line2"))
        assertTrue(line.contains("음성 LLM-GPT"))
        assertTrue(line.contains("기존 힌트"))
    }

    @Test
    fun `other control characters are likewise flattened`() {
        for (ctrl in listOf('\t', '\r', '\u0000', '\u001B')) {
            val line = CoachIndicatorLabel.hintLine(CoachIndicator(route = "a${ctrl}b", provider = "openai"), "기존 힌트")
            assertFalse("control char ${ctrl.code} must be flattened", line.contains("a${ctrl}b"))
        }
    }

    @Test
    fun `emoji route and provider render raw without crashing`() {
        val line = CoachIndicatorLabel.hintLine(CoachIndicator(route = "🎉_scenario", provider = "😀"), "기존 힌트")
        assertTrue(line.contains("🎉_scenario"))
        assertTrue(line.contains("😀"))
        assertFalse(line.contains("\n"))
    }

    /**
     * Not a live defect: luma-api's `OrchestratorProvider` Literal type only ever emits the
     * exact lowercase strings "etri" / "openai" / "internal" (schemas/orchestrator.py:21), so
     * this case sensitivity is unreachable in production traffic today. Recorded as a
     * fragility: if a future backend change ever emits a mixed-case value, "SLM-TANGO" silently
     * stops appearing and the raw uppercased string shows instead — both "ETRI" and "Etri"
     * collide onto the same uppercase fallback "ETRI", which looks plausible but is NOT the
     * SLM-TANGO branch.
     */
    @Test
    fun `provider label matching is case-sensitive - only the exact lowercase wire value maps to a friendly label`() {
        assertEquals("SLM-TANGO", CoachIndicatorLabel.providerLabel("etri"))
        assertEquals("ETRI", CoachIndicatorLabel.providerLabel("ETRI"))
        assertEquals("ETRI", CoachIndicatorLabel.providerLabel("Etri"))
    }

    /**
     * This used to PIN a LOW gap: "internal" is a valid backend provider value with no
     * friendly label, so learners would have seen "코치 INTERNAL(…)". Mapped to 내부 — no
     * SLM/LLM prefix on purpose, the internal engine is neither.
     */
    @Test
    fun `provider internal renders its Korean label`() {
        assertEquals("내부", CoachIndicatorLabel.providerLabel("internal"))
    }

    @Test
    fun `provider and route fields are mapped independently even when their values are swapped`() {
        val line = CoachIndicatorLabel.hintLine(CoachIndicator(route = "etri", provider = "free_chat"), "기존 힌트")
        assertEquals("음성 LLM-GPT · 코치 FREE_CHAT(etri) · 기존 힌트", line)
    }

    @Test
    fun `confidence renders inline in the coach segment when the server sent one`() {
        val line = CoachIndicatorLabel.hintLine(
            CoachIndicator(route = "free_chat", provider = "etri", confidencePercent = 98),
            "기존 힌트",
        )
        assertEquals("음성 LLM-GPT · 코치 SLM-TANGO(자유대화 98%) · 기존 힌트", line)
    }

    @Test
    fun `no confidence means no percent - never a fabricated number`() {
        val line = CoachIndicatorLabel.hintLine(CoachIndicator(route = "free_chat", provider = "etri"), "기존 힌트")
        assertFalse(line.contains("%"))
    }

    @Test
    fun `the reason variant replaces the base hint with the router's own words`() {
        val line = CoachIndicatorLabel.hintLineWithReason(
            CoachIndicator(route = "free_chat", provider = "etri", reason = "학습자가 자유 대화를 원함", confidencePercent = 74),
            "기존 힌트",
        )
        assertEquals("음성 LLM-GPT · 코치 SLM-TANGO(자유대화 74%) · 사유: 학습자가 자유 대화를 원함", line)
        assertFalse("the tap guide yields while the reason shows", line.contains("기존 힌트"))
    }

    @Test
    fun `a long reason is clipped by display width - Hangul counts double`() {
        val longReason = "학습자가 특정 질문이 아니라 자유로운 한국어 말하기를 원하며 짧은 대화를 이어가려 한다"
        val line = CoachIndicatorLabel.hintLineWithReason(
            CoachIndicator(route = "free_chat", provider = "etri", reason = longReason),
            "기존 힌트",
        )
        assertTrue("clip must end with an ellipsis", line.endsWith("…"))
        val shown = line.substringAfter("사유: ").removeSuffix("…")
        val width = shown.sumOf { if (it.code >= 0x1100) 2 else 1 as Int }
        assertTrue("width $width exceeds the 40-unit budget", width <= 40)
    }

    @Test
    fun `a blank reason falls back to the plain hint line - never an empty 사유 segment`() {
        val line = CoachIndicatorLabel.hintLineWithReason(
            CoachIndicator(route = "free_chat", provider = "etri", reason = "  "),
            "기존 힌트",
        )
        assertEquals(CoachIndicatorLabel.hintLine(CoachIndicator(route = "free_chat", provider = "etri", reason = "  "), "기존 힌트"), line)
        assertTrue(line.contains("기존 힌트"))
    }

    @Test
    fun `a reason with control characters stays on one line`() {
        val line = CoachIndicatorLabel.hintLineWithReason(
            CoachIndicator(route = "free_chat", provider = "etri", reason = "줄1\n줄2"),
            "기존 힌트",
        )
        assertFalse(line.contains("\n"))
    }

    @Test
    fun `a future speaking-eval turn renders SLM-ASE, not SLM-TANGO`() {
        // Pre-wired for the ETRI ASE integration: same provider string ("etri"), different
        // service. The 08/05 memo names this exact label. Until luma gains the route this is
        // unreachable from live traffic — the pin exists so the integration needs no glasses
        // change and no one "fixes" the special case away in the meantime.
        val line = CoachIndicatorLabel.hintLine(
            CoachIndicator(route = "speaking_eval", provider = "etri", confidencePercent = 91),
            "기존 힌트",
        )
        assertEquals("음성 LLM-GPT · 코치 SLM-ASE(말하기평가 91%) · 기존 힌트", line)
    }
}
