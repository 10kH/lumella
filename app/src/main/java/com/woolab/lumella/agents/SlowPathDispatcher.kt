package com.woolab.lumella.agents

import com.woolab.lumella.orchestration.StateGraphOrchestrator
import com.woolab.lumella.slowpath.SlowPathQueue
import com.woolab.lumella.slowpath.SlowPathTask
import com.woolab.lumella.state.StateDelta
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
    private val client: PedagogyAgentClient,
    private val orchestrator: StateGraphOrchestrator,
    private val agents: List<PedagogyAgent> = listOf(
        GrammarAgent(),
        PronunciationFluencyAgent(),
        VisualContextAgent(),
    ),
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
        val applicable = agents.filter { it.role != "visual" || task.imageBase64 != null }
        if (applicable.isEmpty()) return
        val deltas = Collections.synchronizedList(ArrayList<StateDelta>())
        val remaining = AtomicInteger(applicable.size)

        for (agent in applicable) {
            client.analyze(agent.role, task) { result ->
                result.onSuccess { body ->
                    runCatching { agent.toStateDelta(body, task) }.getOrNull()?.let { deltas.add(it) }
                }
                if (remaining.decrementAndGet() == 0) {
                    SlowPathCoalescer.coalesce(deltas.toList())?.let { orchestrator.applySlowPath(it) }
                }
            }
        }
    }
}
