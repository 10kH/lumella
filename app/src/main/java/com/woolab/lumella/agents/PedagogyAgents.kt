package com.woolab.lumella.agents

import com.woolab.lumella.slowpath.SlowPathTask
import com.woolab.lumella.state.Correction
import com.woolab.lumella.state.ErrorRecord
import com.woolab.lumella.state.PronFluency
import com.woolab.lumella.state.LearnerState
import com.woolab.lumella.state.StateDelta
import com.woolab.lumella.state.VocabTarget
import com.woolab.lumella.state.VisualContextItem
import com.woolab.lumella.util.MiniJson

/**
 * Slow-path pedagogical agents (plan P3). Each agent role maps a chat-completion
 * shaped response body (from the delegate reachable through [PedagogyAgentClient])
 * to a [StateDelta] that the orchestrator (P4) applies under the single-writer
 * lock. Pure parsing logic — JVM-unit-testable; no network here.
 *
 * Every produced StateDelta carries source turnId + age (FIX A) so the staleness
 * guard can later decide whether a deferred correction is still live.
 */
interface PedagogyAgent {
    /** Role key (grammar|pronunciation|visual). */
    val role: String

    /** Parse the chat-completions-shaped response body into a StateDelta for the given turn. */
    fun toStateDelta(responseBody: String, task: SlowPathTask): StateDelta
}

/** Extracts choices[0].message.content (the agent's JSON string) from a chat response. */
internal fun extractMessageContent(responseBody: String): String? {
    val root = MiniJson.asObject(MiniJson.parse(responseBody)) ?: return null
    val choices = MiniJson.asArray(root["choices"]) ?: return null
    val first = MiniJson.asObject(choices.firstOrNull()) ?: return null
    val message = MiniJson.asObject(first["message"]) ?: return null
    return message["content"] as? String
}

/**
 * The longitudinal agent. Reads the accumulated [LearnerState] (not a single turn) and names
 * the one grammatical pattern the learner keeps getting wrong, plus 2-4 correct forms of it
 * the tutor can use naturally. Fired every few turns by [SlowPathDispatcher], never per turn.
 *
 * Why it exists: the fast layer already recasts individual errors on the same turn (measured
 * 2026-09-18, 4/4), so per-turn "Try: went" steering from the slow layer arrived after the
 * fix and had nothing left to do. What the fast layer cannot do is remember across turns.
 */
class ConsolidateAgent : PedagogyAgent {
    override val role = "consolidate"

