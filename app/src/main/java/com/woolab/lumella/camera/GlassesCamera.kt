package com.woolab.lumella.camera

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.CameraState
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
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
    private val capturing = AtomicBoolean(false)

    /**
     * Captures a single JPEG frame; [onCaptured] receives raw JPEG bytes.
     * Safe to call from any thread; binding is marshalled to the main thread as CameraX requires.
     */
    fun captureImage(onCaptured: (ByteArray) -> Unit, onError: (String) -> Unit) {
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

    fun shutdown() {
        mainHandler.post {
            runCatching { ProcessCameraProvider.getInstance(appContext).get().unbindAll() }
        }
        cameraExecutor.shutdown()
    }

    private companion object {
        const val TAG = "lumella"
        const val CAMERA_OPEN_TIMEOUT_MS = 5_000L
        const val CAPTURE_MAX_ATTEMPTS = 3
        const val CAPTURE_RETRY_DELAY_MS = 600L
    }
}
