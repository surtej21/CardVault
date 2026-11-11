package com.example.cardvault

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.TextureView
import java.io.File
import kotlin.math.abs

/**
 * A tiny in-process camera: capture one JPEG straight into an app-cache file,
 * entirely inside the vault's own activity. Because no external camera app
 * ever takes over the screen, nothing can kill the session mid-import.
 *
 * Caller must hold CAMERA permission before calling start().
 */
class CardCamera(
    private val context: Context,
    private val textureView: TextureView,
    private val output: File,
    private val onCaptured: (File) -> Unit,
    private val onFailure: (String) -> Unit
) {
    private val main = Handler(context.mainLooper)
    private val worker = HandlerThread("card-camera").apply { start() }
    private val handler = Handler(worker.looper)
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var stillBuilder: CaptureRequest.Builder? = null
    private var started = false
    private var finished = false

    fun start() {
        if (started) return
        started = true
        if (textureView.isAvailable) attach()
        else textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                if (!finished) attach()
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
        }
    }

    fun capture() {
        val builder = stillBuilder ?: return
        val sender = session ?: return
        handler.post {
            try {
                sender.stopRepeating()
                sender.capture(builder.build(), null, handler)
            } catch (e: Exception) {
                main.post { fail("Could not capture the photo.") }
            }
        }
    }

    fun close() {
        if (finished) return
        finished = true
        textureView.surfaceTextureListener = null
        try { session?.stopRepeating() } catch (_: Exception) {}
        try { session?.close() } catch (_: Exception) {}
        try { device?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { previewSurface?.release() } catch (_: Exception) {}
        session = null; device = null; reader = null; previewSurface = null; stillBuilder = null
        worker.quitSafely()
    }

    private fun fail(reason: String) {
        finishLog()
        main.post { onFailure(reason) }
    }

    private fun finishLog() {
        finished = true
        closeContextOnly()
    }

    /** Closes camera objects without notifying caller again. */
    private fun closeContextOnly() {
        textureView.surfaceTextureListener = null
        try { session?.close() } catch (_: Exception) {}
        try { device?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { previewSurface?.release() } catch (_: Exception) {}
        session = null; device = null; reader = null; previewSurface = null; stillBuilder = null
    }

    @SuppressLint("MissingPermission")
    private fun attach() {
        try {
            val id = cameraManager.cameraIdList.firstOrNull {
                it != null && cameraManager.getCameraCharacteristics(it)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: cameraManager.cameraIdList.firstOrNull()
            if (id == null) return fail("No camera available on this phone.")

            val chars = cameraManager.getCameraCharacteristics(id)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            if (map == null) return fail("Camera is unusable on this phone.")
            val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

            // Cap the still around 2 MP — plenty for OCR, small and quick to save.
            val captureSize = map.getOutputSizes(ImageFormat.JPEG)
                .filter { it.width <= 2048 && it.height <= 1536 }
                .maxByOrNull { it.width * it.height } ?: Size(1280, 960)
            val previewSize = map.getOutputSizes(SurfaceTexture::class.java)
                .minByOrNull { abs(it.width.toFloat() / it.height - captureSize.width.toFloat() / captureSize.height) }
                ?: Size(640, 480)

            val texture = textureView.surfaceTexture ?: return fail("Camera preview is unavailable.")
            texture.setDefaultBufferSize(previewSize.width, previewSize.height)
            val preview = Surface(texture)
            previewSurface = preview

            val jpegReader = ImageReader.newInstance(captureSize.width, captureSize.height, ImageFormat.JPEG, 2)
            jpegReader.setOnImageAvailableListener({ active ->
                val image = try { active.acquireLatestImage() } catch (e: Exception) { null }
                if (image == null) return@setOnImageAvailableListener
                try {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    output.writeBytes(bytes)
                    if (!finished) {
                        finished = true
                        main.post {
                            closeContextOnly()
                            onCaptured(output)
                        }
                    }
                } finally {
                    image.close()
                }
            }, handler)
            reader = jpegReader

            cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (finished) { camera.close(); return }
                    device = camera
                    try {
                        camera.createCaptureSession(
                            listOf(preview, jpegReader.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(s: CameraCaptureSession) {
                                    if (finished) { s.close(); return }
                                    session = s
                                    try {
                                        val previewRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                            addTarget(preview)
                                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                        }
                                        s.setRepeatingRequest(previewRequest.build(), null, handler)
                                        stillBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                            addTarget(jpegReader.surface)
                                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                            set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
                                        }
                                    } catch (e: Exception) {
                                        main.post { fail("Camera preview failed to start.") }
                                    }
                                }
                                override fun onConfigureFailed(s: CameraCaptureSession) {
                                    main.post { fail("Camera session failed.") }
                                }
                            },
                            handler
                        )
                    } catch (e: Exception) {
                        main.post { fail("Camera session failed.") }
                    }
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close() }
                override fun onError(camera: CameraDevice, error: Int) { camera.close(); main.post { fail("Camera cannot be opened on this phone.") } }
            }, handler)
        } catch (e: SecurityException) {
            fail("Camera permission is missing.")
        } catch (e: Exception) {
            fail("Camera is unavailable on this phone.")
        }
    }
}
