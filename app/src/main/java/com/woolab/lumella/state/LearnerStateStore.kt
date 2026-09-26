package com.woolab.lumella.state

import java.io.File
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
 *
 * **Persistence.** With [backing] set, the store loads that file on construction and
 * rewrites it after every publish, inside the same writer lock, so what is on disk is
 * always a state that was actually published and never a torn write. Without it the
 * store behaves exactly as before — memory only — which is what the unit tests use.
 *
 * Why: without this, every launch started from `LearnerState()` and the tutor forgot the
 * learner's errors, recasts and profile between sessions. Personalization that resets
 * on restart is not personalization. The write is a whole-file replace via a temp file
 * and rename, so a crash mid-write leaves the previous good file, not a partial one.
 * A file the codec cannot read (wrong schema, corrupt) is ignored and overwritten on the
 * next publish rather than crashing the app on boot.
 */
class LearnerStateStore(
    initial: LearnerState = LearnerState(),
    private val backing: File? = null,
) {

    private val ref = AtomicReference(load(backing) ?: initial)
    private val writeLock = ReentrantLock()

    private companion object {
        fun load(file: File?): LearnerState? {
            if (file == null || !file.isFile) return null
            return runCatching { LearnerStateCodec.decode(file.readText()) }.getOrNull()
        }
    }

    /** Must be called with [writeLock] held, after [ref] has been set to [state]. */
    private fun persist(state: LearnerState) {
        val file = backing ?: return
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(LearnerStateCodec.encode(state))
            if (!tmp.renameTo(file)) {
                // Some filesystems refuse rename-over; fall back to copy + delete.
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }
        // Best-effort: a failed save must never break the voice loop that just published.
    }

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
            persist(next)
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
            persist(next)
            return next
        }
    }
}
