package com.woolab.lumella.state

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Single-writer shared learner-state store (plan Decision 2 / P1, AC1+AC2).
 *
 * - Writes ([apply]) are serialized by a single lock so concurrent StateDeltas
 *   never lose updates and `revision` is strictly monotonic (AC2).
 * - Reads ([snapshot]) are lock-free / non-blocking: they read an
 *   [AtomicReference] and NEVER wait on the writer lock (AC1 / F5). The fast path
 *   uses [snapshot] to compose per-response instructions without ever stalling
 *   response.create.
 *
 * The published value is an immutable [LearnerState], so a reader always observes
 * a consistent snapshot; the writer swaps in a new immutable value atomically.
 */
class LearnerStateStore(initial: LearnerState = LearnerState()) {

    private val ref = AtomicReference(initial)
    private val writeLock = ReentrantLock()

    /** Lock-free, non-blocking read of the current immutable snapshot. */
    fun snapshot(): LearnerState = ref.get()

    /** Convenience: current revision without exposing the whole snapshot. */
    fun revision(): Int = ref.get().revision

    /**
     * Apply a delta under the single writer lock and publish the new snapshot.
     * Returns the new state. Serialized: even under concurrent callers, each apply
     * reads the latest committed state inside the lock, so no update is lost.
     */
    fun apply(delta: StateDelta): LearnerState = applyWithBarrier(delta, beforePublish = null)

    /**
     * Single-writer arbitrary transform (used by the orchestrator to remove delivered
     * corrections, re-anchor, etc.). Serialized under the writer lock; revision is
     * bumped automatically if the transform did not already advance it. Reads stay lock-free.
     */
    fun update(transform: (LearnerState) -> LearnerState): LearnerState {
        writeLock.withLock {
            val current = ref.get()
            val transformed = transform(current)
            val next = if (transformed.revision == current.revision) {
                transformed.copy(revision = current.revision + 1)
            } else {
                transformed
            }
            ref.set(next)
            return next
        }
    }

    /**
     * Test seam (also the real apply path). [beforePublish], when provided, runs
     * WHILE the writer lock is held and BEFORE the new snapshot is published. Tests
     * use it to prove that [snapshot] does not block while a write is in progress.
     */
    internal fun applyWithBarrier(delta: StateDelta, beforePublish: (() -> Unit)?): LearnerState {
        writeLock.withLock {
            val current = ref.get()
            val next = delta.applyTo(current)
            beforePublish?.invoke()
            ref.set(next)
            return next
        }
    }
}
