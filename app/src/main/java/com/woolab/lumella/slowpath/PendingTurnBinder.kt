package com.woolab.lumella.slowpath

/**
 * Binds the per-turn image to its turn AT SEND/COMMIT TIME (plan P2, AC6 fix).
 *
 * The image must be snapshotted when the learner commits the turn (before the
 * send path clears PendingImageStore). The transcription event arrives later
 * (after the server round-trip), so reading the image at transcript time loses
 * or mis-attributes it. This binder assigns the turnId and captures the image at
 * commit, then pairs it with the transcript (FIFO: transcripts complete in commit
 * order, one user input item per turn).
 *
 * Thread-safe: commit runs on the recording-stop path, completion on the WebSocket
 * message thread.
 *
 * Invariant: assumes a single in-flight turn (turns serialized by ResponseWaitState;
 * the next turn is gated until response.done). FIFO pairing of commit->transcript is
 * valid only under that serialization. Pre-commit send failures and empty transcripts
 * MUST still drain a pending entry (failTurn / completeTurn) or the FIFO desyncs for
 * the rest of the session. Overlapping / barge-in turns would require item-id keying.
 */
class PendingTurnBinder(private val tracker: TurnTracker = TurnTracker()) {

    private data class Pending(val turnId: Int, val imageBase64: String?)

    private val pending = ArrayDeque<Pending>()

    /** Called at commit/send time. Assigns a turnId and snapshots the turn image. */
    @Synchronized
    fun beginTurn(imageBase64: String?): Int {
        val turnId = tracker.next()
        pending.addLast(Pending(turnId, imageBase64))
        return turnId
    }

    /**
     * Called when the learner-utterance transcript completes. Pairs the transcript
     * with the oldest pending turn (and its captured image). If no turn was begun
     * (e.g. transcript without a tracked commit), falls back to a fresh turnId with
     * no image so the utterance is never silently dropped.
     */
    @Synchronized
    fun completeTurn(transcript: String): SlowPathTask {
        val p = pending.removeFirstOrNull()
        val turnId = p?.turnId ?: tracker.next()
        return SlowPathTask(
            turnId = turnId,
            userTranscript = transcript,
            imageBase64 = p?.imageBase64,
        )
    }

    /** Called when transcription fails; discards the oldest pending turn. Returns its id. */
    @Synchronized
    fun failTurn(): Int? = pending.removeFirstOrNull()?.turnId

    @Synchronized
    fun pendingCount(): Int = pending.size

    /** Reset on session (re)creation. */
    @Synchronized
    fun reset() {
        pending.clear()
        tracker.reset()
    }
}