    override fun toStateDelta(responseBody: String, task: SlowPathTask): StateDelta {
        val content = extractMessageContent(responseBody)
        val obj = MiniJson.asObject(content?.let { MiniJson.parse(it) })
        // A response that did not parse, or parsed to something without a ruleGap KEY, is not
        // a diagnosis and must not be treated as one. Before this guard a truncated 200, an
        // empty refusal, or an envelope of the wrong shape all landed on ruleGap=null with
        // consolidated=true, which applyTo reads as "clear the diagnosis" — wiping a live one
        // mid-take with no visible cause and pushing the next attempt three turns out. Only an
        // explicit `"ruleGap": null` from the model means "no pattern".
        if (obj == null || !obj.containsKey("ruleGap")) {
            return StateDelta(sourceTurnId = task.turnId) // no-op: nothing recorded, cadence untouched
        }
        val ruleGap = (obj["ruleGap"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val targets = MiniJson.asArray(obj?.get("practiceTargets")).orEmpty()
            .mapNotNull { it as? String }.map { it.trim() }.filter { it.isNotEmpty() }.take(4)
        val cefr = (obj?.get("proficiencyEstimate") as? String)?.takeIf { it in CEFR }
        val vocab = MiniJson.asArray(obj?.get("vocabTargets")).orEmpty().mapNotNull { v ->
            val vm = MiniJson.asObject(v) ?: return@mapNotNull null
            val word = vm["word"] as? String ?: return@mapNotNull null
            VocabTarget(word = word, context = vm["context"] as? String ?: "")
        }.take(2)
        return StateDelta(
            sourceTurnId = task.turnId,
            consolidated = true,
            ruleGap = ruleGap,
            practiceTargets = if (ruleGap != null) targets else emptyList(),
            proficiencyEstimate = cefr,
            addVocabTargets = vocab,
        )
    }

    companion object {
        private val CEFR = setOf("A1", "A2", "B1", "B2", "C1")

        /**
         * The state, serialised for the agent. Only errors SINCE the last diagnosis are sent,
         * plus the current turn, so the model can see whether the pattern is still happening.
         * Sending the whole record made every diagnosis permanent: once two instances existed
         * they stayed in the window forever and the model re-affirmed the same ruleGap on
         * every call, so "cleared when the record no longer supports it" could never occur
         * and the tutor would drill the same pattern for the rest of the session.
         */
        fun serialise(state: LearnerState, currentTurnId: Int): String {
            val sb = StringBuilder()
            sb.append("currentTurn: ").append(currentTurnId).append('\n')
            sb.append("proficiencyEstimate: ").append(state.profile.proficiencyEstimate).append('\n')
            state.ruleGap?.let {
                sb.append("previousRuleGap: ").append(it)
                    .append(" (diagnosed at turn ").append(state.lastConsolidatedTurnId).append(")\n")
            }
            // The learner's own sentences for the window, so the model judges a pattern against
            // what was actually said rather than against isolated spans. A span like "goed" is
            // unambiguous; "a apple" vs "an hour" is not, and the sentence decides it.
            val turns = state.turnHistory.filter { it.turnId > state.lastConsolidatedTurnId }.takeLast(6)
            if (turns.isNotEmpty()) {
                sb.append("learnerTurnsSinceLastDiagnosis:\n")
                turns.forEach { t ->
                    sb.append("  turn ").append(t.turnId).append(": \"").append(t.userTranscript).append("\"\n")
                }
            }
            sb.append("errorsSinceLastDiagnosis:\n")
            val recent = state.grammarErrors.filter { it.turnId > state.lastConsolidatedTurnId }.takeLast(20)
            if (recent.isEmpty()) sb.append("  (none)\n")
            recent.forEach { e ->
                sb.append("  turn ").append(e.turnId).append(" | ").append(e.type)
                    .append(" | \"").append(e.span).append("\" -> \"").append(e.recast).append("\"\n")
            }
            // Deliberately NOT sent: problemPhonemes. The slow layer receives a transcript,
            // never audio, so those phonemes are what a Korean learner is statistically likely
            // to find hard in these words — not what this learner actually mispronounced.
            // Feeding a guess into the diagnosis as if it were evidence is how a diagnosis
            // stops being evidence-backed. They are still recorded; they are not grounds.
            return sb.toString()
        }
    }
}

/**
 * @param emitDeferredCorrections When true (the pre-2026-09-18 behaviour, kept for the
 *   DEFERRED_ONLY / IMMEDIATE_ONLY evaluation ablations) each error also becomes a per-turn
 *   "Try: ..." correction for the next response. When false (FULL with consolidation) errors
 *   are only RECORDED — the fast layer already recast them on the same turn, and the record
 *   is raw material for [ConsolidateAgent]'s longitudinal diagnosis, which is what the
 *   steering carries instead.
 */
class GrammarAgent(private val emitDeferredCorrections: Boolean = true) : PedagogyAgent {
    override val role = "grammar"

    override fun toStateDelta(responseBody: String, task: SlowPathTask): StateDelta {
        val content = extractMessageContent(responseBody)
        val obj = MiniJson.asObject(content?.let { MiniJson.parse(it) })
        val errors = MiniJson.asArray(obj?.get("errors")).orEmpty()
        val records = errors.mapNotNull { e ->
            val em = MiniJson.asObject(e) ?: return@mapNotNull null
            val span = em["span"] as? String ?: return@mapNotNull null
            val type = em["type"] as? String ?: "grammar"
            val recast = em["recast"] as? String ?: return@mapNotNull null
            ErrorRecord(span = span, type = type, recast = recast, turnId = task.turnId)
        }
        if (!emitDeferredCorrections) {
            return StateDelta(sourceTurnId = task.turnId, addGrammarErrors = records)
        }
        val corrections = records.map {
            Correction(
                text = "Try: \"${it.recast}\" (${it.type})",
                priority = 2,
                sourceAgent = role,
                turnId = task.turnId,
                age = 0, // age recomputed by the orchestrator/staleness guard at delivery (P4)
            )
        }
        return StateDelta(
            sourceTurnId = task.turnId,
            addGrammarErrors = records,
            addDeferredCorrections = corrections,
        )
    }
}

class PronunciationFluencyAgent(private val emitDeferredCorrections: Boolean = true) : PedagogyAgent {
    override val role = "pronunciation"

    override fun toStateDelta(responseBody: String, task: SlowPathTask): StateDelta {
        val content = extractMessageContent(responseBody)
        val obj = MiniJson.asObject(content?.let { MiniJson.parse(it) })
        val phonemes = MiniJson.stringList(obj, "problemPhonemes")
        val notes = obj?.get("notes") as? String
        // Same rule as GrammarAgent: under consolidation the record is raw material for the
        // diagnosis, not a per-turn correction. Observed 2026-09-18: with only a transcript
        // to go on, this agent wrote a grammar recast ("use bought instead of buyed") under a
        // pronunciation label and it rode the next turn as a deferred correction.
        val corrections = if (emitDeferredCorrections && phonemes.isNotEmpty()) {
            listOf(
                Correction(
                    text = notes ?: "Watch these sounds: ${phonemes.joinToString(", ")}",
                    priority = 1,
                    sourceAgent = role,
                    turnId = task.turnId,
                    age = 0,
                )
            )
        } else {
            emptyList()
        }
        return StateDelta(
            sourceTurnId = task.turnId,
            pronFluency = PronFluency(problemPhonemes = phonemes),
            addDeferredCorrections = corrections,
        )
    }
}

class VisualContextAgent : PedagogyAgent {
    override val role = "visual"

    override fun toStateDelta(responseBody: String, task: SlowPathTask): StateDelta {
        val content = extractMessageContent(responseBody)
        val obj = MiniJson.asObject(content?.let { MiniJson.parse(it) })
        val caption = obj?.get("caption") as? String ?: return StateDelta(sourceTurnId = task.turnId)
        val grounded = MiniJson.stringList(obj, "groundedObjects")
        return StateDelta(
            sourceTurnId = task.turnId,
            addVisualContext = listOf(
                VisualContextItem(turnId = task.turnId, caption = caption, groundedObjects = grounded),
            ),
        )
    }
}
