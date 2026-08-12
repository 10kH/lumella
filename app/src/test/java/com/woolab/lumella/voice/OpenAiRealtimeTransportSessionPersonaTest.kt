package com.woolab.lumella.voice

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the two persona sentences that fixed the 2026-08-05 "confident invention" bug: the
 * model was never told a camera existed, so asked what was in front of the learner it
 * invented a desk scene instead of admitting it had not looked. If a future persona edit
 * drops either sentence, this test should be the one that explains why the app regressed
 * instead of a wearer finding out live.
 */
class OpenAiRealtimeTransportSessionPersonaTest {

    private val instructions = OpenAiRealtimeTransport.DEFAULT_SESSION_INSTRUCTIONS

    @Test
    fun `persona still tells the model it can look through the glasses`() {
        assertTrue(
            "DEFAULT_SESSION_INSTRUCTIONS must mention capture_photo, or the model has no " +
                "reason to believe looking is an option — it will invent a scene instead of " +
                "calling the tool, exactly like the 2026-08-05 desk/laptop/coffee-cup failure.",
            instructions.contains("capture_photo"),
        )
    }

    @Test
    fun `persona still forbids describing surroundings it has not photographed`() {
        assertTrue(
            "DEFAULT_SESSION_INSTRUCTIONS must forbid describing the surroundings from " +
                "imagination. Without this explicit ban the model will confidently describe " +
                "a scene it never captured (2026-08-05: a pitch-dark room described as a desk " +
                "with a laptop and a coffee cup) instead of saying it will take a look.",
            instructions.contains("NEVER describe") && instructions.contains("imagination"),
        )
    }

    @Test
    fun `persona mentions all three display and language tools by name`() {
        // 08/05 requirement 2: without these, the model has no reason to believe display
        // control or a language switch is even possible — it would try to describe the
        // change in words instead of calling the tool, same failure class as capture_photo.
        assertTrue("must mention set_text_display", instructions.contains("set_text_display"))
        assertTrue("must mention set_hints_visible", instructions.contains("set_hints_visible"))
        assertTrue("must mention switch_tutor_language", instructions.contains("switch_tutor_language"))
    }

    @Test
    fun `persona still teaches the Korean text-display trigger phrases`() {
        assertTrue(instructions.contains("텍스트 크게"))
        assertTrue(instructions.contains("텍스트 작게"))
        assertTrue(instructions.contains("텍스트 꺼줘"))
        assertTrue(instructions.contains("텍스트 켜줘"))
        assertTrue(instructions.contains("설명 꺼줘"))
        assertTrue(instructions.contains("설명"))
    }

    @Test
    fun `persona instructs a handover sentence BEFORE the language-switch tool call`() {
        // A silent language switch is disorienting (the app just vanishes and a different one
        // appears); the model must speak first, then call the tool — not the other way round.
        assertTrue(
            "persona must say to speak a handover sentence FIRST, THEN call the tool",
            instructions.contains("handover sentence FIRST") && instructions.contains("THEN"),
        )
        val handoverIndex = instructions.indexOf("handover sentence FIRST")
        val toolCallIndex = instructions.indexOf("call switch_tutor_language")
        assertTrue("handover instruction must precede the tool-call instruction", handoverIndex in 0 until toolCallIndex)
    }
}
