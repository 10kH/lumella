package com.woolab.lumella.audio

/**
 * One take's clock as the VIDEO saw it, for the voice taps ([WavTap]) that are separate files
 * from the video and only line up in the edit if they count time the way it does.
 *
 * The video is not recording at every moment of a take:
 *  - **Before the first frame of each segment.** The recorder reports Start up to ~1.5s before
 *    the first frame reaches the file (the camera opens only when the recording binds): measured
 *    2026-09-28 at 0.6-1.5s. A clock that started at Start ran 2.6s long over one take — 135.0s
 *    of voice against 132.4s of video.
 *  - **At every photo turn**, while one segment closes and the next comes up. Counting that
 *    pushed the tutor a further 1.5s late at every photo (149.7s WAV against 146.6s of video).
 *
 * So the clock rolls only between a segment's first frame ([firstFrame]) and the request to stop
 * it ([dark]), and at each segment's end it takes the recorder's own measured length
 * ([segmentEnded]) — the frames written after the stop was requested are counted too, and any
 * estimate error is gone before the next segment starts.
 *
 * **When no video is coming** the voices are the only record, and a clock frozen for the rest of
 * the take would drop every word the learner says and stack the tutor's replies back to back.
 * The camera says so when it knows ([noVideo]: busy, start failed, recording ended by itself), and
 * a video that shows no frame for [giveUpAfterMs] is given up on without being told. Either way
 * the clock rolls on by the wall, continuing from where it stood.
 *
 * **If a frame comes after the clock gave up,** the video is the reference again and the clock
 * steps back to it. That is the one time video time moves backwards, and [Stamp.epoch] counts it
 * so the taps can cut back to the new position ([WavTap]) rather than stay ahead for good.
 *
 * Without a camera ([followVideo] false) it is simply the wall clock from construction.
 */
class TakeClock(
    followVideo: Boolean,
    private val giveUpAfterMs: Long = GIVE_UP_AFTER_MS,
    private val warn: (String) -> Unit = {},
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    /**
     * Video time at the moment of the call, whether the video was recording then, and how many
     * times the clock has stepped back so far.
     */
    data class Stamp(val elapsedMs: Long, val dark: Boolean, val epoch: Int = 0)

    /** Video time at [anchorWallMs], or the frozen video time while dark. */
    private var baseMs = 0L
    /** Wall time the clock has been rolling from; null while the video is not recording. */
    private var anchorWallMs: Long? = null
    /** Wall time the clock went dark; null while rolling. */
    private var darkSinceMs: Long? = null
    /** Exact length of the segments already closed, as the recorder measured them. */
    private var closedMs = 0L
    /**
     * The clock is on the wall because the video was given up on, and has not seen a frame since:
     * the next [firstFrame] is a step back to the video.
     */
    private var gaveUp = false
    private var epoch = 0

    init {
        val now = nowMs()
        if (followVideo) darkSinceMs = now else anchorWallMs = now
    }

    @Synchronized
    fun stamp(): Stamp {
        val now = nowMs()
        val since = darkSinceMs
        if (anchorWallMs == null && since != null && now - since > giveUpAfterMs) {
            warn("take clock: no video frame for ${now - since}ms; voices follow the wall clock")
            anchorWallMs = since
            darkSinceMs = null
            gaveUp = true
        }
        val anchor = anchorWallMs ?: return Stamp(baseMs, dark = true, epoch = epoch)
        return Stamp(baseMs + (now - anchor), dark = false, epoch = epoch)
    }

    /**
     * A segment's first frame is in the file; it had recorded [segmentRecordedMs] by then
     * (normally ~0). Later calls within the same segment are ignored — unless the clock had given
     * up on the video, in which case it steps back to it.
     */
    @Synchronized
    fun firstFrame(segmentRecordedMs: Long) {
        if (anchorWallMs != null && !gaveUp) return
        if (gaveUp) {
            warn("take clock: a video frame came after all; back on the video's clock")
            epoch++
            gaveUp = false
        }
        baseMs = closedMs + segmentRecordedMs
        anchorWallMs = nowMs()
        darkSinceMs = null
    }

    /**
     * Nothing will record for this take from here: roll on by the wall from where the clock
     * stands. A no-op while already rolling (including after a give-up).
     */
    @Synchronized
    fun noVideo() {
        if (anchorWallMs != null) return
        anchorWallMs = nowMs()
        darkSinceMs = null
    }

    /** A stop was requested: freeze at the current estimate. Idempotent. */
    @Synchronized
    fun dark() {
        val anchor = anchorWallMs ?: return
        val now = nowMs()
        baseMs += now - anchor
        anchorWallMs = null
        darkSinceMs = now
    }

    /**
     * The segment closed having recorded exactly [recordedMs]. Normally the clock takes that as
     * the truth. On a given-up clock the voices have been following the wall, and they keep doing
     * so — the step back waits for an actual frame.
     */
    @Synchronized
    fun segmentEnded(recordedMs: Long) {
        closedMs += recordedMs
        val now = nowMs()
        val anchor = anchorWallMs
        if (gaveUp) {
            if (anchor != null) baseMs += now - anchor
        } else {
            baseMs = closedMs
        }
        if (anchor != null) darkSinceMs = now
        anchorWallMs = null
    }

    companion object {
        /** Longer than any start (<=1.5s) or photo turn (~2s) the camera actually takes. */
        const val GIVE_UP_AFTER_MS = 5_000L
    }
}
