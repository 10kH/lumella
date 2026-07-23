package com.woolab.lumella.agents

import com.woolab.lumella.state.StateDelta

/**
 * Per-turn coalescing of parallel slow-path agent outputs (plan P3, Decision 3).
 *
 * The three agents (grammar, pronunciation, visual) fire in parallel for a turn;
 * their [StateDelta]s are coalesced into ONE delta before the orchestrator applies
 * it under the single-writer lock. This bounds writer contention to one apply per
 * turn (not one per agent). Deltas for mismatched turns are rejected.
 */
object SlowPathCoalescer {

    /**
     * Merge per-turn deltas into one. All deltas must share the same sourceTurnId.
     * Returns null if the list is empty.
     */
    fun coalesce(deltas: List<StateDelta>): StateDelta? {
        if (deltas.isEmpty()) return null
        val turnId = deltas.first().sourceTurnId
        require(deltas.all { it.sourceTurnId == turnId }) {
            "coalesce requires all deltas to share sourceTurnId=$turnId"
        }
        return StateDelta(
            sourceTurnId = turnId,
            age = deltas.maxOf { it.age },
            addGrammarErrors = deltas.flatMap { it.addGrammarErrors },
            // Last non-null pronFluency wins (only the pronunciation agent sets it).
            pronFluency = deltas.mapNotNull { it.pronFluency }.lastOrNull(),
            addVocabTargets = deltas.flatMap { it.addVocabTargets },
            addVisualContext = deltas.flatMap { it.addVisualContext },
            addDeferredCorrections = deltas
                .flatMap { it.addDeferredCorrections }
                .sortedBy { it.priority }, // priority 1 (pronunciation) before 2 (grammar)
            addTurnHistory = deltas.flatMap { it.addTurnHistory },
        )
    }
}
