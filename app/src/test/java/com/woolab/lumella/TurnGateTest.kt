package com.woolab.lumella

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnGateTest {

    @Test
    fun `a tap with no detected speech claims nothing`() {
        assertFalse(TurnGate().claimSpeech())
    }

    @Test
    fun `speech is claimable exactly once`() {
        val gate = TurnGate()
        gate.onSpeechDetected()
        assertTrue(gate.claimSpeech())
        assertFalse("one utterance, one turn", gate.claimSpeech())
    }

    @Test
    fun `only one of two racing callers may publish the turn`() {
        // The real case, not a hypothetical: the wearer taps because they have just finished,
        // at the instant VAD's silence window expires. Both getting through means two
        // response.create for one utterance, and the second cancels the reply already in
        // flight — the tutor starting a sentence, cutting itself off and starting again.
        repeat(200) {
            val gate = TurnGate()
            gate.onSpeechDetected()
            val winners = AtomicInteger(0)
            val go = CountDownLatch(1)
            val ready = CountDownLatch(2)
            val threads = List(2) {
                Thread {
                    ready.countDown()
                    go.await()
                    if (gate.claimSpeech()) winners.incrementAndGet()
                }.apply { isDaemon = true; start() }
            }
            ready.await()
            go.countDown()
            threads.forEach { it.join(5_000) }
            assertEquals("exactly one caller may publish", 1, winners.get())
        }
    }

    @Test
    fun `a lost session forgets that it heard anything`() {
        // The flag used to survive a socket death, so after the reconnect a tap in silence
        // passed the guard and the tutor answered nothing — the fault the guard exists for.
        val gate = TurnGate()
        gate.onSpeechDetected()
        gate.onSessionLost()
        assertFalse("a new session has heard nothing", gate.claimSpeech())
    }

    @Test
    fun `speaking cancels a pending exit`() {
        // Talking to the tutor is not trying to leave. Without this an accidental double tap
        // stayed armed while the wearer spoke, and the ordinary tap that ends their turn quit
        // the app mid-conversation.
        val gate = TurnGate()
        gate.armExit(deadlineMs = 10_000L)
        gate.onSpeechDetected()
        assertEquals(0L, gate.exitDeadlineMs())
    }

    @Test
    fun `a lost session cancels a pending exit too`() {
        // A dead session can never deliver the speech that would have cancelled it, so an arm
        // left behind would greet the wearer on their next tap after reconnecting.
        val gate = TurnGate()
        gate.armExit(deadlineMs = 10_000L)
        gate.onSessionLost()
        assertEquals(0L, gate.exitDeadlineMs())
    }

    @Test
    fun `an ordinary tap cancels a pending exit`() {
        val gate = TurnGate()
        gate.armExit(deadlineMs = 10_000L)
        gate.disarmExit()
        assertEquals(0L, gate.exitDeadlineMs())
    }

    @Test
    fun `arming does not fabricate speech, and speech does not arm an exit`() {
        val gate = TurnGate()
        gate.armExit(deadlineMs = 10_000L)
        assertFalse(gate.claimSpeech())

        val other = TurnGate()
        other.onSpeechDetected()
        assertEquals(0L, other.exitDeadlineMs())
    }
}
