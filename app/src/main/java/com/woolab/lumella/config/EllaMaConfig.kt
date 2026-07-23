package com.woolab.lumella.config

/**
 * Ablation modes for ELLA-MA evaluation (plan Decision 5 / AC8).
 *
 * Each mode is switchable from a single config flag so the evaluation harness
 * can produce comparable logs across conditions without code changes.
 *
 * - SINGLE_AGENT: baseline = current single-agent ELLA (fast path only, no
 *   shared learner-state, no slow-path pedagogical agents).
 * - NO_LEARNER_STATE: slow agents run but their StateDeltas are not persisted
 *   into a shared learner-state (rebuts the "single-agent + buffer" steelman).
 * - IMMEDIATE_ONLY: only in-conversation recast (fast lane); deferred
 *   corrections are never scheduled.
 * - DEFERRED_ONLY: only deferred analytical corrections; no immediate recast.
 * - FULL: the full SLA scheduling policy (immediate recast + deferred,
 *   staleness-guarded corrections over a shared learner-state).
 */
enum class AblationMode {
    SINGLE_AGENT,
    NO_LEARNER_STATE,
    IMMEDIATE_ONLY,
    DEFERRED_ONLY,
    FULL;

    /**
     * B0 (PRIMARY, read by StateGraphOrchestrator/SteeringComposer): whether the shared,
     * structured cross-turn learner-state is used for steering. When true (FULL/IMMEDIATE_ONLY/
     * DEFERRED_ONLY) the orchestrator persists slow-path deltas into the shared store and the
     * composer surfaces accumulated vocab targets, visual-context continuity, and longitudinally
     * staleness-aged corrections. When false (NO_LEARNER_STATE) deltas are NOT persisted; only an
     * ephemeral per-turn correction buffer is surfaced (no cross-turn accumulation, no aging).
     * This pre-registered buffer-vs-structural boundary is the FULL vs NO_LEARNER_STATE contrast.
     */
    val usesLearnerState: Boolean
        get() = this == FULL || this == IMMEDIATE_ONLY || this == DEFERRED_ONLY

    /** Whether slow-path pedagogical agents fire at all. */
    val usesSlowAgents: Boolean
        get() = this != SINGLE_AGENT

    /**
     * Whether immediate in-conversation recast (fast lane) is enabled.
     * The fast lane IS the realtime model itself (plan Decision 3); it recasts
     * inline whenever it runs. Only DEFERRED_ONLY disables it by design.
     *
     * DEVICE-SIDE / DESCRIPTIVE by design: the offline harness has no realtime fast lane to
     * gate, so the FULL-vs-DEFERRED_ONLY immediate-recast contrast is routed to the on-device
     * demo (plan Block C), NOT the offline judged ablation. This flag is therefore intentionally
     * not read by [StateGraphOrchestrator]; it documents the device condition, not dead code.
     */
    val usesImmediateRecast: Boolean
        get() = this != DEFERRED_ONLY

    /**
     * Whether deferred analytical corrections are scheduled/delivered.
     * NO_LEARNER_STATE still delivers corrections (from an ephemeral per-turn
     * buffer) to rebut the "single-agent + buffer" steelman, but without the
     * structured shared learner-state (usesLearnerState = false).
     */
    val usesDeferredCorrections: Boolean
        get() = this == FULL || this == DEFERRED_ONLY || this == NO_LEARNER_STATE
}

/**
 * Static ELLA-MA runtime configuration (plan P0).
 *
 * `ellaMaEnabled` gates the entire multi-agent layer; when false the app behaves
 * as the original single-agent ELLA. `ablationMode` selects the evaluation
 * condition. `stalenessGuardMaxAgeTurns` (K) bounds how old a deferred
 * correction may be before the staleness guard drops or re-anchors it
 * (plan AC4 / FIX A); default chosen conservatively and overridable per run.
 */
data class EllaMaConfig(
    val ellaMaEnabled: Boolean = true,
    val ablationMode: AblationMode = AblationMode.FULL,
    val stalenessGuardMaxAgeTurns: Int = 3,
) {
    init {
        require(stalenessGuardMaxAgeTurns >= 1) {
            "stalenessGuardMaxAgeTurns (K) must be >= 1"
        }
    }

    companion object {
        /** Baseline single-agent ELLA: multi-agent layer effectively off. */
        val SINGLE_AGENT_BASELINE = EllaMaConfig(
            ellaMaEnabled = false,
            ablationMode = AblationMode.SINGLE_AGENT,
        )
    }
}
