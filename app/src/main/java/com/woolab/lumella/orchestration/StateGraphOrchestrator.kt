package com.woolab.lumella.orchestration

import com.woolab.lumella.config.AblationMode
import com.woolab.lumella.state.Correction
import com.woolab.lumella.state.CorrectionStatus
import com.woolab.lumella.state.LearnerState
import com.woolab.lumella.pedagogy.SteeringComposer
import com.woolab.lumella.state.LearnerStateStore
import com.woolab.lumella.state.StateDelta

/**
 * The channel used to steer a single response. AC5: deferred corrections + dynamic
 * steering use per-response `response.create.instructions` ONLY — never session.update
 * (session-wide persona only) and never a tool-call. This type makes the channel an
 * explicit, assertable value in the build output.
 */
enum class SteeringChannel { RESPONSE_CREATE_INSTRUCTIONS }

data class ResponseInstructions(
    val channel: SteeringChannel,
    val text: String,
    /** turnIds of corrections surfaced this response (to be marked DELIVERED). */
    val deliveredCorrectionSourceTurnIds: List<Int>,
    /**
     * The exact correction instances the guard evaluated this response (delivered,
     * re-anchored, or dropped). commitDelivery removes EXACTLY these by identity so
     * corrections that arrive late (after the lock-free build) are never silently lost.
     */
    val consumedCorrections: List<Correction>,
    /**
     * The corrections actually surfaced (delivered) this response, in delivery order.
     * Persisted to the eval log (B-LOG) so the offline judge item can be rebuilt
     * solely from the durable log (text + source-turn age).
     */
    val deliveredCorrections: List<Correction>,
)

/**
 * IntelliCode-style coordinator ported to realtime voice (plan Decision 2/3, P4).
 *
 * - applySlowPath: commits a (coalesced) StateDelta under the single-writer lock.
 * - buildResponseInstructions: at the app-owned seam right before response.create,
 *   reads a LOCK-FREE snapshot (AC1/F5 — never blocks on the writer lock), runs the
 *   SLA scheduling policy (immediate vs deferred) + staleness guard, and composes the
 *   per-response instruction payload (AC3/AC4/AC5). It does NOT itself send the event;
 *   MainActivity sends it on the existing response.create path (P5).
 * - commitDelivery: single-writer removal of delivered/dropped corrections and
 *   re-anchoring, keeping the deferred queue bounded.
 */
