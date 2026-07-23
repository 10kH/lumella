package com.woolab.lumella.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread

/**
 * Minimal Camera2-based single-shot JPEG capture (no preview) for the glasses' left-touchpad
 * photo action, ported in spirit from `TUTOR/LEGACY/ELLA` MainActivity's CameraX
 * `ImageCapture(CAPTURE_MODE_MINIMIZE_LATENCY)` flow, but built on the platform `android.hardware.camera2`
 * API directly so `:app` adds zero new dependencies beyond okhttp (plan G006).
 *
 * Not exercised by JVM unit tests (real `android.hardware.camera2`); verified on-device via
 * the P5 smoke pass.
 */
class GlassesCamera(context: Context) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val backgroundThread = HandlerThread("lumella-camera").apply { start() }
    private val backgroundHandler = Handler(backgroundThread.looper)

    /** Captures a single JPEG frame from the first back-facing (or first available) camera. */
    fun captureImage(onCaptured: (ByteArray) -> Unit, onError: (String) -> Unit) {
        val cameraId = try {
            selectCameraId()
        } catch (e: Exception) {
            onError("Camera enumeration failed: ${e.message}")
            return
        }
        if (cameraId == null) {
            onError("No camera available")
            return
        }

        val reader = ImageReader.newInstance(1280, 720, ImageFormat.JPEG, 1)

        try {
            cameraManager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        startCaptureSession(camera, reader, onCaptured, onError)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        reader.close()
                        onError("Camera device error: $error")
                    }
                },
                backgroundHandler,
            )
        } catch (e: SecurityException) {
            reader.close()
            onError("Missing CAMERA permission: ${e.message}")
        } catch (e: Exception) {
            reader.close()
            onError("Camera open failed: ${e.message}")
        }
    }

    private fun startCaptureSession(
        camera: CameraDevice,
        reader: ImageReader,
        onCaptured: (ByteArray) -> Unit,
        onError: (String) -> Unit,
    ) {
        reader.setOnImageAvailableListener(
            { imageReader ->
                val image = imageReader.acquireLatestImage()
                if (image != null) {
                    onCaptured(readJpegBytes(image))
                    image.close()
                }
                reader.close()
                camera.close()
            },
            backgroundHandler,
        )

        try {
            val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }
            camera.createCaptureSession(
                listOf(reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        session.capture(
                            captureRequest.build(),
                            object : CameraCaptureSession.CaptureCallback() {
                                override fun onCaptureFailed(
                                    session: CameraCaptureSession,
                                    request: CaptureRequest,
                                    failure: android.hardware.camera2.CaptureFailure,
                                ) {
                                    reader.close()
                                    camera.close()
                                    onError("Capture failed: reason=${failure.reason}")
                                }
                            },
                            backgroundHandler,
                        )
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        reader.close()
                        camera.close()
                        onError("Camera session configuration failed")
                    }
                },
                backgroundHandler,
            )
        } catch (e: Exception) {
            reader.close()
            camera.close()
            onError("Camera capture failed: ${e.message}")
        }
    }

    private fun readJpegBytes(image: Image): ByteArray {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return bytes
    }

    private fun selectCameraId(): String? =
        cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        } ?: cameraManager.cameraIdList.firstOrNull()

    fun shutdown() {
        backgroundThread.quitSafely()
    }
}
