package com.woolab.lumella.voice

import com.woolab.lumella.contract.SteeringEvidence
import com.woolab.lumella.contract.SteeringResult
import com.woolab.lumella.contract.TurnEvidence
import com.woolab.lumella.contract.TutorBrain
import com.woolab.lumella.orchestration.ResponseInstructions
import com.woolab.lumella.orchestration.StateGraphOrchestrator
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Orchestrates the realtime voice turn loop (plan P3): on turn start it builds the
 * per-response instructions via [StateGraphOrchestrator]/SteeringComposer, layers in
 * the [TutorBrain]'s slow-path [SteeringResult] (Available -> composed into the
 * instructions; Unavailable/error -> voice-only degrade, DELIVER-only, loop
 * continues), and sends the result through [RealtimeTransport] — the only output
 * channel this class knows about.
 *
 * D-4 INVARIANT: brain/steering text has NO direct path to spoken output. It is
 * folded into [ResponseInstructions.text] and handed to [RealtimeTransport.sendInstructions]
 * only; [RealtimeTransport] has no "speak" method for this class (or anything else)
 * to call directly.
 *
 * [submitTurnEvidence] is fire-and-forget: brain failures/unavailability must never
 * break the voice loop (W-1 ABSENT -> COACH_UNSUPPORTED degrade).
 *
 * [fetchSteering] is bounded on the caller side by [fetchSteeringTimeoutMs]: a slow
 * or hung brain implementation must never stall the voice loop past that budget.
 * The wait runs on a dedicated daemon single-thread executor (reused across turns,
 * not spawned per call) so a timed-out call cannot leak threads; timing out is
 * treated exactly like [SteeringResult.Unavailable] (degrade, loop continues).
 */
class VoiceFastPath(
    private val orchestrator: StateGraphOrchestrator,
    private val brain: TutorBrain,
    private val transport: RealtimeTransport,
    private val sessionId: () -> String,
    private val personaSummary: String = "",
    private val fetchSteeringTimeoutMs: Long = 1_500L,
) {

    /** True once the brain has reported/thrown Unavailable for the current turn's steering fetch. */
    @Volatile
    var degraded: Boolean = false
        private set

    /** Dedicated daemon executor reused across turns/calls; never one thread per call. */
    private val steeringExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "voice-fast-path-steering").apply { isDaemon = true }
    }

    /**
     * Turn-start hook: composes and sends per-response instructions, folding in brain
     * steering when available. Always sends instructions (voice loop never stalls on
     * the brain) and always returns the orchestrator's [ResponseInstructions] so the
     * caller can drive delivery bookkeeping ([StateGraphOrchestrator.commitDelivery]).
     */
    fun onTurnStart(currentTurnId: Int): ResponseInstructions {
        val instructions = orchestrator.buildResponseInstructions(currentTurnId, personaSummary)
        val steeringText = fetchSteeringText(currentTurnId)
        val combinedText = if (steeringText.isNullOrBlank()) {
            instructions.text
        } else {
            listOf(instructions.text, steeringText).filter { it.isNotBlank() }.joinToString("\n")
        }

        transport.sendInstructions(combinedText)
        orchestrator.commitDelivery(instructions)
        return instructions
    }

    /** Fire-and-forget submission of this turn's evidence to the brain's slow path. */
    fun submitTurnEvidence(evidence: TurnEvidence) {
        try {
            brain.submitTurnEvidence(evidence)
        } catch (_: Exception) {
            // Slow-path evidence submission must never break the voice loop.
        }
    }

    /**
     * Fetches steering, bounded by [fetchSteeringTimeoutMs] on the caller side: the
     * blocking [TutorBrain.fetchSteering] call runs on [steeringExecutor] and this
     * method waits at most [fetchSteeringTimeoutMs] for it. A timeout is treated the
     * same as [SteeringResult.Unavailable]: degrade and let the voice loop continue.
     * The timed-out future is cancelled with interruption; a truly hung
     * (non-interruptible) brain call keeps the single worker occupied, so fetches
     * queued behind it also time out to the same degrade path — the loop never stalls.
     */
    private fun fetchSteeringText(currentTurnId: Int): String? {
        val future = steeringExecutor.submit(Callable { brain.fetchSteering(sessionId()) })
        val result = try {
            future.get(fetchSteeringTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            degraded = true
            return null
        } catch (_: Exception) {
            future.cancel(true)
            degraded = true
            return null
        }

        return when (result) {
            is SteeringResult.Available -> {
                degraded = false
                composeSteeringText(result.evidence)
            }
            is SteeringResult.Unavailable -> {
                degraded = true
                null
            }
        }
    }

    private fun composeSteeringText(evidence: SteeringEvidence): String {
        val sb = StringBuilder()
        evidence.focusHint?.let { sb.append(it).append('\n') }
        evidence.hints.forEach { sb.append(it).append('\n') }
        return sb.toString().trim()
    }
}
