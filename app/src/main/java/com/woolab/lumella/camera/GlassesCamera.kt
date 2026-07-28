package com.woolab.lumella.camera

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
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
        mainHandler.post { bindAndCapture(onCaptured, onError) }
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
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, capture)

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
                            release(provider)
                            onError("Capture failed: ${exception.message}")
                        }
                    },
                )
            } catch (e: Exception) {
                release(provider)
                onError("Camera bind failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(appContext))
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
    }
}
