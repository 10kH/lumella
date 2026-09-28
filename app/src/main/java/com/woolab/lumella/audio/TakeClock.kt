package com.woolab.lumella.audio

/**
 * One take's clock as the VIDEO saw it, for the voice taps ([WavTap]) that are separate files
 * from the video and only line up in the edit if they count time the way it does.
 *
 * The video is not recording at every moment of a take:
 *  - **Before the first frame of each segment.** The recorder reports Start ~1.5s before the
 *    first frame reaches the file (the camera opens only when the recording binds): measured
 *    2026-09-28 as 1.45s and 1.50s on the two segments of one take. A clock that started at
 *    Start ran 2.6s long over that take — 135.0s of voice against 132.4s of video.
 *  - **At every photo turn**, while one segment closes and the next comes up. Counting that
 *    pushed the tutor a further 1.5s late at every photo (149.7s WAV against 146.6s of video).
 *
 * So the clock rolls only between a segment's first frame ([rolling]) and the request to stop
 * it ([dark]), and at each segment's end it takes the recorder's own measured length
 * ([segmentEnded]) — the frames written after the stop was requested are counted too, and any
 * estimate error is gone before the next segment starts.
 *
 * Without a camera ([followVideo] false) it is simply the wall clock from construction.
 */
class TakeClock(
    followVideo: Boolean,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    /** Video time at the moment of the call, and whether the video was recording then. */
    data class Stamp(val elapsedMs: Long, val dark: Boolean)

    /** Video time at [anchorWallMs], or the frozen video time while dark. */
    private var baseMs = 0L
    /** Wall time the clock has been rolling from; null while the video is not recording. */
    private var anchorWallMs: Long? = if (followVideo) null else nowMs()
    /** Exact length of the segments already closed, as the recorder measured them. */
    private var closedMs = 0L

    @Synchronized
    fun stamp(): Stamp {
        val anchor = anchorWallMs ?: return Stamp(baseMs, dark = true)
        return Stamp(baseMs + (nowMs() - anchor), dark = false)
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
    }

    /** A stop was requested: freeze at the current estimate. Idempotent. */
    @Synchronized
    fun dark() {
        val anchor = anchorWallMs ?: return
        baseMs += nowMs() - anchor
        anchorWallMs = null
    }

    /** The segment closed having recorded exactly [recordedMs]. */
    @Synchronized
    fun segmentEnded(recordedMs: Long) {
        closedMs += recordedMs
        baseMs = closedMs
        anchorWallMs = null
    }
}
