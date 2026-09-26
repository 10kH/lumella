package com.woolab.lumella.agents

import com.woolab.lumella.contract.UnavailableReason
import com.woolab.lumella.slowpath.SlowPathTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingPedagogyClientTest {

    private class Recorder(private val reply: String) : PedagogyAgentClient {
        val roles = mutableListOf<String>()
        override fun analyze(role: String, task: SlowPathTask, callback: (Result<String>) -> Unit) {
            roles += role
            callback(Result.success(reply))
        }
    }

    private val task = SlowPathTask(turnId = 1, userTranscript = "어제 친구가 만났어요")

    @Test
    fun grammarAndConsolidateGoToTheEndpointTheRestToTheBrain() {
        // Measured on the glasses 2026-09-26: the brain adapter answered "consolidate" with
        // "{}", and its grammar evidence for "어제 친구가 만났어요" was corrections=[] — the
        // particle slip a Korean learner actually makes, unseen. Each role must reach the
        // client that can actually answer it.
        val brain = Recorder("""{"errors":[]}""")
        val endpoint = Recorder("""{"ruleGap":"조사 오류","evidence":[],"practiceTargets":[]}""")
        val client = RoutingPedagogyClient(perTurn = brain, consolidate = endpoint)

        val replies = mutableMapOf<String, String>()
        for (role in listOf("grammar", "pronunciation", "visual", "consolidate")) {
            client.analyze(role, task) { replies[role] = it.getOrThrow() }
        }

        assertEquals(listOf("pronunciation", "visual"), brain.roles)
        assertEquals(listOf("grammar", "consolidate"), endpoint.roles)
        assertTrue(replies.getValue("consolidate").contains("ruleGap"))
        assertTrue(replies.getValue("grammar").contains("ruleGap"))   // the endpoint stub's reply
    }

    @Test
    fun consolidateWithoutAnEndpointFailsLoudlyInsteadOfReturningNothing() {
        // An empty reply is exactly the silent failure this class exists to end. With no
        // endpoint configured the dispatcher must see a failure it can log, not "{}".
        val brain = Recorder("""{"errors":[]}""")
        val client = RoutingPedagogyClient(perTurn = brain, consolidate = null)

        var failure: Throwable? = null
        client.analyze("consolidate", task) { failure = it.exceptionOrNull() }

        val e = failure
        assertTrue(e is SlowPathUnavailableException)
        assertEquals(UnavailableReason.SLOW_PATH_UNAVAILABLE, (e as SlowPathUnavailableException).reason)
        assertEquals(emptyList<String>(), brain.roles)
    }
    private class Unavailable : PedagogyAgentClient {
        val roles = mutableListOf<String>()
        override fun analyze(role: String, task: SlowPathTask, callback: (Result<String>) -> Unit) {
            roles += role
            callback(Result.failure(SlowPathUnavailableException(UnavailableReason.SLOW_PATH_UNAVAILABLE)))
        }
    }

    private class Broken : PedagogyAgentClient {
        override fun analyze(role: String, task: SlowPathTask, callback: (Result<String>) -> Unit) {
            callback(Result.failure(IllegalStateException("brain returned garbage")))
        }
    }

    @Test
    fun perTurnFallsThroughToTheEndpointWhenTheBrainIsUnreachable() {
        // Measured 2026-09-26: the luma brain lives on a lab address that is not on the
        // network the glasses are on, and will not be at a booth. With the brain answering
        // "unavailable" for every role, no error was ever recorded and consolidate never had
        // a reason to run. The endpoint serves the same per-turn roles, so use it.
        val brain = Unavailable()
        val endpoint = Recorder("""{"errors":[{"span":"친구가","type":"object particle","recast":"친구를"}]}""")
        val client = RoutingPedagogyClient(perTurn = brain, consolidate = endpoint)

        var reply: String? = null
        client.analyze("pronunciation", task) { reply = it.getOrThrow() }

        assertEquals(listOf("pronunciation"), brain.roles)    // the brain was tried first
        assertEquals(listOf("pronunciation"), endpoint.roles) // then the endpoint answered
        assertTrue(reply!!.contains("object particle"))       // the endpoint stub's reply
    }

    @Test
    fun aBrainFailureThatIsNotUnavailabilityIsPassedOnNotMasked() {
        // Only unavailability is a reason to try elsewhere. A malformed reply from a reachable
        // brain is a bug to surface, not a condition to paper over with a second opinion.
        val endpoint = Recorder("""{"errors":[]}""")
        val client = RoutingPedagogyClient(perTurn = Broken(), consolidate = endpoint)

        var failure: Throwable? = null
        client.analyze("pronunciation", task) { failure = it.exceptionOrNull() }

        assertTrue(failure is IllegalStateException)
        assertEquals(emptyList<String>(), endpoint.roles)
    }
    @Test
    fun anUnreachableBrainIsAskedOnceThenSkippedForTheSession() {
        // Measured 2026-09-26: a dead address on another subnet hangs each connect to the 30s
        // timeout, so three roles cost ninety seconds a turn. One unavailable answer is enough.
        val brain = Unavailable()
        val endpoint = Recorder("""{"errors":[]}""")
        val client = RoutingPedagogyClient(perTurn = brain, consolidate = endpoint)

        client.analyze("pronunciation", task) {}
        client.analyze("visual", task) {}
        client.analyze("pronunciation", task) {}

        assertEquals(listOf("pronunciation"), brain.roles)                            // asked exactly once
        assertEquals(listOf("pronunciation", "visual", "pronunciation"), endpoint.roles) // everything answered
    }
}
