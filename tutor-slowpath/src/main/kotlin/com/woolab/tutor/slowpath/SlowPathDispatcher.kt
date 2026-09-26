package com.woolab.tutor.slowpath

import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs the slow path off the critical path (plan P3/P5): for each queued turn it
 * fires the role agents in parallel via [PedagogyAgentClient], coalesces their
 * StateDeltas, and applies the single coalesced delta to the orchestrator
 * (single-writer, one apply per turn). The visual agent only fires when an image is
 * attached. Pure orchestration over the injected client — unit-testable with a fake.
 */
class SlowPathDispatcher(
    private val language: TutorLanguage,
    private val client: PedagogyAgentClient,
    private val orchestrator: StateGraphOrchestrator,
    /**
     * The longitudinal agent, fired on its own cadence (see [shouldConsolidate]) with the
     * accumulated state rather than the turn. Default null: the pre-registered evaluation
     * harness (EvalHarness, the ablation study) constructs this class with defaults and its
     * FULL condition is defined as per-turn deferred corrections — changing that under it
     * would invalidate the comparison. The shipped product (MainActivity) opts in with
     * ConsolidateAgent(), and then the grammar agent stops emitting per-turn corrections
     * because the diagnosis is what carries the slow layer's work to the tutor.
     */
    private val consolidateAgent: PedagogyAgent? = null,
    private val agents: List<PedagogyAgent> = listOf(
        GrammarAgent(emitDeferredCorrections = consolidateAgent == null),
        PronunciationFluencyAgent(emitDeferredCorrections = consolidateAgent == null),
        VisualContextAgent(),
    ),
    private val consolidateEveryTurns: Int = DEFAULT_CONSOLIDATE_EVERY_TURNS,
    private val consolidateOnErrorCount: Int = DEFAULT_CONSOLIDATE_ON_ERROR_COUNT,
    /** Where failures go. The app supplies a logger; tests capture. Never silent. */
    private val warn: (String) -> Unit = { System.err.println("slow-path: $it") },
    /**
     * The language gate exists to keep ambient audio in other languages out of the
     * learner's record. The evaluation corpus contains a deliberate English code-switch
     * turn whose whole purpose is to exercise the scaffolding response — gating it silently
     * changes the ablation numbers. The harness disables the gate so the study measures what
     * it was registered to measure; the product keeps it.
     */
    private val gateOtherLanguages: Boolean = true,
) {
    /** Drain all queued turns and dispatch each. Non-blocking poll. */
    fun drain(queue: SlowPathQueue) {
        while (true) {
            val task = queue.poll() ?: break
            dispatch(task)
        }
    }

    /** Fire the applicable agents for one turn; coalesce + apply when all return. */
    fun dispatch(task: SlowPathTask) {
        // Language gate. ELLA learned this the hard way: before its transcription language was
        // pinned, VAD picked up ambient television and 22 of 24 recorded "errors" were in the
        // wrong language, all persisted. The pin fixes most of it upstream; this gate is the
        // second wall: a tutor has nothing valid to say about an utterance in some other
        // language, so the language agents do not fire. What counts as "some other language"
        // is the TutorLanguage's call. The visual agent still runs on a photo — the image is
        // language-neutral. Also saves Vercel round-trips per noise turn.
        val inLanguage = !gateOtherLanguages || language.isPlausibly(task.userTranscript)
        val applicable = agents.filter { agent ->
            when (agent.role) {
                "visual" -> task.imageBase64 != null
                else -> inLanguage
            }
        }
        if (applicable.isEmpty()) return
        val deltas = Collections.synchronizedList(ArrayList<StateDelta>())
        // Record the turn itself. Nothing else writes turnHistory, and the diagnosis needs the
        // learner's sentences, not just the error spans, to tell one pattern from another.
        deltas.add(
            StateDelta(
                sourceTurnId = task.turnId,
                addTurnHistory = listOf(
                    TurnRecord(
                        turnId = task.turnId,
                        userTranscript = task.userTranscript,
                        ellaTranscript = task.ellaTranscript ?: "",
                        imageAttached = task.imageBase64 != null,
                    ),
                ),
            ),
        )
        val remaining = AtomicInteger(applicable.size)

        for (agent in applicable) {
            client.analyze(agent.role, task) { result ->
                result.onSuccess { body ->
                    runCatching { agent.toStateDelta(body, task) }
                        .onFailure { warn("${agent.role} parse failed at turn ${task.turnId}: ${it.message}") }
                        .getOrNull()?.let { deltas.add(it) }
                }
                // A per-turn failure used to vanish here. On the glasses that meant three
                // recorded turns and zero errors with nothing in the log to say why.
                result.onFailure { warn("${agent.role} call failed at turn ${task.turnId}: ${it.message}") }
                if (remaining.decrementAndGet() == 0) {
                    val after = SlowPathCoalescer.coalesce(deltas.toList())?.let { orchestrator.applySlowPath(it) }
                    // Only in-language turns feed the diagnosis; a photo-only dispatch does not.
                    // The cadence runs on fan-in regardless of whether this turn produced a
                    // delta: a quiet turn (no errors, agents returned nothing) is exactly the
                    // turn that should re-examine and clear a standing diagnosis.
                    if (inLanguage) maybeConsolidate(after ?: orchestrator.snapshot(), task.turnId)
                }
            }
        }
    }

    /**
     * Fire the consolidate agent when enough has happened since the last diagnosis: either
     * [consolidateEveryTurns] turns have passed, or [consolidateOnErrorCount] new errors have
     * accumulated (a learner making many mistakes gets diagnosed sooner). Its delta REPLACES
     * ruleGap/practiceTargets rather than accumulating, and stamps lastConsolidatedTurnId so
     * the cadence restarts.
     */
    private fun maybeConsolidate(state: LearnerState, turnId: Int) {
        val agent = consolidateAgent ?: return
        if (!shouldConsolidate(state, turnId, consolidateEveryTurns, consolidateOnErrorCount)) return
        val task = SlowPathTask(turnId = turnId, userTranscript = ConsolidateAgent.serialise(state, turnId))
        client.analyze(agent.role, task) { result ->
            result.onSuccess { body ->
                runCatching { agent.toStateDelta(body, task) }
                    .onFailure { warn("consolidate parse failed at turn $turnId: ${it.message}") }
                    .getOrNull()?.let { orchestrator.applySlowPath(it) }
            }.onFailure {
                // This is the paper's central path. A failure here is invisible to the wearer
                // (the tutor just stops being steered) and, before this line, invisible to us.
                warn("consolidate call failed at turn $turnId: ${it.message}")
            }
        }
    }

    companion object {
        private const val TAG = "slow-path"
        const val DEFAULT_CONSOLIDATE_EVERY_TURNS = 3
        const val DEFAULT_CONSOLIDATE_ON_ERROR_COUNT = 4

        /** Pure so the cadence is unit-testable. */
        fun shouldConsolidate(state: LearnerState, turnId: Int, everyTurns: Int, onErrorCount: Int): Boolean {
            // Nothing ever recorded and nothing diagnosed: nothing to say. But a standing
            // diagnosis with NO new errors must still be re-examined on cadence, or it can
            // never be cleared — the learner stopping the mistake is exactly the case.
            if (state.grammarErrors.isEmpty() && state.ruleGap == null) return false
            val turnsSince = turnId - state.lastConsolidatedTurnId
            val errorsSince = state.grammarErrors.count { it.turnId > state.lastConsolidatedTurnId }
            return turnsSince >= everyTurns || errorsSince >= onErrorCount
        }

    }
}
