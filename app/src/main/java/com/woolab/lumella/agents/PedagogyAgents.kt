package com.woolab.lumella.agents

import com.woolab.lumella.slowpath.SlowPathTask
import com.woolab.lumella.state.Correction
import com.woolab.lumella.state.ErrorRecord
import com.woolab.lumella.state.PronFluency
import com.woolab.lumella.state.StateDelta
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

class GrammarAgent : PedagogyAgent {
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

class PronunciationFluencyAgent : PedagogyAgent {
    override val role = "pronunciation"

    override fun toStateDelta(responseBody: String, task: SlowPathTask): StateDelta {
        val content = extractMessageContent(responseBody)
        val obj = MiniJson.asObject(content?.let { MiniJson.parse(it) })
        val phonemes = MiniJson.stringList(obj, "problemPhonemes")
        val notes = obj?.get("notes") as? String
        val corrections = if (phonemes.isNotEmpty()) {
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
