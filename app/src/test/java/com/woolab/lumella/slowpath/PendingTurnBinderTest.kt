package com.woolab.lumella.slowpath

import com.woolab.lumella.RealtimeServerEventKind
import com.woolab.lumella.RealtimeServerEventTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * P2 / AC6 fix: the per-turn image must be bound at commit time and paired with the
 * transcript that completes later, in FIFO order.
 */
class PendingTurnBinderTest {

    @Test
    fun bindsImageAtCommitAndPairsWithLaterTranscript() {
        val binder = PendingTurnBinder()
        val turnId = binder.beginTurn("imgA")
        assertEquals(1, turnId)
        assertEquals(1, binder.pendingCount())

        // Transcript completes later (after server round-trip) — image still attached.
        val task = binder.completeTurn("I see a red apple")
        assertEquals(1, task.turnId)
        assertEquals("imgA", task.imageBase64)
        assertEquals("I see a red apple", task.userTranscript)
        assertEquals(0, binder.pendingCount())
    }

    @Test
    fun pairsTurnsFifoEvenWithInterleavedCommits() {
        val binder = PendingTurnBinder()
        val t1 = binder.beginTurn("img1")
        val t2 = binder.beginTurn(null)

        val first = binder.completeTurn("first")
        val second = binder.completeTurn("second")

        assertEquals(t1, first.turnId)
        assertEquals("img1", first.imageBase64)
        assertEquals(t2, second.turnId)
        assertNull(second.imageBase64)
    }

    @Test
    fun completeWithoutCommitFallsBackToFreshTurnNoImage() {
        val binder = PendingTurnBinder()
        val task = binder.completeTurn("orphan utterance")
        assertEquals(1, task.turnId)
        assertNull(task.imageBase64)
    }

    @Test
    fun abortedPreCommitTurnDoesNotLeakIntoNextTurn() {
        val binder = PendingTurnBinder()
        binder.beginTurn("imgA") // turn 1 begins...
        binder.failTurn()        // ...but send failed pre-commit -> discard
        val t2 = binder.beginTurn("imgB")
        val task = binder.completeTurn("next utterance")
        assertEquals("next turn pairs with its own image, not the aborted one", t2, task.turnId)
        assertEquals("imgB", task.imageBase64)
    }

    @Test
    fun emptyTranscriptDrainKeepsFifoAligned() {
        val binder = PendingTurnBinder()
        binder.beginTurn("imgA") // turn 1
        binder.completeTurn("")  // empty transcript still drains turn 1
        assertEquals(0, binder.pendingCount())
        val t2 = binder.beginTurn("imgB")
        val task = binder.completeTurn("hello")
        assertEquals(t2, task.turnId)
        assertEquals("imgB", task.imageBase64)
    }

    @Test
    fun failTurnDiscardsOldestPending() {
        val binder = PendingTurnBinder()
        binder.beginTurn("img1")
        val t2 = binder.beginTurn("img2")
        assertEquals(1, binder.failTurn())
        // Next completion now pairs with the second turn.
        val task = binder.completeTurn("hello")
        assertEquals(t2, task.turnId)
        assertEquals("img2", task.imageBase64)
    }

    @Test
    fun resetClearsPendingAndTurnIds() {
        val binder = PendingTurnBinder()
        binder.beginTurn("img1")
        binder.reset()
        assertEquals(0, binder.pendingCount())
        // turnId counter restarts at 1 after reset.
        assertEquals(1, binder.beginTurn(null))
    }

    @Test
    fun mapsFailedTranscriptionEvent() {
        assertEquals(
            RealtimeServerEventKind.INPUT_TRANSCRIPT_FAILED,
            RealtimeServerEventTypes.kindOf("conversation.item.input_audio_transcription.failed"),
        )
    }
}
