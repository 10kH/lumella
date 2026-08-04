package com.woolab.lumella

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The two pieces of state that decide whether a tap publishes a turn or closes the app.
 *
 * Both are reached from several threads — the websocket reader when VAD reports speech, the
 * touch handler when the wearer taps, the camera executor on a photo turn — and both have
 * regressed repeatedly while living as loose fields:
 *
 * The speech flag was read and cleared as two steps straddling the turn body, so a tap
 * arriving at the instant the silence window expired let both through and the second response
 * cancelled the first — the tutor starting a sentence, cutting itself off and starting again.
 * It also survived a dropped socket, so after a reconnect a tap in silence sailed through the
 * guard and the tutor answered nothing.
 *
 * The exit arm outlived the prompt that warned about it, and at one point outlived the wearer
 * speaking, so an accidental double tap followed by ordinary use closed the app.
 *
 * Kept pure and dependency-free so the rules can be tested on a plain JVM, which is what none
 * of the above had.
 */
class TurnGate {
    private val heardSpeech = AtomicBoolean(false)
    private val exitArmedUntilMs = AtomicLong(0L)

    /** Server VAD heard the learner. Also cancels any pending exit: talking is not leaving. */
    fun onSpeechDetected() {
        heardSpeech.set(true)
        exitArmedUntilMs.set(0L)
    }

    /**
     * The session went away. A new one has heard nothing and nobody armed anything in it, and
     * a dead session can never deliver the speech that would have cancelled a pending exit.
     */
    fun onSessionLost() {
        heardSpeech.set(false)
        exitArmedUntilMs.set(0L)
    }

    /**
     * Claims the turn if speech was heard, atomically. Whoever loses the race between the tap
     * and VAD's own close gets false and must publish nothing — one utterance, one turn.
     */
    fun claimSpeech(): Boolean = heardSpeech.getAndSet(false)

    /** A double tap asks before quitting; [deadlineMs] is when the offer lapses. */
    fun armExit(deadlineMs: Long) {
        exitArmedUntilMs.set(deadlineMs)
    }

    /** Cancels a pending exit — an ordinary tap means the wearer went back to using the app. */
    fun disarmExit() {
        exitArmedUntilMs.set(0L)
    }

    fun exitDeadlineMs(): Long = exitArmedUntilMs.get()
}
