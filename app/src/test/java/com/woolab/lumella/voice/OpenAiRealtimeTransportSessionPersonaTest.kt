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
}
