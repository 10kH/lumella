package com.woolab.tutor.slowpath


/**
 * The two-line indicator in the corner of the wearer's view: which layer produced the voice,
 * and whether the slow layer is currently shaping the conversation.
 *
 * Why it exists: the paper's claim is that two layers with different jobs are both at work and
 * that an observer can tell which did what. Without something on screen that claim is only
 * checkable in a JSON file after the fact, which is not a demonstration. This is the smallest
 * honest version — it reports state that already exists rather than narrating.
 *
 * Honesty rules, in order of how easily each could be faked:
 *  - `voice` is always the real-time model. It speaks every turn; there is nothing to decide.
 *  - `coach` names the slow model ONLY while a diagnosis is on record. A consultation that
 *    returned nothing, or one that cleared a stale diagnosis, leaves the coach idle, because on
 *    that turn the slow layer genuinely contributed nothing to what the learner hears.
 *  - The idle mark is a dot, not the model's name. Printing "luna" when luna has said nothing
 *    would be exactly the dishonesty this indicator exists to prevent.
 *
 * Pure so it is testable on a plain JVM; MainActivity maps the string onto both eyes.
 */
object LayerIndicator {

    const val VOICE_MODEL = "realtime"
    const val COACH_MODEL = "luna"

    /** Shown when the slow layer holds no diagnosis: it is listening, not steering. */
    private const val IDLE = "·"

    /**
     * Two lines, aligned, for a fixed-width-ish corner label:
     *
     *   voice  realtime
     *   coach  luna          <- a diagnosis is steering this turn
     *
     *   voice  realtime
     *   coach  ·             <- nothing on record; the fast layer is on its own
     */
    fun render(state: LearnerState): String {
        val coach = if (state.ruleGap.isNullOrBlank()) IDLE else COACH_MODEL
        return "voice  $VOICE_MODEL\ncoach  $coach"
    }

    /** True when the slow layer is currently shaping replies — what the film needs to catch. */
    fun isCoachEngaged(state: LearnerState): Boolean = !state.ruleGap.isNullOrBlank()
}
