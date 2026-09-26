package com.woolab.lumella.slowpath

import com.woolab.lumella.agents.ConsolidateAgent
import com.woolab.lumella.agents.PedagogyAgentClient
import com.woolab.lumella.agents.RoutingPedagogyClient
import com.woolab.lumella.agents.SlowPathDispatcher
import com.woolab.lumella.config.AblationMode
import com.woolab.lumella.orchestration.StalenessGuard
import com.woolab.lumella.orchestration.StateGraphOrchestrator
import com.woolab.lumella.state.LearnerStateStore
import java.io.File

/**
 * The slow path, assembled in one place.
 *
 * Everything from the learner record on disk to the dispatcher that fills it: store, turn
 * numbering, orchestrator, routing between the pedagogy endpoint and the brain, and the
 * dispatcher with its consolidate agent. MainActivity used to build all of this inline across
 * thirty lines of onCreate, and on 2026-09-26 the port to this app shipped with one argument
 * missing — no ConsolidateAgent — and formed no diagnosis on the glasses. Nothing caught it
 * because nothing tested the wiring; the end-to-end test that was written afterwards had to
 * rebuild the wiring itself, which meant the test and the activity could drift apart.
 *
 * Now there is one [build]. The activity calls it with real files and clients; the test calls
 * it with a temp file and fakes. Same wiring, by construction.
 *
 * Android stays outside: the caller resolves the backing [File], constructs the endpoint client
 * from BuildConfig, and decides how to log. This class knows none of that.
 */
class SlowPathAssembly private constructor(
    val store: LearnerStateStore,
    val tracker: TurnTracker,
    val orchestrator: StateGraphOrchestrator,
    val dispatcher: SlowPathDispatcher,
    val queue: SlowPathQueue,
) {
    /** One learner turn through the slow path: enqueue, then drain everything queued. Synchronous. */
    fun dispatch(task: SlowPathTask) {
        queue.enqueue(task)
        dispatcher.drain(queue)
    }

    companion object {
        /**
         * @param backing where the learner record lives; null keeps it in memory only.
         * @param endpoint the pedagogy function (grammar, pronunciation, consolidate). Null means
         *   not configured, and every endpoint role will report unavailable rather than pretend.
         * @param brainClient the brain adapter for roles it serves (visual); null means no brain.
         * @param brainCameUp whether the brain opened a session at bootstrap; consulted per call.
         * @param onStateChanged fires after every orchestrator publish — the activity redraws the
         *   corner indicator here.
         * @param warn where dispatcher and store failures go. They never throw; they always say.
         */
        fun build(
            backing: File?,
            endpoint: PedagogyAgentClient?,
            brainClient: PedagogyAgentClient?,
            brainCameUp: () -> Boolean,
            onStateChanged: () -> Unit,
            warn: (String) -> Unit,
        ): SlowPathAssembly {
            val store = LearnerStateStore(backing = backing, warn = warn)
            // Continue numbering from what the record already holds — see TurnTracker.
            val tracker = TurnTracker(seed = store.snapshot().highestTurnId())
            val orchestrator = StateGraphOrchestrator(store, StalenessGuard(3, 20), AblationMode.FULL).apply {
                this.onStateChanged = onStateChanged
            }
            val client: PedagogyAgentClient = when {
                brainClient != null -> RoutingPedagogyClient(
                    perTurn = brainClient,
                    endpointClient = endpoint,
                    brainCameUp = brainCameUp,
                    log = warn,
                )
                endpoint != null -> endpoint
                else -> NotConfiguredClient
            }
            val dispatcher = SlowPathDispatcher(
                client,
                orchestrator,
                // The slow layer diagnoses the learner across turns and the steering carries
                // that diagnosis. Left null, the dispatcher never asks for one — which is how
                // the first on-device pass recorded three turns and no errors.
                consolidateAgent = ConsolidateAgent(),
                warn = warn,
            )
            return SlowPathAssembly(store, tracker, orchestrator, dispatcher, SlowPathQueue())
        }

        /** Neither endpoint nor brain: every call fails loudly instead of returning nothing. */
        private object NotConfiguredClient : PedagogyAgentClient {
            override fun analyze(role: String, task: SlowPathTask, callback: (Result<String>) -> Unit) {
                callback(Result.failure(IllegalStateException("slow path has no client configured for role '$role'")))
            }
        }
    }
}
