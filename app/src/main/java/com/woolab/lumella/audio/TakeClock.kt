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
 * So the clock rolls only between a segment's first frame ([rolling]) and the request to stop
 * it ([dark]), and at each segment's end it takes the recorder's own measured length
 * ([segmentEnded]) — the frames written after the stop was requested are counted too, and any
 * estimate error is gone before the next segment starts.
 *
 * **A video that shows no frame for [giveUpAfterMs] is not coming back** (a camera that opened
 * and never delivered, a resume that never started). The clock then rolls on by the wall from
 * the moment it went dark, and says so through [warn]: the voices are the only record left,
 * and a clock frozen for the rest of the take would drop every word the learner says and stack
 * the tutor's replies back to back.
 *
 * Without a camera ([followVideo] false) it is simply the wall clock from construction.
 */
class TakeClock(
    followVideo: Boolean,
    private val giveUpAfterMs: Long = GIVE_UP_AFTER_MS,
    private val warn: (String) -> Unit = {},
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    /** Video time at the moment of the call, and whether the video was recording then. */
    data class Stamp(val elapsedMs: Long, val dark: Boolean)

    /** Video time at [anchorWallMs], or the frozen video time while dark. */
    private var baseMs = 0L
    /** Wall time the clock has been rolling from; null while the video is not recording. */
    private var anchorWallMs: Long? = null
    /** Wall time the clock went dark; null while rolling. */
    private var darkSinceMs: Long? = null
    /** Exact length of the segments already closed, as the recorder measured them. */
    private var closedMs = 0L

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
        }
        val anchor = anchorWallMs ?: return Stamp(baseMs, dark = true)
        return Stamp(baseMs + (now - anchor), dark = false)
    }

    /**
     * A segment's first frame is in the file; it had recorded [segmentRecordedMs] by then
     * (normally ~0). Later calls within the same segment are ignored.
     */
    @Synchronized
    fun rolling(segmentRecordedMs: Long) {
        if (anchorWallMs != null) return
        baseMs = closedMs + segmentRecordedMs
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

    /** The segment closed having recorded exactly [recordedMs]. */
    @Synchronized
    fun segmentEnded(recordedMs: Long) {
        closedMs += recordedMs
        baseMs = closedMs
        if (anchorWallMs != null) darkSinceMs = nowMs()
        anchorWallMs = null
    }

    companion object {
        /** Longer than any start (<=1.5s) or photo turn (~2s) the camera actually takes. */
        const val GIVE_UP_AFTER_MS = 5_000L
    }
}
