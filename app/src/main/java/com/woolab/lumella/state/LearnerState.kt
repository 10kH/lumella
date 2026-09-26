package com.woolab.lumella.state

/**
 * ELLA-MA shared learner-state (plan Decision 2 / P1; schema in
 * docs/ella-ma/learner-state-schema.md). Immutable value type; the single-writer
 * [LearnerStateStore] publishes new snapshots. Fast path reads snapshots lock-free.
 */

data class Profile(
    val l1: String = "ko",
    val proficiencyEstimate: String = "unknown",
    val goals: List<String> = emptyList(),
)

enum class CorrectionStatus { NEW, DELIVERED }

/** A detected learner error with the recast (correct form). `turnId` = source turn. */
data class ErrorRecord(
    val span: String,
    val type: String,
    val recast: String,
    val turnId: Int,
    val status: CorrectionStatus = CorrectionStatus.NEW,
)

data class PronFluency(
    val lastScore: Double? = null,
    val problemPhonemes: List<String> = emptyList(),
    val wpm: Double? = null,
    val pauseRatio: Double? = null,
)

data class VocabTarget(
    val word: String,
    val context: String,
    val introduced: Boolean = false,
)

data class VisualContextItem(
    val turnId: Int,
    val caption: String,
    val groundedObjects: List<String> = emptyList(),
)

/**
 * A deferred correction queued for later delivery. Staleness fields (FIX A):
 * [turnId] is the source turn; [age] is turns elapsed since [turnId] at the time
 * the orchestrator schedules delivery (the staleness guard drops/re-anchors when
 * age exceeds K = EllaMaConfig.stalenessGuardMaxAgeTurns).
 */
data class Correction(
    val text: String,
    val priority: Int,
    val sourceAgent: String,
    val turnId: Int,
    val age: Int = 0,
)

data class TurnRecord(
    val turnId: Int,
    val userTranscript: String,
    val ellaTranscript: String,
    val imageAttached: Boolean,
)

data class LearnerState(
    val profile: Profile = Profile(),
    val grammarErrors: List<ErrorRecord> = emptyList(),
    val pronFluency: PronFluency = PronFluency(),
    val vocabTargets: List<VocabTarget> = emptyList(),
    val visualContext: List<VisualContextItem> = emptyList(),
    val deferredCorrections: List<Correction> = emptyList(),
    val turnHistory: List<TurnRecord> = emptyList(),
    /**
     * The slow layer's longitudinal diagnosis: the one grammatical pattern this learner keeps
     * getting wrong, in a sentence, or null when no pattern has recurred yet. Refreshed every
     * few turns by the consolidate agent, not every turn. This is what the steering carries
     * — the fast layer already recasts individual errors on the same turn, so per-turn recasts
     * from here were redundant.
     */
    val ruleGap: String? = null,
    /** 2-4 correct forms of [ruleGap] the tutor can use naturally in its next replies. */
    val practiceTargets: List<String> = emptyList(),
    /** Turn at which [ruleGap] was last refreshed; the dispatch policy reads it. */
    val lastConsolidatedTurnId: Int = 0,
    /** Single-writer monotonic revision counter. */
    val revision: Int = 0,
)

/**
 * A proposed mutation emitted by a slow-path agent. Carries source [sourceTurnId]
 * and [age] so the orchestrator can apply the staleness guard. Pure: [applyTo]
 * computes the next immutable state and bumps the revision; it never mutates in place.
 */
data class StateDelta(
    val sourceTurnId: Int,
    val age: Int = 0,
    val addGrammarErrors: List<ErrorRecord> = emptyList(),
    val pronFluency: PronFluency? = null,
    val addVocabTargets: List<VocabTarget> = emptyList(),
    val addVisualContext: List<VisualContextItem> = emptyList(),
    val addDeferredCorrections: List<Correction> = emptyList(),
    val addTurnHistory: List<TurnRecord> = emptyList(),
    /**
     * Consolidation result. [consolidated] true means this delta came from the consolidate
     * agent and REPLACES ruleGap/practiceTargets (a diagnosis is a snapshot, not an
     * accumulation) and stamps lastConsolidatedTurnId. A null [ruleGap] with consolidated=true
     * clears a stale diagnosis — the learner may have stopped making that mistake.
     */
    val consolidated: Boolean = false,
    val ruleGap: String? = null,
    val practiceTargets: List<String> = emptyList(),
    val proficiencyEstimate: String? = null,
) {
    fun applyTo(state: LearnerState): LearnerState = state.copy(
        profile = if (proficiencyEstimate != null) state.profile.copy(proficiencyEstimate = proficiencyEstimate) else state.profile,
        grammarErrors = state.grammarErrors + addGrammarErrors,
        pronFluency = pronFluency ?: state.pronFluency,
        vocabTargets = state.vocabTargets + addVocabTargets,
        visualContext = state.visualContext + addVisualContext,
        deferredCorrections = state.deferredCorrections + addDeferredCorrections,
        turnHistory = state.turnHistory + addTurnHistory,
        ruleGap = if (consolidated) ruleGap else state.ruleGap,
        practiceTargets = if (consolidated) practiceTargets else state.practiceTargets,
        lastConsolidatedTurnId = if (consolidated) sourceTurnId else state.lastConsolidatedTurnId,
        revision = state.revision + 1,
    )
}
