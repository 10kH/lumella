package com.woolab.lumella.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * P1 / AC2 (single-writer no-lost-update) + AC1/F5 (lock-free non-blocking read).
 */
class LearnerStateStoreTest {

    private fun vocabDelta(turnId: Int, word: String) = StateDelta(
        sourceTurnId = turnId,
        addVocabTargets = listOf(VocabTarget(word = word, context = "ctx$turnId")),
    )

    @Test
    fun applyBumpsRevisionAndAppends() {
        val store = LearnerStateStore()
        assertEquals(0, store.revision())
        store.apply(vocabDelta(1, "apple"))
        store.apply(vocabDelta(2, "banana"))
        val snap = store.snapshot()
        assertEquals(2, snap.revision)
        assertEquals(listOf("apple", "banana"), snap.vocabTargets.map { it.word })
    }

    @Test
    fun pronFluencyReplacedOnlyWhenPresent() {
        val store = LearnerStateStore()
        store.apply(StateDelta(sourceTurnId = 1, pronFluency = PronFluency(lastScore = 0.7)))
        assertEquals(0.7, store.snapshot().pronFluency.lastScore!!, 1e-9)
        // A delta without pronFluency must not wipe the existing value.
        store.apply(vocabDelta(2, "carrot"))
        assertEquals(0.7, store.snapshot().pronFluency.lastScore!!, 1e-9)
    }

    @Test
    fun concurrentDeltasNeverLoseUpdates() {
        val store = LearnerStateStore()
        val n = 64
        val start = CountDownLatch(1)
        val threads = (0 until n).map { i ->
            thread(start = true) {
                start.await()
                store.apply(vocabDelta(i, "w$i"))
            }
        }
        start.countDown()
        threads.forEach { it.join(5_000) }

        val snap = store.snapshot()
        assertEquals("revision must equal number of applies (no lost update)", n, snap.revision)
        assertEquals(n, snap.vocabTargets.size)
        assertEquals("all words distinct and present", n, snap.vocabTargets.map { it.word }.toSet().size)
    }

    @Test
    fun snapshotReadDoesNotBlockWhileWriteHoldsLock() {
        val store = LearnerStateStore()
        store.apply(vocabDelta(1, "seed")) // revision now 1
        val enteredLock = CountDownLatch(1)
        val readObserved = CountDownLatch(1)
        val readRevision = AtomicInteger(-1)

        val writer = thread(start = true) {
            store.applyWithBarrier(vocabDelta(2, "mid")) {
                // Writer holds the lock here and pauses until the reader has read.
                enteredLock.countDown()
                readObserved.await(5, TimeUnit.SECONDS)
            }
        }

        // Wait until the writer is provably holding the lock mid-apply.
        assertTrue(enteredLock.await(5, TimeUnit.SECONDS))

        // This read MUST return immediately (lock-free), observing the pre-write state.
        val snap = store.snapshot()
        readRevision.set(snap.revision)
        readObserved.countDown()
        writer.join(5_000)

        assertEquals("read returned the committed pre-write snapshot without blocking", 1, readRevision.get())
        assertEquals("write committed after the read", 2, store.revision())
    }
}
