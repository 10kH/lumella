package com.woolab.lumella.orchestration

import com.woolab.lumella.config.AblationMode
import com.woolab.lumella.state.Correction
import com.woolab.lumella.state.CorrectionStatus
import com.woolab.lumella.state.ErrorRecord
import com.woolab.lumella.state.LearnerStateStore
import com.woolab.lumella.state.StateDelta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class StateGraphOrchestratorTest {

    private fun orchestrator(
        store: LearnerStateStore,
        mode: AblationMode = AblationMode.FULL,
    ) = StateGraphOrchestrator(store, StalenessGuard(maxAgeTurns = 2, reAnchorWindowTurns = 4), mode)

    private fun grammarDelta(turnId: Int, text: String) = StateDelta(
        sourceTurnId = turnId,
        addGrammarErrors = listOf(ErrorRecord("goed", "tense", "went", turnId = turnId)),
        addDeferredCorrections = listOf(Correction(text, priority = 2, sourceAgent = "grammar", turnId = turnId)),
    )

    @Test
    fun ac3_deferredCorrectionDeliveredOnLaterTurn() {
        val store = LearnerStateStore()
        val orch = orchestrator(store)
        orch.applySlowPath(grammarDelta(turnId = 1, text = "Try: \"I went\""))

        val instr = orch.buildResponseInstructions(currentTurnId = 2, personaSummary = "You are Ella.")
        assertEquals(SteeringChannel.RESPONSE_CREATE_INSTRUCTIONS, instr.channel) // AC5
        assertTrue("correction surfaced in instructions", instr.text.contains("I went"))
        assertEquals(listOf(1), instr.deliveredCorrectionSourceTurnIds)
    }

    @Test
    fun ac5_onlyChannelIsResponseCreateInstructions() {
        val store = LearnerStateStore()
        val instr = orchestrator(store).buildResponseInstructions(1, "You are Ella.")
        // The only steering channel the orchestrator can emit is response.create.instructions.
        assertEquals(SteeringChannel.RESPONSE_CREATE_INSTRUCTIONS, instr.channel)
        assertEquals(SteeringChannel.values().toList(), listOf(SteeringChannel.RESPONSE_CREATE_INSTRUCTIONS))
    }

    @Test
    fun ac4_staleCorrectionNeverDeliveredVerbatim() {
        val store = LearnerStateStore()
        val orch = orchestrator(store)
        orch.applySlowPath(grammarDelta(turnId = 1, text = "stale-fix"))

        // Simulate agent latency >> turn cadence: deliver attempt many turns later.
        val instr = orch.buildResponseInstructions(currentTurnId = 12, personaSummary = "")
        assertFalse("age 11 > window 4 -> dropped, not delivered", instr.text.contains("stale-fix"))
        assertEquals("nothing surfaced", emptyList<Int>(), instr.deliveredCorrectionSourceTurnIds)
        assertEquals(1, orch.metric.count(StalenessOutcome.DROP))
        assertEquals(0, orch.metric.count(StalenessOutcome.DELIVER))
        // The metric tracks the evaluated (stale) age for the distribution figure, but
        // nothing was surfaced — the stale correction was dropped, never delivered.
        assertEquals(11, orch.metric.maxEvaluatedAge())
        assertTrue(instr.consumedCorrections.isNotEmpty()) // it WAS evaluated (then dropped)
    }

    @Test
    fun ac1_buildInstructionsReadsLockFreeWhileWriteHoldsLock() {
        val store = LearnerStateStore()
        val orch = orchestrator(store)
        store.apply(grammarDelta(turnId = 1, text = "seed-fix")) // revision 1

        val enteredLock = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val writer = thread(start = true) {
            store.applyWithBarrier(grammarDelta(turnId = 2, text = "mid")) {
                enteredLock.countDown()
                proceed.await(5, TimeUnit.SECONDS)
            }
        }
        assertTrue(enteredLock.await(5, TimeUnit.SECONDS))
        // Writer holds the lock; building instructions must NOT block.
        val instr = orch.buildResponseInstructions(currentTurnId = 2, personaSummary = "")
        assertTrue(instr.text.contains("seed-fix")) // observed committed pre-write snapshot
        assertFalse(instr.text.contains("mid"))      // mid not yet published
        proceed.countDown()
        writer.join(5_000)
    }

    @Test
    fun commitDeliveryConsumesExactlyEvaluatedAndMarksGrammarDelivered() {
        val store = LearnerStateStore()
        val orch = orchestrator(store)
        orch.applySlowPath(grammarDelta(turnId = 1, text = "fix1"))
        val instr = orch.buildResponseInstructions(currentTurnId = 2, personaSummary = "")
        orch.commitDelivery(instr)

        val snap = store.snapshot()
        assertTrue("consumed correction removed from queue", snap.deferredCorrections.isEmpty())
        assertEquals(CorrectionStatus.DELIVERED, snap.grammarErrors.single().status)
    }

    @Test
    fun commitDeliveryPreservesLateArrivalAddedAfterBuild() {
        val store = LearnerStateStore()
        val orch = orchestrator(store)
        orch.applySlowPath(grammarDelta(turnId = 1, text = "fix-early"))
        val instr = orch.buildResponseInstructions(currentTurnId = 2, personaSummary = "")
        // A slow agent for the SAME turn arrives LATE, after the lock-free build:
        orch.applySlowPath(grammarDelta(turnId = 1, text = "fix-late"))
        orch.commitDelivery(instr)

        val snap = store.snapshot()
        // Only the evaluated instance is consumed; the late arrival survives (no silent loss).
        assertEquals(1, snap.deferredCorrections.size)
        assertEquals("fix-late", snap.deferredCorrections.single().text)
    }

    @Test
    fun ablationImmediateOnlySurfacesNoDeferredCorrections() {
        val store = LearnerStateStore()
        val orch = orchestrator(store, AblationMode.IMMEDIATE_ONLY)
        orch.applySlowPath(grammarDelta(turnId = 1, text = "fixX"))
        val instr = orch.buildResponseInstructions(currentTurnId = 2, personaSummary = "")
        assertFalse(instr.text.contains("fixX"))
        assertTrue(instr.deliveredCorrectionSourceTurnIds.isEmpty())
    }

    @Test
    fun ablationSingleAgentDoesNotApplySlowPath() {
        val store = LearnerStateStore()
        val orch = orchestrator(store, AblationMode.SINGLE_AGENT)
        assertEquals(null, orch.applySlowPath(grammarDelta(turnId = 1, text = "nope")))
        assertEquals(0, store.snapshot().revision)
    }
    // --- B0: NO_LEARNER_STATE buffer-vs-structural semantics ---

    @Test
    fun noLearnerState_doesNotPersistToSharedStoreButBuffersCorrections() {
        val store = LearnerStateStore()
        val orch = orchestrator(store, AblationMode.NO_LEARNER_STATE)
        orch.applySlowPath(grammarDelta(turnId = 1, text = "buffer-fix"))
        // Shared structured store is never written.
        assertEquals(0, store.snapshot().revision)
        assertTrue(store.snapshot().deferredCorrections.isEmpty())
        // The ephemeral per-turn buffer surfaces the correction on the next turn.
        val instr = orch.buildResponseInstructions(currentTurnId = 2, personaSummary = "")
        assertTrue(instr.text.contains("buffer-fix"))
        assertEquals(listOf(1), instr.deliveredCorrectionSourceTurnIds)
    }

    @Test
    fun noLearnerState_bufferReplacesEachTurnNoLongitudinalAccumulation() {
        val store = LearnerStateStore()
        val orch = orchestrator(store, AblationMode.NO_LEARNER_STATE)
        orch.applySlowPath(grammarDelta(turnId = 1, text = "fix-old"))
        orch.applySlowPath(grammarDelta(turnId = 2, text = "fix-new")) // replaces, not accumulates
        val instr = orch.buildResponseInstructions(currentTurnId = 3, personaSummary = "")
        assertTrue(instr.text.contains("fix-new"))
        assertFalse("prior turn correction is not accumulated", instr.text.contains("fix-old"))
        assertEquals(listOf(2), instr.deliveredCorrectionSourceTurnIds)
    }

    @Test
    fun noLearnerState_commitClearsBufferKeepingQueueBounded() {
        val store = LearnerStateStore()
        val orch = orchestrator(store, AblationMode.NO_LEARNER_STATE)
        orch.applySlowPath(grammarDelta(turnId = 1, text = "fix1"))
        val instr = orch.buildResponseInstructions(currentTurnId = 2, personaSummary = "")
        assertTrue(instr.text.contains("fix1"))
        orch.commitDelivery(instr)
        // Buffer cleared -> next build surfaces nothing (turn-local, no aging), store untouched.
        val next = orch.buildResponseInstructions(currentTurnId = 3, personaSummary = "")
        assertFalse(next.text.contains("fix1"))
        assertTrue(next.deliveredCorrectionSourceTurnIds.isEmpty())
        assertEquals(0, store.snapshot().revision)
    }

}
