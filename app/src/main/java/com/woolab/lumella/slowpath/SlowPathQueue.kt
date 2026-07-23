package com.woolab.lumella.slowpath

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Capture plumbing for the slow path (plan P2). The realtime (fast) loop produces
 * one [SlowPathTask] per completed turn; slow-path pedagogical agents (P3) consume
 * tasks off the critical path. Pure JVM logic so it is unit-testable.
 */

/** Monotonic per-session turn id source. Reset on session (re)creation. */
class TurnTracker {
    private val counter = AtomicInteger(0)

    /** Returns the next turn id (1-based, strictly increasing within a session). */
    fun next(): Int = counter.incrementAndGet()

    /** Current turn id without advancing (0 before the first turn). */
    fun current(): Int = counter.get()

    /** Reset to the pre-session baseline (call on WebSocket session (re)creation). */
    fun reset() {
        counter.set(0)
    }
}

/**
 * A unit of work for the slow path: the learner utterance, Ella's reply, and any
 * image attached to the turn. [turnId] threads through to StateDelta.sourceTurnId
 * so the staleness guard (P4) can compute correction age.
 */
data class SlowPathTask(
    val turnId: Int,
    val userTranscript: String,
    val ellaTranscript: String? = null,
    val imageBase64: String? = null,
)

/** Thread-safe FIFO hand-off from the fast loop to slow-path agents. */
class SlowPathQueue {
    private val queue = ConcurrentLinkedQueue<SlowPathTask>()

    fun enqueue(task: SlowPathTask) {
        queue.add(task)
    }

    /** Removes and returns the head task, or null if empty (non-blocking). */
    fun poll(): SlowPathTask? = queue.poll()

    fun isEmpty(): Boolean = queue.isEmpty()

    fun size(): Int = queue.size
}
