package com.woolab.lumella.agents

import com.woolab.lumella.config.AblationMode
import com.woolab.lumella.orchestration.StalenessGuard
import com.woolab.lumella.orchestration.StateGraphOrchestrator
import com.woolab.lumella.slowpath.SlowPathQueue
import com.woolab.lumella.slowpath.SlowPathTask
import com.woolab.lumella.state.LearnerStateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlowPathDispatcherTest {

    /** Fake client: returns a canned chat-completions body per role, synchronously. */
    private class FakeClient(private val byRole: Map<String, String>) : PedagogyAgentClient {
        val calledRoles = mutableListOf<String>()
        override fun analyze(role: String, task: SlowPathTask, callback: (Result<String>) -> Unit) {
            calledRoles.add(role)
            val content = byRole[role] ?: "{}"
            val escaped = content.replace("\\", "\\\\").replace("\"", "\\\"")
            callback(Result.success("""{"choices":[{"message":{"content":"$escaped"}}]}"""))
        }
    }

    private fun orchestrator(store: LearnerStateStore) =
        StateGraphOrchestrator(store, StalenessGuard(2, 4), AblationMode.FULL)

    @Test
    fun dispatchFiresAgentsCoalescesAndAppliesOneRevision() {
        val store = LearnerStateStore()
        val client = FakeClient(
            mapOf(
                "grammar" to """{"errors":[{"span":"I goed","type":"tense","recast":"I went"}]}""",
                "pronunciation" to """{"problemPhonemes":["t"],"notes":"t sound"}""",
                "visual" to """{"caption":"a park","groundedObjects":["tree"]}""",
            ),
        )
        val dispatcher = SlowPathDispatcher(client, orchestrator(store))

        dispatcher.dispatch(SlowPathTask(turnId = 1, userTranscript = "I goed", imageBase64 = "img"))

        val snap = store.snapshot()
        // 3 agents fired; coalesced into exactly one apply (single revision bump).
        assertEquals(listOf("grammar", "pronunciation", "visual"), client.calledRoles)
        assertEquals(1, snap.revision)
        assertEquals(1, snap.grammarErrors.size)
        assertEquals(1, snap.visualContext.size)
        assertEquals(listOf("t"), snap.pronFluency.problemPhonemes)
        // grammar + pronunciation each produced a deferred correction.
        assertEquals(2, snap.deferredCorrections.size)
    }

    @Test
    fun visualAgentSkippedWhenNoImage() {
        val store = LearnerStateStore()
        val client = FakeClient(mapOf("grammar" to """{"errors":[]}""", "pronunciation" to """{"problemPhonemes":[]}"""))
        val dispatcher = SlowPathDispatcher(client, orchestrator(store))

        dispatcher.dispatch(SlowPathTask(turnId = 1, userTranscript = "I am fine"))

        assertTrue("visual agent must not fire without an image", "visual" !in client.calledRoles)
        assertEquals(setOf("grammar", "pronunciation"), client.calledRoles.toSet())
    }

    @Test
    fun drainProcessesAllQueuedTurns() {
        val store = LearnerStateStore()
        val client = FakeClient(mapOf("grammar" to """{"errors":[{"span":"a","type":"t","recast":"b"}]}""", "pronunciation" to """{"problemPhonemes":[]}"""))
        val dispatcher = SlowPathDispatcher(client, orchestrator(store))
        val queue = SlowPathQueue()
        queue.enqueue(SlowPathTask(1, "first"))
        queue.enqueue(SlowPathTask(2, "second"))

        dispatcher.drain(queue)

        assertTrue(queue.isEmpty())
        assertEquals(2, store.snapshot().revision) // one apply per turn
    }
}
