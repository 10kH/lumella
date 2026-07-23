package com.woolab.lumella.camera

import android.content.Context
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
 * G006 on-device finding (2026-07-23): the previous hand-rolled camera2 flow opened the
 * camera but never delivered a frame on the RayNeo — the system disconnected the client
 * ~6s later with no capture and no error surfaced. This is the LEGACY `TUTOR/LEGACY/ELLA`
 * recipe instead (CameraX 1.3.1, `CAPTURE_MODE_MINIMIZE_LATENCY`, no preview,
 * `bindToLifecycle`), which is proven on this exact hardware.
 *
 * Not exercised by JVM unit tests (real camera stack); verified via the on-device smoke.
 */
class GlassesCamera(context: Context, private val lifecycleOwner: LifecycleOwner) {

    private val appContext = context.applicationContext
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "lumella-camera").apply { isDaemon = true }
    }
    private var imageCapture: ImageCapture? = null
    private val ready = AtomicBoolean(false)

    init {
        val providerFuture = ProcessCameraProvider.getInstance(appContext)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, capture)
                imageCapture = capture
                ready.set(true)
                Log.d(TAG, "CameraX initialized (LEGACY recipe)")
            } catch (e: Exception) {
                Log.w(TAG, "CameraX init failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(appContext))
    }

    /** Captures a single JPEG frame; [onCaptured] receives raw JPEG bytes. */
    fun captureImage(onCaptured: (ByteArray) -> Unit, onError: (String) -> Unit) {
        val capture = imageCapture
        if (capture == null || !ready.get()) {
            onError("Camera not ready")
            return
        }
        capture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        onCaptured(bytes)
                    } catch (e: Exception) {
                        onError("Image read failed: ${e.message}")
                    } finally {
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    onError("Capture failed: ${exception.message}")
                }
            },
        )
    }

    fun shutdown() {
        cameraExecutor.shutdown()
    }

    private companion object {
        const val TAG = "lumella"
    }
}
