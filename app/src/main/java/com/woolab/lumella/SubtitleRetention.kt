package com.woolab.lumella

import java.util.concurrent.atomic.AtomicLong

/**
 * Decides when the tutor's last subtitle may leave the screen.
 *
 * The subtitle used to vanish the instant the learner started talking, which is exactly when
 * a learner who is answering the tutor still needs to see what was said (08/05 requirement 1).
 * So a speech start no longer clears the text — it arms a retention window, and only a timer
 * that is still the CURRENT one may clear.
 *
 * Pure and dependency-free so the staleness rules are testable on a plain JVM. The rules are
 * generation-based because timers cannot be trusted to be cancelled: a posted runnable may
 * fire after a new tutor reply replaced the text, or after a reconnect started a session that
 * never armed it. Each of those bumps the generation, and a timer holding an old token is
 * refused. This is the same shape that fixed the tap/VAD race in TurnGate — check-then-act
 * over two steps loses; one atomic token comparison does not.
 *
 * This file is hand-synchronised between the two glasses apps and pinned by
 * SubtitleRetentionDriftTest against a checked-in copy, like RightTapDecision.
 */
class SubtitleRetention(val retentionMs: Long = DEFAULT_RETENTION_MS) {

    private val generation = AtomicLong(0L)

    /**
     * The learner started talking. Returns the token the scheduled clear must present when it
     * fires; anything that supersedes the text in the meantime invalidates it.
     */
    fun onSpeechStarted(): Long = generation.incrementAndGet()

    /** New tutor text is on screen. Any pending clear now refers to text that no longer exists. */
    fun onNewTutorText() {
        generation.incrementAndGet()
    }

    /**
     * The session reset or dropped. The caller blanks the screen itself (a new session has
     * nothing to retain); this only makes sure no old timer fires into the new session.
     */
    fun onSessionReset() {
        generation.incrementAndGet()
    }

    /** True when the timer holding [token] is still the one whose text is on screen. */
    fun mayClear(token: Long): Boolean = generation.get() == token

    companion object {
        /**
         * Long enough to finish reading a four-line reply after starting to answer it, short
         * enough that stale text does not sit over the wearer's view of the world.
         */
        const val DEFAULT_RETENTION_MS = 8_000L
    }
}
