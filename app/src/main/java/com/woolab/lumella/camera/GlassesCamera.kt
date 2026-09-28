package com.woolab.lumella.camera

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.util.Range
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.CameraState
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CameraX single-shot JPEG capture for the glasses' left-touchpad photo action.
 *
 * **The camera is bound only for the duration of one shot.**
 *
 * The glasses have a single camera and only one app may hold it. The earlier version bound
 * `ImageCapture` in `init` and kept it for the whole Activity lifetime, mirroring LEGACY
 * ELLA's `initCamera()`. With both apps installed that meant whichever launched first owned
 * the camera forever and the other app's `takePicture()` queued silently — no error, no
 * callback, just a status stuck on "Capturing..." (reported from the field 2026-07-28 for
 * ELLA, and the same stall was seen here).
 *
 * Binding per shot and unbinding straight after keeps the camera free the rest of the time,
 * which also avoids holding a power-hungry sensor open on a battery-constrained device. The
 * cost is the bind latency (~sub-second) on each capture.
 *
 * Not exercised by JVM unit tests (real camera stack); verify on device.
 */
class GlassesCamera(context: Context, private val lifecycleOwner: LifecycleOwner) {

    private val appContext = context.applicationContext
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "lumella-camera").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Guards the bind-per-shot photo path only. A recording does NOT set this — see [segmentAndCapture]. */
    private val capturing = AtomicBoolean(false)

    /** Non-null only while a video recording is in flight. Main-thread only. */
    private var activeRecording: Recording? = null
    private var recordingProvider: ProcessCameraProvider? = null
    /** The clock of the take [activeRecording] belongs to. Main-thread only. */
    private var activeClock: RecordingClock? = null

    /**
     * The in-flight segment's file; non-null while recording. A photo turn during a take closes
     * this segment and lifts a frame out of it — see [segmentAndCapture]. (Photos used to fail
     * outright while recording, a red "Capture error" on 2026-09-14.)
     */
    @Volatile private var recordingFile: File? = null

    /** Runs once the in-flight recording finalises — how a segment boundary chains to the next. */
    private var pendingAfterFinalize: (() -> Unit)? = null

    /**
     * Bumped by every take-level [startRecording] and [stopRecording]. A photo turn resumes its
     * take only if this has not moved since the turn began: a stop that lands while the frame is
     * being lifted — no recording in flight, so nothing to stop — used to be lost, and the resume
     * then started a segment nothing would ever stop. Main-thread only.
     */
    private var takeGeneration = 0

    /**
     * What a take's recording is doing, for anything that must stay on its clock — the voice
     * taps, via [com.woolab.lumella.audio.TakeClock], which explains why each event is the one it
     * is. Passed per take and carried from segment to segment, so a late event from one take can
     * never land on the next take's clock.
     */
    interface RecordingClock {
        /**
         * A segment's first frame is in the file. Also sent when nothing will record for this
         * take (start failed, camera busy, recording ended by itself): the voices then follow
         * the wall clock rather than wait for a video that is not coming.
         */
        fun rolling(segmentRecordedMs: Long)
        /** A stop was requested (photo turn or end of take); frames may still land briefly. */
        fun dark()
        /** A segment closed having recorded exactly [recordedMs], by the recorder's own count. */
        fun segmentEnded(recordedMs: Long)
    }

    /**
     * Captures a single JPEG frame; [onCaptured] receives raw JPEG bytes.
     * Safe to call from any thread; binding is marshalled to the main thread as CameraX requires.
     */
    fun captureImage(onCaptured: (ByteArray) -> Unit, onError: (String) -> Unit) {
        val live = recordingFile
        if (live != null) {
            // This hardware binds ONE use case at a time, so a still cannot be taken while the
            // recording holds the camera, and an in-progress MP4 has no moov atom to read a
            // frame back from. Close the current segment, lift the frame out of it, and open the
            // next one. The take continues as consecutive files that join in the edit, and the
            // wearer keeps both the footage and the image turn.
            Log.i(TAG, "capture during recording; closing segment to lift a frame")
            segmentAndCapture(live, onCaptured, onError)
            return
        }
        if (!capturing.compareAndSet(false, true)) {
            onError("Capture already in progress")
            return
        }
        mainHandler.post {
            Log.i(TAG, "capture requested; lifecycle=${lifecycleOwner.lifecycle.currentState}")
            bindAndCapture(onCaptured, onError)
        }
    }

    private fun bindAndCapture(onCaptured: (ByteArray) -> Unit, onError: (String) -> Unit) {
        val providerFuture = ProcessCameraProvider.getInstance(appContext)
        providerFuture.addListener({
            var provider: ProcessCameraProvider? = null
            try {
                provider = providerFuture.get()
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                provider.unbindAll()
                val camera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, capture)

                // bindToLifecycle returns before the capture session finishes configuring.
                // Taking the picture immediately produced "Failed to submit capture request"
                // (on-device 2026-07-28) — with the previous bind-at-startup design the gap
                // was hidden by minutes of idle time. Wait for CameraState.OPEN instead.
                awaitCameraOpen(camera.cameraInfo.cameraState) { opened ->
                    // Not fatal: some devices never publish OPEN for a capture-only session.
                    // Try anyway — takePicture has its own retry for a not-yet-ready session.
                    if (!opened) Log.w(TAG, "camera never reported OPEN; attempting capture anyway")
                    takeWithRetry(capture, provider, attempt = 1, onCaptured = onCaptured, onError = onError)
                }
            } catch (e: Exception) {
                release(provider)
                onError("Camera bind failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(appContext))
    }

    /**
     * `takePicture` right after bind can fail with "Failed to submit capture request" while the
     * capture session is still configuring (on-device 2026-07-28). Retry a couple of times
     * before giving up rather than surfacing a transient race as a user-visible failure.
     */
    private fun takeWithRetry(
        capture: ImageCapture,
        provider: ProcessCameraProvider?,
        attempt: Int,
        onCaptured: (ByteArray) -> Unit,
        onError: (String) -> Unit,
    ) {
        capture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            val bytes = try {
                                val buffer = image.planes[0].buffer
                                ByteArray(buffer.remaining()).also(buffer::get)
                            } catch (e: Exception) {
                                null
                            } finally {
                                image.close()
                            }
                            release(provider)
                            if (bytes != null) onCaptured(bytes) else onError("Image read failed")
                        }

                override fun onError(exception: ImageCaptureException) {
                    if (attempt < CAPTURE_MAX_ATTEMPTS) {
                        Log.w(TAG, "capture attempt $attempt failed (${exception.message}); retrying")
                        mainHandler.postDelayed(
                            { takeWithRetry(capture, provider, attempt + 1, onCaptured, onError) },
                            CAPTURE_RETRY_DELAY_MS,
                        )
                        return
                    }
                    release(provider)
                    onError("Capture failed: ${exception.message}")
                }
            },
        )
    }


    /**
     * Invokes [onResult] once the camera reports [CameraState.Type.OPEN], or with `false`
     * after [CAMERA_OPEN_TIMEOUT_MS]. Observed on the main thread (LiveData requirement)
     * and unsubscribed exactly once so a late state change cannot fire the callback twice.
     */
    private fun awaitCameraOpen(
        state: androidx.lifecycle.LiveData<CameraState>,
        onResult: (Boolean) -> Unit,
    ) {
        val settled = java.util.concurrent.atomic.AtomicBoolean(false)
        lateinit var observer: Observer<CameraState>
        val timeout = Runnable {
            if (settled.compareAndSet(false, true)) {
                state.removeObserver(observer)
                onResult(false)
            }
        }
        observer = Observer { s ->
            Log.d(TAG, "cameraState=${s?.type} err=${s?.error?.code}")
            if (s?.type == CameraState.Type.OPEN && settled.compareAndSet(false, true)) {
                mainHandler.removeCallbacks(timeout)
                state.removeObserver(observer)
                onResult(true)
            }
        }
        state.observe(lifecycleOwner, observer)
        mainHandler.postDelayed(timeout, CAMERA_OPEN_TIMEOUT_MS)
    }

    /** Unbinds so other apps (and the next shot) can take the camera. Idempotent. */
    private fun release(provider: ProcessCameraProvider?) {
        mainHandler.post {
            runCatching { provider?.unbindAll() }
                .onFailure { Log.w(TAG, "unbind failed: ${it.message}") }
            capturing.set(false)
        }
    }

    /**
     * Records first-person video to [destination] until [stopRecording].
     *
     * **Video only.** The recording used to carry the microphone too (it works — the platform's
     * one-active-input cap is not per-process here, measured 2026-09-14), but that made CameraX
     * open the mic a second time and AAC-encode it in software. Measured 2026-09-28 against the
     * same take without it: the device at 234% against 204% of 400% — lumella +24 points,
     * media.swcodec +11, the audio HAL +5 — on a device the POV already pushes to its limit.
     * Both voices now come from taps of PCM the app already has —
     * the learner from [com.woolab.lumella.audio.AudioCapture], the tutor from
     * [com.woolab.lumella.audio.AudioPlayback] — on the take's clock, [clock].
     *
     * Unlike [captureImage] this keeps the camera bound for the duration — a recording IS the
     * bind. A photo turn during a take closes the segment and lifts a frame from it
     * ([segmentAndCapture]). A debug-triggered shooting aid, not wired to the touchpad.
     *
     * Not exercised by JVM unit tests (real camera stack); verify on device.
     */
    fun startRecording(destination: File, clock: RecordingClock?, onEvent: (String) -> Unit) {
        mainHandler.post {
            takeGeneration++
            startSegment(destination, clock, onEvent)
        }
    }

    /** One segment of a take. Main thread. */
    private fun startSegment(destination: File, clock: RecordingClock?, onEvent: (String) -> Unit) {
        if (activeRecording != null) {
            // This take will not record, so its voices must not wait for a first frame.
            clock?.rolling(0)
            onEvent("busy: already recording")
            return
        }
        val providerFuture = ProcessCameraProvider.getInstance(appContext)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val recorder = Recorder.Builder()
                    // Cap the encoder's bitrate. Recording both layers at full rate left
                    // cameraserver at 116% CPU with 77% of four cores idle, and the tutor's
                    // replies dragged — TTFA stayed inside budget at 730ms but the speech
                    // took seconds to finish. 6 Mbps is ample for 720p footage that gets
                    // scaled down in the edit, and it buys back the headroom the realtime
                    // audio path needs.
                    .setTargetVideoEncodingBitRate(6_000_000)
                    .setQualitySelector(
                        // HIGHEST alone fails on devices whose best profile the encoder
                        // cannot sustain; the fallback keeps a lower profile usable.
                        // HD, not FHD. At 1920x1080 the POV saturates the single hardware
                        // encoder and `screenrecord` cannot get a session at all — measured
                        // 2026-09-14, the screen file froze at 3 frames while the POV kept
                        // running, and dropping the SCREEN to 640x240 did not help because
                        // the constraint is the encoder, not the pixel count. A take needs
                        // both layers, and 1280x720 is past what the film needs anyway.
                        QualitySelector.from(
                            Quality.HD,
                            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD),
                        ),
                    )
                    .build()
                // 24fps, locked — the one camera setting that moves the CPU. The camera HAL's
                // work is per frame: measured 2026-09-28 at 104.6% of a core at 24fps against
                // 122.4% at 30, and the device at 211% against 233% of 400%. [24,24] is one
                // of the four AE ranges this camera offers ([15,15] [24,24] [15,30] [30,30])
                // and the film standard, so the edit loses nothing. Noise reduction and edge
                // enhancement were measured too and left at the camera's defaults: turning
                // them off saved nothing (104.2% vs 104.9%) — they run in the ISP, not on a
                // core — and only made the picture worse. So did SD: 720x480 cost exactly
                // what 1280x720 does (HAL 102.7% both), so the resolution above stands.
                val videoCapture = VideoCapture.Builder(recorder)
                    .setTargetFrameRate(Range(POV_FPS, POV_FPS))
                    .build()
                    .apply {
                        // The glasses report a portrait natural orientation, so the recording
                        // lands with rotation=-90 in its metadata and every player shows it on
                        // its side. Pin the rotation here rather than fixing it per file in the
                        // edit, which is a step easy to forget on one take out of twelve.
                        //
                        // Determined by looking at the picture, not the dimensions. ROTATION_90
                        // removed the metadata and produced a landscape-shaped file, which read
                        // as correct, but the scene inside was still on its left. ROTATION_270
                        // then stamped rotation=-180 and stood it on its head. ROTATION_180 is
                        // what leaves the wearer's view upright: stored 1280x720 with 90° rotation
                        // metadata, it plays as an upright 720x1280 portrait (QuickTime and
                        // ffmpeg, checked 2026-09-28).
                        targetRotation = Surface.ROTATION_180
                    }
                // VideoCapture binds ALONE. This camera refuses every second use case
                // beside it — both ImageCapture and ImageAnalysis come back with
                //   "No supported surface combination is found for camera device - Id : 0"
                // (measured 2026-09-14). Photo turns during a take are served by pulling a
                // frame out of the recording instead; see [captureImage].
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    videoCapture,
                )
                recordingFile = destination
                recordingProvider = provider
                activeClock = clock
                // Main executor: the listener runs on one thread, so this needs no guard.
                var rolled = false
                activeRecording = videoCapture.output
                    .prepareRecording(appContext, FileOutputOptions.Builder(destination).build())
                    .start(ContextCompat.getMainExecutor(appContext)) { event ->
                        when (event) {
                            is VideoRecordEvent.Start -> {
                                Log.i(TAG, "recording started -> ${destination.absolutePath}")
                            }
                            // Not Start: Start comes up to ~1.5s before the first frame, while
                            // the camera is still opening. The first Status is sent once data
                            // is actually in the file.
                            is VideoRecordEvent.Status -> if (!rolled) {
                                rolled = true
                                clock?.rolling(event.recordingStats.recordedDurationNanos / 1_000_000)
                            }
                            is VideoRecordEvent.Finalize -> {
                                clock?.segmentEnded(event.recordingStats.recordedDurationNanos / 1_000_000)
                                val ok = !event.hasError()
                                Log.i(
                                    TAG,
                                    "recording finalized ok=$ok err=${event.error} " +
                                        "bytes=${event.outputResults.outputUri}",
                                )
                                releaseRecording()
                                onEvent(
                                    if (ok) "finalized ${destination.absolutePath}"
                                    else "failed code=${event.error}",
                                )
                                // Segment boundary: the frame can only be read now that the
                                // moov atom is written, and the next segment starts here.
                                val next = pendingAfterFinalize
                                pendingAfterFinalize = null
                                if (next != null) {
                                    next()
                                } else {
                                    // Ended with no photo turn behind it: a stop (the take's
                                    // voices are already closed; harmless) or the recording
                                    // ending by itself — then the video is over and the
                                    // voices carry on by the wall clock.
                                    clock?.rolling(0)
                                }
                            }
                            else -> Unit
                        }
                    }
                onEvent("recording -> ${destination.absolutePath}")
            } catch (e: Exception) {
                releaseRecording()
                // Nothing will record, so there is no video clock to wait for.
                clock?.rolling(0)
                onEvent("start failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(appContext))
    }

    /**
     * Ends the take: stops the in-flight segment, or — between the segments of a photo turn,
     * when nothing is recording — keeps the pending resume from starting. The file is only
     * complete once Finalize arrives.
     */
    fun stopRecording(onEvent: (String) -> Unit) {
        mainHandler.post {
            takeGeneration++
            val rec = activeRecording
            if (rec == null) {
                onEvent("not recording")
                return@post
            }
            activeClock?.dark()
            rec.stop()
            onEvent("stopping")
        }
    }

    /** Main-thread only. Unbinds and clears recording state; safe to call twice. */
    private fun releaseRecording() {
        activeRecording = null
        activeClock = null
        recordingFile = null
        runCatching { recordingProvider?.unbindAll() }
            .onFailure { Log.w(TAG, "unbind after recording failed: ${it.message}") }
        recordingProvider = null
        capturing.set(false)
    }

    /**
     * Ends the current segment, pulls its last frame, and starts the next segment so the take
     * keeps running. Segments are named `<take>.mp4`, `<take>-2.mp4`, ... in order.
     */
    private fun segmentAndCapture(
        current: File,
        onCaptured: (ByteArray) -> Unit,
        onError: (String) -> Unit,
    ) {
        mainHandler.post {
            val rec = activeRecording
            if (rec == null || recordingFile != current) {
                onError("Recording ended before the photo")
                return@post
            }
            val generation = takeGeneration
            val clock = activeClock
            val next = nextSegment(current)
            pendingAfterFinalize = {
                cameraExecutor.execute {
                    val bytes = frameFromRecording(current)
                    mainHandler.post {
                        if (bytes != null) onCaptured(bytes) else onError("Segment produced no frame")
                        // Resume regardless of the still: losing the rest of a take to a failed
                        // still is worse than a still that did not arrive. But not if the take
                        // was stopped (or another started) while the frame was being lifted.
                        if (generation == takeGeneration) {
                            startSegment(next, clock) { msg -> Log.i(TAG, "segment resume: $msg") }
                        } else {
                            Log.i(TAG, "take ended during a photo turn; not resuming ${next.name}")
                        }
                    }
                }
            }
            clock?.dark()
            rec.stop()
        }
    }

    /** `take.mp4` -> `take-2.mp4` -> `take-3.mp4`. Keeps segments sortable next to each other. */
    private fun nextSegment(current: File): File = File(current.parentFile, nextSegmentName(current.name))


    private fun frameFromRecording(file: File): ByteArray? {
        // The file is still being written, so the tail is usually an incomplete fragment:
        // asking for the last frame returns null while a slightly earlier timestamp decodes
        // fine. Walk backwards a little rather than giving up on the first miss.
        if (!file.exists() || file.length() == 0L) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: return null
            var bitmap: Bitmap? = null
            for (backOffMs in FRAME_BACKOFF_MS) {
                val atUs = (durationMs - backOffMs).coerceAtLeast(0L) * 1000
                bitmap = retriever.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (bitmap != null) break
            }
            bitmap?.let {
                val out = java.io.ByteArrayOutputStream()
                it.compress(Bitmap.CompressFormat.JPEG, ANALYSIS_JPEG_QUALITY, out)
                it.recycle()
                out.toByteArray()
            }
        } catch (e: Exception) {
            Log.w(TAG, "frame extraction failed: ${e.message}")
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun shutdown() {
        mainHandler.post {
            runCatching { activeRecording?.stop() }
            runCatching { ProcessCameraProvider.getInstance(appContext).get().unbindAll() }
        }
        cameraExecutor.shutdown()
    }

    internal companion object {
        /**
         * Only a trailing NUMBER is a segment index. The first version cut at the last hyphen
         * whatever followed it, so a take named "perf-baseline-pov" continued as
         * "perf-baseline-2.mp4" — the tail of the name was taken for an index, and take.sh,
         * looking for "perf-baseline-pov-2.mp4", never pulled the second segment. Measured
         * 2026-09-28: a 138s POV take came back as its first 13.7s. Any hyphenated take name
         * lost everything after the tutor's first photo.
         */
        fun nextSegmentName(currentName: String): String {
            val name = currentName.removeSuffix(".mp4")
            val tail = name.substringAfterLast("-", "")
            val n = tail.toIntOrNull()
            return if (n != null && name.contains('-')) "${name.substringBeforeLast("-")}-${n + 1}.mp4"
            else "$name-2.mp4"
        }

        private const val TAG = "lumella"

        private const val POV_FPS = 24
        private const val CAMERA_OPEN_TIMEOUT_MS = 5_000L
        private const val ANALYSIS_JPEG_QUALITY = 90

        /** How far back from the write head to look for a decodable frame, in order. */
        private val FRAME_BACKOFF_MS = longArrayOf(0, 200, 500, 1_000, 2_000)
        const val CAPTURE_MAX_ATTEMPTS = 3
        const val CAPTURE_RETRY_DELAY_MS = 600L
    }
}
