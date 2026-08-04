package com.woolab.lumella

import java.util.concurrent.atomic.AtomicInteger

/**
 * Decides what to do with a tool call from the model.
 *
 * Pure and dependency-free so the rules are testable on a plain JVM — the device path they
 * guard is expensive to reach and, in the case of the retry ceiling, hard to provoke on
 * purpose: the model only loops when conditions are bad enough, which is exactly when nobody
 * is watching.
 *
 * Two rules, both learned the hard way:
 *
 * Every call must be answered. A tool call ends the response that emitted it, so a name the
 * app does not implement is not something it can quietly ignore — the model would wait
 * forever for a result that is never coming.
 *
 * And looking has a floor. The persona asks for another photo when one is unusable, and in a
 * dark room every photo is unusable, so "let me try again" recurses on its own. Each round
 * costs a realtime response and a camera bind.
 */
class CapturePolicy(private val maxCapturesPerUtterance: Int = DEFAULT_MAX_CAPTURES) {

    /** What the caller should do about one tool call. */
    sealed interface Decision {
        /** Run the capture; answer the model when it finishes. */
        data object Capture : Decision

        /** Do not capture; answer the model immediately with [payload]. */
        data class Refuse(val reason: String) : Decision {
            /**
             * Escaped rather than interpolated. The reasons are fixed literals today, so this
             * is unreachable — but raw interpolation into JSON is the habit that produced the
             * stray brace which voided an entire session, and it costs nothing to not have it.
             */
            val payload: String get() =
                """{"status":"error","reason":${com.woolab.lumella.voice.jsonString(reason)}}"""
        }
    }

    private val capturesSinceLearnerSpoke = AtomicInteger(0)

    /** Resets the ceiling. Call when the learner takes a turn — that is a fresh request. */
    fun onLearnerSpoke() {
        capturesSinceLearnerSpoke.set(0)
    }

    /**
     * Consecutive capture_photo calls seen since the learner last spoke, refusals included.
     *
     * Named for what it counts. It reads attempts, not photos taken — five calls against a
     * ceiling of two report five — which is the useful number when the question is "is the
     * model looping", and a misleading one if you read it as "photos taken".
     */
    fun attemptsSoFar(): Int = capturesSinceLearnerSpoke.get()

    fun decide(toolName: String): Decision = when {
        toolName != CAPTURE_PHOTO -> Decision.Refuse(REASON_UNKNOWN_TOOL)
        capturesSinceLearnerSpoke.incrementAndGet() > maxCapturesPerUtterance ->
            Decision.Refuse(REASON_TOO_MANY)
        else -> Decision.Capture
    }

    companion object {
        const val CAPTURE_PHOTO = "capture_photo"
        const val DEFAULT_MAX_CAPTURES = 2
        const val REASON_UNKNOWN_TOOL = "unknown_tool"
        const val REASON_TOO_MANY = "too_many_attempts"
    }
}
