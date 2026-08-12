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

    @Test
    fun `the persona leads with a recast, like the English tutor it was aligned to`() {
        // 08/05 requirement 3: same recast behaviour as the English app. The regression this
        // guards: a persona edit that quietly drops the recast-first rule reverts the Korean
        // tutor to answering without ever modelling the natural phrasing.
        val p = OpenAiRealtimeTransport.DEFAULT_SESSION_INSTRUCTIONS
        assertTrue("recast-first rule lost", p.contains("FIRST move is a recast"))
        assertTrue("never-as-a-drill lost", p.contains("never as a drill"))
    }

    @Test
    fun `the persona forbids repeat-after-me drills`() {
        // Observed live on this very app (2026-08-12): "자, 이제 따라 해보실까요?" — the exact
        // drill the requirements ban. The ban must name the Korean phrases, because that is
        // what the model produces.
        val p = OpenAiRealtimeTransport.DEFAULT_SESSION_INSTRUCTIONS
        assertTrue(p.contains("NEVER ask"))
        assertTrue(p.contains("따라하세요"))
    }

    @Test
    fun `the persona caps reply length and difficulty`() {
        // The live complaint behind 08/05 requirement 3: replies too long and too difficult.
        val p = OpenAiRealtimeTransport.DEFAULT_SESSION_INSTRUCTIONS
        assertTrue("length cap lost", p.contains("1-2 short sentences"))
        assertTrue("difficulty cap lost", p.contains("everyday vocabulary"))
    }
}
