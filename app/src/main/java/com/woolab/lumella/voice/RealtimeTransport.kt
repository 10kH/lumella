package com.woolab.lumella.voice

/**
 * The ONLY channel [VoiceFastPath] uses to steer the realtime voice session (plan P3,
 * D-4 invariant). Device WS/audio wiring lands later; this interface exists so
 * [VoiceFastPath] can be built and tested now, with a real implementation swapped
 * in without changing the fast-path orchestration.
 *
 * D-4: this interface intentionally exposes ONLY [sendInstructions] (mirroring
 * `response.create.instructions`, see StateGraphOrchestrator.SteeringChannel). There is
 * no `speak(text)` / raw-output method, so brain/steering text has no direct path to
 * spoken output — it can only ever reach the realtime model as instructions, which the
 * model then voices in its own words.
 */
fun interface RealtimeTransport {
    fun sendInstructions(instructions: String)
}