class StateGraphOrchestrator(
    private val store: LearnerStateStore,
    private val guard: StalenessGuard,
    private val ablationMode: AblationMode = AblationMode.FULL,
    val metric: StalenessMetric = StalenessMetric(),
) {

    /**
     * B0: ephemeral per-turn correction buffer for NO_LEARNER_STATE (usesLearnerState=false).
     * Replaces (never accumulates) each turn, so corrections are turn-local — bounded queue,
     * no cross-turn structured state, no longitudinal staleness aging. Distinct from the
     * shared learner-state [store] used by FULL/DEFERRED_ONLY/IMMEDIATE_ONLY. AtomicReference
     * whole-value set keeps lock-free reads safe; the set(apply)/clear(commit) pair is
     * last-writer-wins, which is sound here because the semantics are turn-local replace and
     * this path is NOT used by the shipped FULL configuration (NO_LEARNER_STATE is eval-only).
     */
    private val ephemeralBuffer = java.util.concurrent.atomic.AtomicReference<List<Correction>>(emptyList())

    /**
     * Commit a coalesced slow-path delta (single writer). No-op for SINGLE_AGENT.
     * B0: NO_LEARNER_STATE does NOT persist the delta into the shared learner-state; only the
     * deferred corrections survive, in a turn-local ephemeral buffer (replaced, not accumulated).
     */
    fun applySlowPath(delta: StateDelta): LearnerState? {
        if (!ablationMode.usesSlowAgents) return null
        if (!ablationMode.usesLearnerState) {
            if (ablationMode.usesDeferredCorrections) ephemeralBuffer.set(delta.addDeferredCorrections)
            return null
        }
        return store.apply(delta)
    }

    /**
     * Build the per-response steering payload at turn start. Lock-free snapshot read.
     * Honors the ablation mode: deferred corrections are only surfaced when the mode
     * uses them; SINGLE_AGENT / IMMEDIATE_ONLY surface none.
     */
    fun buildResponseInstructions(
        currentTurnId: Int,
        personaSummary: String,
    ): ResponseInstructions {
        val snapshot: LearnerState = store.snapshot() // AC1: lock-free, non-blocking

        val deliver: List<Correction>
        val deliveredTurnIds: List<Int>
        val consumed: List<Correction>
        if (ablationMode.usesDeferredCorrections) {
            if (ablationMode.usesLearnerState) {
                // FULL / DEFERRED_ONLY: structured longitudinal queue, staleness-guarded.
                val decisions = guard.evaluate(snapshot.deferredCorrections, currentTurnId)
                metric.record(decisions) // AC4 staleness-distribution metric
                deliver = decisions.mapNotNull { it.delivered } // DROP -> null (age>window never delivered)
                deliveredTurnIds = decisions
                    .filter { it.outcome != StalenessOutcome.DROP }
                    .map { it.original.turnId }
                consumed = decisions.map { it.original } // exact instances evaluated this turn
            } else {
                // B0 NO_LEARNER_STATE: surface only the ephemeral per-turn buffer (turn-local,
                // always fresh — no staleness aging, no cross-turn accumulation).
                val buffer = ephemeralBuffer.get()
                deliver = buffer
                deliveredTurnIds = buffer.map { it.turnId }
                consumed = buffer
            }
        } else {
            deliver = emptyList()
            deliveredTurnIds = emptyList()
            consumed = emptyList()
        }

        val text = composeInstructions(personaSummary, snapshot, deliver, ablationMode.usesLearnerState)
        return ResponseInstructions(
            channel = SteeringChannel.RESPONSE_CREATE_INSTRUCTIONS,
            text = text,
            deliveredCorrectionSourceTurnIds = deliveredTurnIds,
            consumedCorrections = consumed,
            deliveredCorrections = deliver,
        )
    }

    /**
     * Single-writer post-response cleanup. Removes EXACTLY the correction instances the
     * guard evaluated this response (carried on [instructions]), so corrections that
     * arrived asynchronously after the lock-free build are preserved (no silent loss /
     * no false DELIVERED). Grammar errors are marked DELIVERED only for corrections that
     * were actually surfaced (not the dropped/stale ones).
     */
    fun commitDelivery(instructions: ResponseInstructions) {
        if (!ablationMode.usesDeferredCorrections) return
        if (!ablationMode.usesLearnerState) {
            // B0 NO_LEARNER_STATE: clear the ephemeral buffer (consumed). The shared store is
            // never written, so the deferred "queue" is bounded to a single turn by construction.
            ephemeralBuffer.set(emptyList())
            return
        }
        if (instructions.consumedCorrections.isEmpty()) return
        val deliveredTurnIds = instructions.deliveredCorrectionSourceTurnIds.toSet()
        store.update { state ->
            state.copy(
                // Identity-based removal: keep any correction that was NOT one of the
                // exact instances evaluated this turn (incl. late arrivals).
                deferredCorrections = state.deferredCorrections.filter { c ->
                    instructions.consumedCorrections.none { it === c }
                },
                grammarErrors = state.grammarErrors.map {
                    if (it.turnId in deliveredTurnIds) it.copy(status = CorrectionStatus.DELIVERED) else it
                },
            )
        }
    }

    private fun composeInstructions(
        personaSummary: String,
        state: LearnerState,
        corrections: List<Correction>,
        useLearnerState: Boolean,
    ): String = SteeringComposer.compose(
        personaSummary = personaSummary,
        state = state,
        corrections = corrections,
        // Code-switch detection uses the most recent recorded learner utterance.
        lastUserUtterance = state.turnHistory.lastOrNull()?.userTranscript,
        useLearnerState = useLearnerState,
    )
}
