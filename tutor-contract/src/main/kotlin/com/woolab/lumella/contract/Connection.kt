package com.woolab.lumella.contract

/**
 * Supplies the credentials `TutorBrain.connect` needs to authenticate against
 * the brain backend. Implementations resolve/refresh credentials (e.g. from a
 * local token service); the contract itself is transport-agnostic.
 */
interface BrainCredentialsProvider {
    fun credentials(): BrainCredentials
}

/**
 * Credentials for authenticating a brain connection.
 *
 * @param baseUrl base URL of the brain backend.
 * @param email account identifying the learner/session owner.
 * @param password account secret.
 * @param deviceName device identifier reported to the backend; defaults to
 *   the glasses device identity.
 */
data class BrainCredentials(
    val baseUrl: String,
    val email: String,
    val password: String,
    val deviceName: String = "lumella-glasses"
)

/**
 * Coarse-grained readiness of a brain connection.
 *
 * - [READY] — brain reachable and authenticated; slow-path steering may be
 *   available depending on [BrainCapabilities.coach].
 * - [DEGRADED] — brain reachable but a subsystem is unavailable (e.g. coach
 *   capability absent per the W-1 posture below); callers MUST continue on
 *   the fast (voice) path without silently hanging.
 * - [AUTH_REQUIRED] — credentials rejected or expired; caller must
 *   re-authenticate before retrying.
 */
enum class BrainConnectionState { READY, DEGRADED, AUTH_REQUIRED }

/**
 * Capabilities negotiated with the brain backend for the current connection.
 *
 * W-1 posture: when the coach capability is ABSENT (`coach == false`), the
 * slow-path steering feed is unavailable and `TutorBrain.fetchSteering` MUST
 * return `SteeringResult.Unavailable(UnavailableReason.COACH_UNSUPPORTED)`
 * rather than blocking or throwing. Callers degrade to voice-only.
 *
 * @param coach whether slow-path coach/steering evidence is supported.
 * @param capabilitiesRoute whether the backend exposes a capability
 *   negotiation route (vs. assuming a fixed baseline).
 * @param raw additional backend-reported capability flags, verbatim.
 */
data class BrainCapabilities(
    val coach: Boolean,
    val capabilitiesRoute: Boolean,
    val raw: Map<String, String> = emptyMap()
)

/**
 * A prior session the backend can resume instead of starting fresh.
 *
 * @param sessionId identifier of the resumable session.
 * @param ageMinutes minutes elapsed since the session was last active.
 */
data class ResumableSession(val sessionId: String, val ageMinutes: Long)

/**
 * Result of [TutorBrain.connect]: connection readiness, negotiated
 * capabilities, and an optional resumable session the caller may choose to
 * continue via `SessionPolicy.RESUME_ACTIVE`.
 */
data class BrainConnection(
    val state: BrainConnectionState,
    val capabilities: BrainCapabilities,
    val resumableSession: ResumableSession? = null
)
