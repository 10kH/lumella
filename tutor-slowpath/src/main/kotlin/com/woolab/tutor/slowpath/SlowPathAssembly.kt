package com.woolab.tutor.slowpath

import java.io.File

/**
 * The slow path, assembled in one place.
 *
 * Everything from the learner record on disk to the dispatcher that fills it: store, turn
 * numbering, orchestrator, the pedagogy endpoint, and the dispatcher with its consolidate
 * agent. The luma brain is not here; it never analysed a turn, and the routing that once
 * decided which of its roles to try was removed the day it was measured. MainActivity used to build all of this inline across
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
         * @param language which language is tutored: gate, scaffold direction, and the code the
         *   pedagogy function selects its prompts by.
         * @param backing where the learner record lives; null keeps it in memory only.
         * @param endpoint the pedagogy function (grammar, pronunciation, visual, consolidate).
         *   Null means not configured, and every call fails with a message that says so.
         * @param onStateChanged fires after every orchestrator publish — the activity redraws the
         *   corner indicator here.
         * @param warn where dispatcher and store failures go. They never throw; they always say.
         */
        fun build(
            language: TutorLanguage,
            backing: File?,
            endpoint: PedagogyAgentClient?,
            onStateChanged: () -> Unit,
            warn: (String) -> Unit,
        ): SlowPathAssembly {
            val store = LearnerStateStore(backing = backing, warn = warn)
            // Continue numbering from what the record already holds — see TurnTracker.
            val tracker = TurnTracker(seed = store.snapshot().highestTurnId())
            val orchestrator = StateGraphOrchestrator(language, store, StalenessGuard(3, 20), AblationMode.FULL).apply {
                this.onStateChanged = onStateChanged
            }
            val client: PedagogyAgentClient = endpoint ?: NotConfiguredClient
            val dispatcher = SlowPathDispatcher(
                language,
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

        /** No endpoint: every call fails loudly instead of returning nothing. */
        private object NotConfiguredClient : PedagogyAgentClient {
            override fun analyze(role: String, task: SlowPathTask, callback: (Result<String>) -> Unit) {
                callback(Result.failure(IllegalStateException("slow path has no client configured for role '$role'")))
            }
        }
    }
}
