package com.woolab.lumella.contract

/**
 * How [TutorBrain.startSession] should treat a [ResumableSession] reported by
 * a prior [BrainConnection]: resume it, or always start fresh.
 */
enum class SessionPolicy { RESUME_ACTIVE, FRESH }

/**
 * Result of [TutorBrain.startSession].
 *
 * @param sessionId identifier of the (possibly resumed) session.
 * @param resumed whether an existing session was resumed rather than created.
 * @param starterPrompt optional opening prompt the caller may surface.
 */
data class BrainSession(
    val sessionId: String,
    val resumed: Boolean,
    val starterPrompt: String? = null
)

/**
 * A single fast-path turn's evidence, submitted to the brain for slow-path
 * processing via [TutorBrain.submitTurnEvidence].
 *
 * @param turnId monotonically increasing turn identifier within the session.
 * @param learnerTranscript the learner's utterance for this turn.
 * @param assistantTranscript optional fast-path assistant response for this
 *   turn, if already generated.
 * @param imageId optional [ImageContext.imageId] this turn is grounded in.
 */
data class TurnEvidence(
    val turnId: Int,
    val learnerTranscript: String,
    val assistantTranscript: String? = null,
    val imageId: String? = null
)

/**
 * Which model/service coached a submitted turn — an honest per-turn indicator (08/05
 * requirement 3). [route] is the luma orchestrator's selected route for that turn (e.g.
 * `"free_chat"`); [provider] is the provider luma used to serve that route (e.g. `"etri"`,
 * `"openai"`). This describes the SLOW-path coach only — the realtime VOICE the learner
 * hears is a separate, always-on model; see `CoachIndicatorLabel` in `:app` for the display
 * mapping and why the voice segment is never derived from this type.
 */
data class CoachIndicator(val route: String, val provider: String)

/**
 * Slow-path coach brain contract. Pure Kotlin, blocking (no coroutines) —
 * callers are responsible for dispatching off the caller's own hot path.
 *
 * D-4 rule: everything this brain produces ([SteeringEvidence],
 * [ImageContext]) is steering/grounding evidence for the fast (voice) path's
 * own response generation. It is NEVER spoken directly to the learner as if
 * it were the brain talking.
 *
 * W-1 posture: when the connected brain lacks the coach capability
 * (`BrainCapabilities.coach == false`), [fetchSteering] returns
 * `SteeringResult.Unavailable(UnavailableReason.COACH_UNSUPPORTED)`. Callers
 * MUST degrade to voice-only (fast path stays live) rather than block.
 */
interface TutorBrain {
    /** Authenticate/connect using [provider]; reports readiness and capabilities. */
    fun connect(provider: BrainCredentialsProvider): BrainConnection

    /** Start (or resume, per [policy]) a tutoring session. */
    fun startSession(policy: SessionPolicy): BrainSession

    /**
     * Submit a fast-path turn's evidence for slow-path processing.
     *
     * Implementations MUST be idempotent per [TurnEvidence.turnId]: callers submit
     * once per turn. A single fast-path submitter owns this call (see `VoiceFastPath`
     * in `:app`); resubmitting the same [TurnEvidence.turnId] MUST NOT duplicate the
     * evidence applied slow-path.
     */
    fun submitTurnEvidence(evidence: TurnEvidence)

    /**
     * Fetch slow-path steering evidence for [sessionId]'s latest processed
     * turn, or the reason it is unavailable (see the W-1 posture above).
     */
    fun fetchSteering(sessionId: String): SteeringResult

    /** Analyze an image, returning grounding context (never spoken directly). */
    fun analyzeImage(bytes: ByteArray, mime: String): ImageContext

    /** End the session identified by [sessionId]. */
    fun endSession(sessionId: String)

    /**
     * Which model/service coached the most recently submitted turn, or `null` when no
     * coach routing data is available (brain reports nothing, or the data is stale/absent).
     * Additive default (D-4/contract-module rule): existing [TutorBrain] implementations
     * compile unchanged and simply show no indicator.
     */
    fun coachIndicator(): CoachIndicator? = null
}
