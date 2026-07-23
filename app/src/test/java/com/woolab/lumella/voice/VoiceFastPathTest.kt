package com.woolab.lumella.voice

import com.woolab.lumella.config.AblationMode
import com.woolab.lumella.contract.BrainCapabilities
import com.woolab.lumella.contract.BrainConnection
import com.woolab.lumella.contract.BrainConnectionState
import com.woolab.lumella.contract.BrainCredentialsProvider
import com.woolab.lumella.contract.BrainSession
import com.woolab.lumella.contract.ImageContext
import com.woolab.lumella.contract.SessionPolicy
import com.woolab.lumella.contract.SteeringEvidence
import com.woolab.lumella.contract.SteeringResult
import com.woolab.lumella.contract.TurnEvidence
import com.woolab.lumella.contract.TutorBrain
import com.woolab.lumella.contract.UnavailableReason
import com.woolab.lumella.agents.TutorBrainPedagogyClient
import com.woolab.lumella.orchestration.StalenessGuard
import com.woolab.lumella.orchestration.StateGraphOrchestrator
import com.woolab.lumella.slowpath.SlowPathTask
import com.woolab.lumella.state.LearnerStateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceFastPathTest {

    private class RecordingTransport : RealtimeTransport {
        val sent = mutableListOf<String>()
        override fun sendInstructions(instructions: String) { sent.add(instructions) }
    }

    private class ScriptedBrain(
        private val steeringResult: () -> SteeringResult,
        private val onSubmit: ((TurnEvidence) -> Unit)? = null,
    ) : TutorBrain {
        override fun connect(provider: BrainCredentialsProvider) =
            BrainConnection(BrainConnectionState.READY, BrainCapabilities(coach = true, capabilitiesRoute = true))
        override fun startSession(policy: SessionPolicy) = BrainSession(sessionId = "s1", resumed = false)
        override fun submitTurnEvidence(evidence: TurnEvidence) { onSubmit?.invoke(evidence) }
        override fun fetchSteering(sessionId: String): SteeringResult = steeringResult()
        override fun analyzeImage(bytes: ByteArray, mime: String) = ImageContext(imageId = "img")
        override fun endSession(sessionId: String) {}
    }

    private fun fastPath(brain: TutorBrain, transport: RealtimeTransport = RecordingTransport()) = VoiceFastPath(
        orchestrator = StateGraphOrchestrator(LearnerStateStore(), StalenessGuard(2, 4), AblationMode.FULL),
        brain = brain,
        transport = transport,
        sessionId = { "s1" },
        personaSummary = "You are Luma.",
    )

    // --- (a) D-4 arbitration: brain text has NO direct path to spoken output ---

    @Test
    fun realtimeTransportExposesOnlyTheInstructionsChannel() {
        // Structural guarantee: the interface VoiceFastPath depends on has exactly one
        // method (sendInstructions) — there is no "speak"/raw-output method for brain
        // text to reach directly, by construction rather than by convention.
        val methods = RealtimeTransport::class.java.methods.filter { !it.isSynthetic && !it.isBridge }
        val nonDefault = methods.filterNot { it.isDefault }
        assertEquals(1, nonDefault.size)
        assertEquals("sendInstructions", nonDefault.single().name)
    }

    @Test
    fun brainSteeringTextOnlyReachesTransportViaComposedInstructions() {
        val marker = "BRAIN-STEERING-MARKER-42"
        val transport = RecordingTransport()
        val brain = ScriptedBrain({
            SteeringResult.Available(
                SteeringEvidence(corrections = emptyList(), hints = listOf(marker), confidence = 1.0, sourceTurnId = 1),
            )
        })
        val voice = fastPath(brain, transport)

        voice.onTurnStart(currentTurnId = 1)

        // Exactly one send; the brain's steering text is folded into that single
        // instructions payload, never delivered through any other call/channel.
        assertEquals(1, transport.sent.size)
        assertTrue(transport.sent.single().contains(marker))
    }

    // --- (b) luma-unavailable resilience ---

    @Test
    fun unavailableSteeringStillProducesPerTurnInstructionsAndSetsDegradeFlag() {
        val transport = RecordingTransport()
        val brain = ScriptedBrain({ SteeringResult.Unavailable(UnavailableReason.SLOW_PATH_UNAVAILABLE) })
        val voice = fastPath(brain, transport)

        assertFalse(voice.degraded)
        val instr = voice.onTurnStart(currentTurnId = 1)

        // Voice loop continues: instructions were still built and sent.
        assertEquals(1, transport.sent.size)
        assertEquals(
            com.woolab.lumella.orchestration.SteeringChannel.RESPONSE_CREATE_INSTRUCTIONS,
            instr.channel,
        )
        assertTrue(voice.degraded)
    }

    @Test
    fun brainThrowingOnFetchSteeringStillProducesInstructionsAndDegrades() {
        val transport = RecordingTransport()
        val brain = ScriptedBrain({ throw IllegalStateException("engine unreachable") })
        val voice = fastPath(brain, transport)

        voice.onTurnStart(currentTurnId = 1)

        assertEquals(1, transport.sent.size)
        assertTrue(voice.degraded)
    }

    @Test
    fun coachUnsupportedDegradesButVoiceLoopContinuesAcrossMultipleTurns() {
        val transport = RecordingTransport()
        val brain = ScriptedBrain({ SteeringResult.Unavailable(UnavailableReason.COACH_UNSUPPORTED) })
        val voice = fastPath(brain, transport)

        voice.onTurnStart(currentTurnId = 1)
        voice.onTurnStart(currentTurnId = 2)
        voice.onTurnStart(currentTurnId = 3)

        assertEquals(3, transport.sent.size)
        assertTrue(voice.degraded)
    }

    @Test
    fun submitTurnEvidenceIsFireAndForgetOnBrainFailure() {
        val brain = ScriptedBrain(
            steeringResult = { SteeringResult.Unavailable(UnavailableReason.NOT_READY) },
            onSubmit = { throw IllegalStateException("submit failed") },
        )
        val voice = fastPath(brain)

        // Must not throw despite the brain failing on submit.
        voice.submitTurnEvidence(TurnEvidence(turnId = 1, learnerTranscript = "hi"))
    }

    // --- (c) submitTurnEvidence idempotency: VoiceFastPath is the SINGLE submitter ---

    @Test
    fun exactlyOneSubmitPerTurnAcrossAMultiRoleDispatchCycle() {
        // TutorBrainPedagogyClient.analyze() (per-role: grammar/pronunciation/visual)
        // must never itself call submitTurnEvidence — only VoiceFastPath does, once
        // per turn. Simulate a full dispatch cycle (three role analyses plus the
        // fast path's own single submit) and assert the brain sees exactly one.
        val submitCount = java.util.concurrent.atomic.AtomicInteger(0)
        val brain = ScriptedBrain(
            steeringResult = { SteeringResult.Available(SteeringEvidence(corrections = emptyList(), hints = emptyList(), confidence = 1.0, sourceTurnId = 1)) },
            onSubmit = { submitCount.incrementAndGet() },
        )
        val voice = fastPath(brain)
        val pedagogyClient = TutorBrainPedagogyClient(brain, sessionId = { "s1" })
        val task = SlowPathTask(turnId = 1, userTranscript = "hi")

        pedagogyClient.analyze("grammar", task) {}
        pedagogyClient.analyze("pronunciation", task) {}
        pedagogyClient.analyze("visual", task) {}
        voice.submitTurnEvidence(TurnEvidence(turnId = 1, learnerTranscript = "hi"))

        assertEquals(1, submitCount.get())
    }

    // --- (d) fetchSteering caller-side timeout: a hung brain must not stall the loop ---

    @Test
    fun blockingBrainDegradesWithinTimeoutBudgetInsteadOfStallingTheLoop() {
        val transport = RecordingTransport()
        val brain = ScriptedBrain({
            Thread.sleep(5_000)
            SteeringResult.Available(SteeringEvidence(corrections = emptyList(), hints = emptyList(), confidence = 1.0, sourceTurnId = 1))
        })
        val voice = VoiceFastPath(
            orchestrator = StateGraphOrchestrator(LearnerStateStore(), StalenessGuard(2, 4), AblationMode.FULL),
            brain = brain,
            transport = transport,
            sessionId = { "s1" },
            personaSummary = "You are Luma.",
            fetchSteeringTimeoutMs = 200L,
        )

        val start = System.nanoTime()
        voice.onTurnStart(currentTurnId = 1)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertTrue("expected turn to finish within timeout budget, took ${elapsedMs}ms", elapsedMs < 5_000)
        assertEquals(1, transport.sent.size)
        assertTrue(voice.degraded)
    }
}
