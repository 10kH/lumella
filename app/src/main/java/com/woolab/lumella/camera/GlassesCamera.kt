package com.woolab.lumella.camera

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
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

    /** Guards the bind-per-shot photo path only. A recording does NOT set this — see [recordingCapture]. */
    private val capturing = AtomicBoolean(false)

    /** Non-null only while a video recording is in flight. Main-thread only. */
    private var activeRecording: Recording? = null
    private var recordingProvider: ProcessCameraProvider? = null

    /**
     * The `ImageCapture` bound alongside `VideoCapture` for the duration of a recording.
     *
     * Photos and video used to share one `capturing` flag, so starting a recording made every
     * photo fail: on 2026-09-14 the wearer asked the tutor to look at what was in front of them
     * and got a red "Capture error" instead, because the recording held the camera. Binding both
     * use cases together lets a take carry the first-person video, its audio, AND the image turns
     * the product is built around — no either/or.
     */
    @Volatile private var recordingFile: File? = null
    @Volatile private var recordingWithAudio: Boolean = true

    /** Runs once the in-flight recording finalises — how a segment boundary chains to the next. */
    @Volatile private var pendingAfterFinalize: (() -> Unit)? = null

    /**
     * Captures a single JPEG frame; [onCaptured] receives raw JPEG bytes.
     * Safe to call from any thread; binding is marshalled to the main thread as CameraX requires.
     */
    fun captureImage(onCaptured: (ByteArray) -> Unit, onError: (String) -> Unit) {
        // While recording, the camera is already bound with an ImageCapture beside the
        // VideoCapture. Reuse it instead of rebinding — rebinding would tear down the recording.
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
     * **Audio IS recorded** (`withAudio`, on by default). This was assumed impossible for days:
     * `dumpsys media.audio_policy` reports `maxActiveCount: 1`, so enabling it looked certain to
     * take the mic from the voice pipeline and end the conversation the footage exists to show.
     * Measured 2026-09-14, that is wrong — the cap is not per-process here. With audio on, the
     * app stayed `Listening...`, a full turn went through (utterance echoed, tutor answered), and
     * the file came back with an AAC 48 kHz stereo track at mean -32 dB.
     *
     * Pass `withAudio = false` if a take must not capture room sound. Note the external camera is
     * filming the same scene, so its audio remains the better sync reference for the edit.
     *
     * Unlike [captureImage] this keeps the camera bound for the duration — a recording IS the
     * bind. Photo capture is therefore unavailable while recording (`captureImage` reports
     * "Capture already in progress"), which is why this is a debug-triggered shooting aid and
     * not something wired to the touchpad.
     *
     * Not exercised by JVM unit tests (real camera stack); verify on device.
     */
    fun startRecording(destination: File, withAudio: Boolean = true, onEvent: (String) -> Unit) {
        if (activeRecording != null) {
            onEvent("busy: already recording")
            return
        }
        mainHandler.post {
            val providerFuture = ProcessCameraProvider.getInstance(appContext)
            providerFuture.addListener({
                try {
                    val provider = providerFuture.get()
                    val recorder = Recorder.Builder()
                        .setQualitySelector(
                            // HIGHEST alone fails on devices whose best profile the encoder
                            // cannot sustain; the fallback keeps a lower profile usable.
                            QualitySelector.from(
                                Quality.FHD,
                                FallbackStrategy.lowerQualityOrHigherThan(Quality.SD),
                            ),
                        )
                        .build()
                    val videoCapture = VideoCapture.withOutput(recorder).apply {
                        // The glasses report a portrait natural orientation, so the recording
                        // lands with rotation=-90 in its metadata and every player shows it on
                        // its side. Pin landscape here rather than fixing it per file in the
                        // edit, which is a step easy to forget on one take out of twelve.
                        targetRotation = Surface.ROTATION_90
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
                    recordingWithAudio = withAudio
                    recordingProvider = provider
                    val pending = videoCapture.output
                        .prepareRecording(appContext, FileOutputOptions.Builder(destination).build())
                    if (withAudio) {
                        @Suppress("MissingPermission")
                        pending.withAudioEnabled()
                    }
                    activeRecording = pending
                        .start(ContextCompat.getMainExecutor(appContext)) { event ->
                            when (event) {
                                is VideoRecordEvent.Start ->
                                    Log.i(TAG, "recording started -> ${destination.absolutePath}")
                                is VideoRecordEvent.Finalize -> {
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
                                    pendingAfterFinalize?.let { next ->
                                        pendingAfterFinalize = null
                                        next()
                                    }
                                }
                                else -> Unit
                            }
                        }
                    onEvent("recording -> ${destination.absolutePath}")
                } catch (e: Exception) {
                    releaseRecording()
                    onEvent("start failed: ${e.message}")
                }
            }, ContextCompat.getMainExecutor(appContext))
        }
    }

    /** Stops an in-flight recording. The file is only complete once Finalize arrives. */
    fun stopRecording(onEvent: (String) -> Unit) {
        mainHandler.post {
            val rec = activeRecording
            if (rec == null) {
                onEvent("not recording")
                return@post
            }
            rec.stop()
            onEvent("stopping")
        }
    }

    /** Main-thread only. Unbinds and clears recording state; safe to call twice. */
    private fun releaseRecording() {
        activeRecording = null
        recordingFile = null
        runCatching { recordingProvider?.unbindAll() }
            .onFailure { Log.w(TAG, "unbind after recording failed: ${it.message}") }
        recordingProvider = null
        capturing.set(false)
    }

    /**
     * YUV_420_888 -> JPEG. `ImageAnalysis` hands out planar YUV, while the rest of the photo path
     * (and [ImageEncoder]) expects encoded JPEG bytes, so the conversion happens here rather than
     * spreading format knowledge outward.
     */
    /**
     * Ends the current segment, pulls its last frame, and starts the next segment so the take
     * keeps running. Segments are named `<take>.mp4`, `<take>-2.mp4`, ... in order.
     */
    private fun segmentAndCapture(
        current: File,
        onCaptured: (ByteArray) -> Unit,
        onError: (String) -> Unit,
    ) {
        val audio = recordingWithAudio
        val next = nextSegment(current)
        pendingAfterFinalize = {
            cameraExecutor.execute {
                val bytes = frameFromRecording(current)
                mainHandler.post {
                    if (bytes != null) onCaptured(bytes) else onError("Segment produced no frame")
                    // Resume regardless: losing the rest of a take to a failed still is worse
                    // than a still that did not arrive.
                    startRecording(next, audio) { msg -> Log.i(TAG, "segment resume: $msg") }
                }
            }
        }
        stopRecording { }
    }

    /** `take.mp4` -> `take-2.mp4` -> `take-3.mp4`. Keeps segments sortable next to each other. */
    private fun nextSegment(current: File): File {
        val name = current.nameWithoutExtension
        val base = name.substringBeforeLast("-")
        val n = name.substringAfterLast("-", "").toIntOrNull() ?: 1
        return File(current.parentFile, "$base-${n + 1}.mp4")
    }

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

    private companion object {
        const val TAG = "lumella"
        const val CAMERA_OPEN_TIMEOUT_MS = 5_000L
        const val ANALYSIS_JPEG_QUALITY = 90

        /** How far back from the write head to look for a decodable frame, in order. */
        val FRAME_BACKOFF_MS = longArrayOf(0, 200, 500, 1_000, 2_000)
        const val CAPTURE_MAX_ATTEMPTS = 3
        const val CAPTURE_RETRY_DELAY_MS = 600L
    }
}
