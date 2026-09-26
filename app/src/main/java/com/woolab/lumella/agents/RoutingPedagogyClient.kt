package com.woolab.lumella.agents

import com.woolab.lumella.contract.UnavailableReason
import com.woolab.lumella.slowpath.SlowPathTask

/**
 * Sends each slow-path role to the client that can actually answer it.
 *
 * lumella's per-turn roles were all answered by the luma brain through
 * [TutorBrainPedagogyClient], which re-shapes the brain's steering evidence into the
 * role-scoped JSON the dispatcher expects. Two things that adapter cannot do, both measured on
 * the glasses 2026-09-26:
 *
 *  - It has no `consolidate` role — the one ELLA added so a reasoning model could read the
 *    accumulated record and name the habit behind the slips. Asked for it, it returned `{}`.
 *  - Its grammar evidence is whatever the brain's coach chose to say, and for the particle
 *    slips a Korean learner actually makes it says nothing: "어제 친구가 만났어요" came back
 *    `corrections: []`, `focusHint: topic_elaboration`. No error recorded, so nothing ever
 *    accumulated, so the diagnosis never had a reason to form.
 *
 * Both jobs are text work on the learner's words and do not depend on the brain, so they go
 * to the same Vercel function ELLA uses, which returned 친구가 → 친구를 for that sentence. The
 * roles the brain does serve — pronunciation, and visual when a photo is attached — stay with
 * it, and fall through to the endpoint only if the brain is unreachable. If the endpoint is not
 * configured, an endpoint role reports unavailable rather than silently returning nothing.
 */
class RoutingPedagogyClient(
    private val perTurn: PedagogyAgentClient,
    private val endpointClient: PedagogyAgentClient?,
    /** Whether the brain opened a session at bootstrap. False means do not ask it at all. */
    private val brainCameUp: () -> Boolean = { true },
    /** Injected so plain-JVM tests do not touch android.util.Log. Which client answered is operational fact. */
    private val log: (String) -> Unit = { runCatching { android.util.Log.i("lumella", it) } },
) : PedagogyAgentClient {

    override fun analyze(role: String, task: SlowPathTask, callback: (Result<String>) -> Unit) {
        val endpoint = endpointClient
        if (role in ENDPOINT_ROLES) {
            if (endpoint == null) {
                callback(Result.failure(SlowPathUnavailableException(UnavailableReason.SLOW_PATH_UNAVAILABLE)))
                return
            }
            log("slow path: $role turn ${task.turnId} -> endpoint")
            endpoint.analyze(role, task, callback)
            return
        }
        // Per-turn roles prefer the brain. When it is unreachable — the lab server is not on
        // the network at a booth, measured 2026-09-26 — fall through to the endpoint, which
        // serves the same roles. A failure other than unavailability is passed on as-is.
        //
        // Unreachability is remembered. A dead address on another subnet does not refuse the
        // connection, it lets it hang to the 30s timeout; three roles in sequence made one turn
        // cost ninety seconds before the endpoint was even asked. After the first unavailable
        // answer the brain is skipped for the rest of the session — a booth does not grow a
        // lab server mid-conversation.
        if (endpoint != null && (brainUnreachable.get() || !brainCameUp())) {
            log("slow path: $role turn ${task.turnId} -> endpoint (brain marked unreachable)")
            endpoint.analyze(role, task, callback)
            return
        }
        perTurn.analyze(role, task) { result ->
            val unavailable = result.exceptionOrNull() is SlowPathUnavailableException
            if (unavailable && endpoint != null) {
                brainUnreachable.set(true)
                log("slow path: $role turn ${task.turnId} -> brain unavailable, falling through to endpoint")
                endpoint.analyze(role, task, callback)
            } else {
                log("slow path: $role turn ${task.turnId} -> brain answered (${if (result.isSuccess) "ok" else result.exceptionOrNull()?.javaClass?.simpleName})")
                callback(result)
            }
        }
    }

    private val brainUnreachable = java.util.concurrent.atomic.AtomicBoolean(false)

    companion object {
        const val CONSOLIDATE_ROLE = "consolidate"
        /**
         * Roles the brain cannot serve for a Korean learner; always the endpoint. Pronunciation
         * is here because TutorBrainPedagogyClient.buildPronunciationContent returns "{}" for
         * every turn — the brain has no phoneme field — and a Result.success("{}") is not a
         * failure the fallthrough could catch. Only visual stays brain-first.
         */
        val ENDPOINT_ROLES: Set<String> = setOf("grammar", "pronunciation", CONSOLIDATE_ROLE)
    }
}
