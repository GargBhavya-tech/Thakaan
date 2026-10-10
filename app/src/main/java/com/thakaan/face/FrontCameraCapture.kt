package com.thakaan.face

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import java.util.concurrent.Executor
import kotlin.math.abs

/**
 * Front camera for the optional face checks (#31). About 640x480 YUV at 30 fps with stabilisation, noise reduction and edge
 * enhancement off. Auto exposure and white balance run for [LOCK_AFTER_MS] and are then LOCKED, because a camera that keeps
 * adjusting hides the tiny colour change of a pulse. Each frame is shrunk to half size and rotated upright, then handed to
 * the callback as an ARGB array and a bitmap that are reused for the next frame: nothing is stored (invariant 7).
 * DEVICE-UNTESTED: front-camera control support differs between phones; see Diagnostics and PHONE_CHECKLIST.
 */
class FrontCameraCapture(private val ctx: Context) {
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var bitmap: Bitmap? = null
    private var pixels = IntArray(0)

    class Applied(val width: Int, val height: Int, val fpsRange: String, val sensorOrientation: Int, val aeLocked: Boolean)
    @Volatile var applied: Applied? = null; private set
    @Volatile var lastError: String? = null; private set

    @SuppressLint("MissingPermission") // the caller requests CAMERA first
    fun start(onFrame: (tNanos: Long, bitmap: Bitmap, pixels: IntArray) -> Unit, onError: (String) -> Unit, rear: Boolean = false) {
        val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val facing = if (rear) CameraCharacteristics.LENS_FACING_BACK else CameraCharacteristics.LENS_FACING_FRONT
        val id = mgr.cameraIdList.firstOrNull { mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == facing }
            ?: return fail(if (rear) "no rear camera" else "no front camera", onError)
        val chars = mgr.getCameraCharacteristics(id)
        val orientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 270
        val sizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.YUV_420_888)
        val size = sizes?.filter { it.width >= 640 }?.minByOrNull { abs(it.width - 640) + abs(it.height - 480) } ?: sizes?.firstOrNull()
            ?: return fail("camera offers no YUV size", onError)
        val fps = pickFps(chars)

        val t = HandlerThread("face-camera").also { it.start() }
        thread = t; handler = Handler(t.looper)
        val ir = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
        reader = ir
        val sideways = orientation == 90 || orientation == 270
        val ow = (if (sideways) size.height else size.width) / 2
        val oh = (if (sideways) size.width else size.height) / 2
        bitmap = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        pixels = IntArray(ow * oh)
        ir.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                toRotatedArgb(img, orientation, ow, oh)
                val bmp = bitmap
                if (bmp != null) {
                    bmp.setPixels(pixels, 0, ow, 0, 0, ow, oh)
                    onFrame(img.timestamp, bmp, pixels)
                }
            } catch (_: IllegalStateException) {
                // the camera was closed (screen left) while this frame was being read: the buffer is gone, so drop the frame
            } finally { img.close() }
        }, handler)

        mgr.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(cam: CameraDevice) {
                device = cam
                val exec = Executor { handler?.post(it) }
                val cfg = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR, listOf(OutputConfiguration(ir.surface)), exec,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            session = s
                            try {
                                s.setRepeatingRequest(request(cam, ir, fps, lock = false).build(), null, handler)
                                applied = Applied(size.width, size.height, fps.toString(), orientation, false)
                                // after the auto controls settle, freeze exposure and white balance for the measurement
                                handler?.postDelayed({
                                    try {
                                        session?.setRepeatingRequest(request(cam, ir, fps, lock = true).build(), null, handler)
                                        applied = Applied(size.width, size.height, fps.toString(), orientation, true)
                                    } catch (_: Exception) {}
                                }, LOCK_AFTER_MS)
                            } catch (e: Exception) { fail("request failed: ${e.message}", onError) }
                        }
                        override fun onConfigureFailed(s: CameraCaptureSession) = fail("camera session failed", onError)
                    },
                )
                cam.createCaptureSession(cfg)
            }
            override fun onDisconnected(cam: CameraDevice) { cam.close(); fail("camera disconnected", onError) }
            override fun onError(cam: CameraDevice, error: Int) { cam.close(); fail("camera error $error", onError) }
        }, handler)
    }

    private fun fail(msg: String, onError: (String) -> Unit) { lastError = msg; onError(msg) }

    private fun pickFps(chars: CameraCharacteristics): Range<Int> {
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return Range(30, 30)
        return ranges.firstOrNull { it.lower == 30 && it.upper == 30 }
            ?: ranges.filter { it.upper >= 30 && it.lower <= 30 }.minByOrNull { it.upper - it.lower } ?: ranges.first()
    }

    private fun request(cam: CameraDevice, ir: ImageReader, fps: Range<Int>, lock: Boolean): CaptureRequest.Builder {
        val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        b.addTarget(ir.surface)
        b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AE_LOCK, lock)
        b.set(CaptureRequest.CONTROL_AWB_LOCK, lock)
        b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps)
        b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF)
        b.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_OFF)
        b.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
        b.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
        return b
    }

    /** Half-size, upright ARGB. Source coordinates are in half-size space; the full-size pixel is twice that. */
    private fun toRotatedArgb(img: Image, rotation: Int, ow: Int, oh: Int) {
        val hw = img.width / 2; val hh = img.height / 2
        val yP = img.planes[0]; val uP = img.planes[1]; val vP = img.planes[2]
        val yBuf = yP.buffer; val uBuf = uP.buffer; val vBuf = vP.buffer
        var o = 0
        for (oy in 0 until oh) {
            for (ox in 0 until ow) {
                val sx: Int; val sy: Int
                when (rotation) {
                    90 -> { sx = oy; sy = hh - 1 - ox }
                    180 -> { sx = hw - 1 - ox; sy = hh - 1 - oy }
                    270 -> { sx = hw - 1 - oy; sy = ox }
                    else -> { sx = ox; sy = oy }
                }
                val x = sx * 2; val y = sy * 2
                val yy = yBuf.get(y * yP.rowStride + x * yP.pixelStride).toInt() and 0xFF
                val u = (uBuf.get((y / 2) * uP.rowStride + (x / 2) * uP.pixelStride).toInt() and 0xFF) - 128
                val v = (vBuf.get((y / 2) * vP.rowStride + (x / 2) * vP.pixelStride).toInt() and 0xFF) - 128
                val r = (yy + 1.402 * v).toInt().coerceIn(0, 255)
                val g = (yy - 0.344136 * u - 0.714136 * v).toInt().coerceIn(0, 255)
                val b = (yy + 1.772 * u).toInt().coerceIn(0, 255)
                pixels[o++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    fun stop() {
        try { session?.stopRepeating() } catch (_: Exception) {}
        session?.close(); session = null
        device?.close(); device = null
        reader?.close(); reader = null
        thread?.quitSafely(); thread = null; handler = null
        bitmap = null
    }

    companion object { const val LOCK_AFTER_MS = 2500L }
}
