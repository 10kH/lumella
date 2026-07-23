package com.woolab.lumella.contract

/**
 * A single learner-utterance correction produced by the slow-path brain.
 *
 * D-4 rule: this is steering evidence only — the fast (voice) path decides
 * if/how to surface it. Callers MUST NOT speak [corrected] directly to the
 * learner as if it were the brain talking; it feeds the fast path's own
 * response generation.
 *
 * @param original the learner's original transcript fragment.
 * @param corrected the brain's corrected form.
 * @param errorType optional taxonomy tag for the error (e.g. grammar class).
 * @param sourceTurnId the turn this correction was derived from. Numbering
 *   domain: the BRAIN's server-side turn index within the brain session
 *   (e.g. luma `turn_log.turn_index`, 1-based), NOT the client-side
 *   [TurnEvidence.turnId]. The two coincide only for a fresh session where
 *   every turn is submitted with 1-based ids.
 */
data class SteeringCorrection(
    val original: String,
    val corrected: String,
    val errorType: String? = null,
    val sourceTurnId: Int
)

/**
 * Slow-path steering evidence for a turn: corrections plus supporting hints.
 *
 * D-4 rule: [corrections], [hints], and [focusHint] are steering evidence
 * only, never spoken directly — the fast path incorporates them into its own
 * generated response rather than relaying brain text verbatim.
 *
 * @param corrections learner-utterance corrections for [sourceTurnId].
 * @param hints free-form coaching hints supporting the corrections.
 * @param focusHint optional single highest-priority focus area for this turn.
 * @param confidence brain's confidence in this evidence, in `[0.0, 1.0]`.
 * @param sourceTurnId the turn this evidence was derived from. Numbering
 *   domain: the CLIENT-side [TurnEvidence.turnId] of the submitting turn —
 *   distinct from [SteeringCorrection.sourceTurnId]'s server-side index.
 */
data class SteeringEvidence(
    val corrections: List<SteeringCorrection>,
    val hints: List<String>,
    val focusHint: String? = null,
    val confidence: Double,
    val sourceTurnId: Int
)

/**
 * Result of [TutorBrain.fetchSteering]: either [Available] evidence to
 * incorporate per the D-4 rule (never spoken directly), or [Unavailable]
 * with a [reason] the fast path uses to decide how to degrade.
 */
sealed class SteeringResult {
    data class Available(val evidence: SteeringEvidence) : SteeringResult()
    data class Unavailable(val reason: UnavailableReason) : SteeringResult()
}

/**
 * Why slow-path steering evidence could not be produced.
 *
 * - [COACH_UNSUPPORTED] — W-1 posture: the connected brain lacks the coach
 *   capability (`BrainCapabilities.coach == false`). Expected steady state
 *   until the coach capability ships; the fast path MUST degrade to
 *   voice-only rather than hang.
 * - [SLOW_PATH_UNAVAILABLE] — coach capability present but the slow path is
 *   transiently unreachable/erroring.
 * - [NOT_READY] — evidence for the requested turn has not been produced yet
 *   (e.g. still processing); caller may retry.
 */
enum class UnavailableReason { COACH_UNSUPPORTED, SLOW_PATH_UNAVAILABLE, NOT_READY }
