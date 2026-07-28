package com.woolab.lumella.agents

import com.woolab.lumella.contract.SteeringResult
import com.woolab.lumella.contract.TutorBrain
import com.woolab.lumella.contract.UnavailableReason
import com.woolab.lumella.slowpath.SlowPathTask

/**
 * Slow-path analysis client used by [SlowPathDispatcher]/[PedagogyAgent]s. AC7 (adapted
 * for P3): direct HTTP calls from `:app` are FORBIDDEN — all slow-path evidence flows
 * through the [TutorBrain] port (see [TutorBrainPedagogyClient]), which itself may be
 * backed by a local or remote engine at runtime via dependency injection.
 */
interface PedagogyAgentClient {
    fun analyze(role: String, task: SlowPathTask, callback: (Result<String>) -> Unit)
}

/** Raised when [TutorBrain.fetchSteering] returns [SteeringResult.Unavailable]. */
class SlowPathUnavailableException(val reason: UnavailableReason) : IllegalStateException(
    "TutorBrain steering unavailable: $reason",
)

/**
 * Delegates slow-path pedagogical analysis to a [TutorBrain] instance instead of a
 * direct luma HTTP endpoint (plan P3 adaptation of the legacy EndpointPedagogyAgentClient).
 *
 * [analyze] fetches the latest [com.woolab.lumella.contract.SteeringEvidence] for the
 * current session and re-shapes it into the chat-completion-style JSON body the
 * existing [PedagogyAgent] implementations (GrammarAgent/PronunciationFluencyAgent/
 * VisualContextAgent) already know how to parse, so those parsers stay unchanged.
 *
 * This class does NOT call [TutorBrain.submitTurnEvidence]: [VoiceFastPath] is the
 * single submitter (once per turn, fire-and-forget) per [TutorBrain.submitTurnEvidence]'s
 * idempotency contract — submitting per-role here would duplicate the same turn's
 * evidence up to 3x per turn.
 *
 * Role-scoped evidence (no fabrication): [SteeringEvidence] only carries
 * `corrections`/`hints`/`focusHint`/`visual` today (see `tutor-contract/Steering.kt`).
 * Grammar consumes [SteeringEvidence.corrections] and visual consumes
 * [SteeringEvidence.visual] (real image-analysis-derived evidence, `null` when the
 * turn has none). Pronunciation has no role-scoped field yet (ETRI pronunciation
 * assessment isn't wired into luma), so it emits an empty-but-valid body rather
 * than relabeling generic `hints`/`focusHint` text as invented phonemes.
 *
 * [TutorBrain] calls are blocking (no coroutines, per contract); [analyze] runs them
 * synchronously on the caller's thread and invokes [callback] before returning.
 */
class TutorBrainPedagogyClient(
    private val brain: TutorBrain,
    private val sessionId: () -> String,
) : PedagogyAgentClient {

    override fun analyze(role: String, task: SlowPathTask, callback: (Result<String>) -> Unit) {
        val steering = try {
            brain.fetchSteering(sessionId())
        } catch (e: Exception) {
            callback(Result.failure(e))
            return
        }

        when (steering) {
            is SteeringResult.Available -> callback(Result.success(toChatResponseBody(role, steering.evidence, task)))
            is SteeringResult.Unavailable -> callback(Result.failure(SlowPathUnavailableException(steering.reason)))
        }
    }

    /** Re-shapes brain steering evidence into a role-scoped chat-completion body. */
    private fun toChatResponseBody(
        role: String,
        evidence: com.woolab.lumella.contract.SteeringEvidence,
        task: SlowPathTask,
    ): String {
        val content = when (role) {
            "grammar" -> buildGrammarContent(evidence, task)
            "pronunciation" -> buildPronunciationContent()
            "visual" -> buildVisualContent(evidence)
            else -> "{}"
        }
        val escaped = content.replace("\\", "\\\\").replace("\"", "\\\"")
        return """{"choices":[{"message":{"content":"$escaped"}}]}"""
    }

    private fun buildGrammarContent(evidence: com.woolab.lumella.contract.SteeringEvidence, task: SlowPathTask): String {
        val errors = evidence.corrections
            .filter { it.sourceTurnId == task.turnId }
            .joinToString(",") { c ->
                """{"span":"${jsonEscape(c.original)}","type":"${jsonEscape(c.errorType ?: "grammar")}","recast":"${jsonEscape(c.corrected)}"}"""
            }
        return """{"errors":[$errors]}"""
    }

    /**
     * No-fake-evidence principle: [SteeringEvidence.hints]/[SteeringEvidence.focusHint]
     * are generic coaching text, NOT per-phoneme pronunciation analysis. Until the W-1
     * coach schema defines a role-scoped `problemPhonemes` field, relabeling them as
     * phonemes would be fabricated evidence, so this always returns an empty-but-valid
     * body ([PronunciationFluencyAgent] parses it to a no-op delta).
     */
    private fun buildPronunciationContent(): String = "{}"

    /**
     * [SteeringEvidence.visual] carries real image-analysis-derived evidence (see
     * `tutor-contract`'s [com.woolab.lumella.contract.SteeringVisual] no-fabrication
     * rule) — when present, its `caption`/`salientElements` are exactly what
     * [VisualContextAgent] already expects under `caption`/`groundedObjects`. When
     * `visual` is `null` (no analyzed image for this turn), this still returns an
     * empty-but-valid body rather than fabricating a caption from
     * [SteeringEvidence.focusHint] ([VisualContextAgent] parses it to a no-op delta).
     *
     * `visibleTextBlocks` is intentionally omitted: [VisualContextAgent] does not
     * parse it today, so sending it would just be a field the agent silently ignores.
     */
    private fun buildVisualContent(evidence: com.woolab.lumella.contract.SteeringEvidence): String {
        val visual = evidence.visual ?: return "{}"
        val groundedObjects = visual.salientElements.joinToString(",") { "\"${jsonEscape(it)}\"" }
        return """{"caption":"${jsonEscape(visual.caption)}","groundedObjects":[$groundedObjects]}"""
    }

    private fun jsonEscape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}