package com.thakaan.ppg

import android.annotation.SuppressLint
import android.content.Context
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

/**
 * Fingertip PPG capture (#8): rear camera at 640x480, a fixed 30 fps, auto exposure / white balance / focus OFF with a
 * fixed exposure and low ISO, stabilisation and noise reduction OFF, torch at about 60% strength. For every frame it
 * averages the red and green channel over the centre 100x100 pixels and stamps it with the sensor's hardware timestamp.
 *
 * Why: auto-exposure flattens the pulse into a straight line, a variable frame rate breaks HRV, and without the torch
 * sunlight leaking around the finger swamps the signal. Frames are RAM-only (invariant 7): nothing is written to disk.
 * DEVICE-UNTESTED: the key set is written from the Camera2 reference and must be checked on the phone (see PHONE_CHECKLIST).
 */
class CameraPpgCapture(private val ctx: Context) {
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null

    /** What the camera actually applied, for the Diagnostics screen. */
    class Applied(val exposureNs: Long?, val iso: Int?, val frameDurationNs: Long?, val torchLevel: Int?)
    @Volatile var applied: Applied? = null; private set
    @Volatile var lastError: String? = null; private set

    fun frameIntervalsOk(frames: List<PpgFrame>): Boolean {
        if (frames.size < 10) return false
        val dts = (1 until frames.size).map { (frames[it].tNanos - frames[it - 1].tNanos) / 1e6 }
        val bad = dts.count { it < 25.0 || it > 42.0 }
        return bad.toDouble() / dts.size < 0.02 // under 2% of intervals off the 33.3 ms cadence
    }

    @SuppressLint("MissingPermission") // the caller requests CAMERA first
    fun start(onFrame: (PpgFrame) -> Unit, onError: (String) -> Unit) {
        val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = mgr.cameraIdList.firstOrNull {
            val c = mgr.getCameraCharacteristics(it)
            c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK &&
                c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return onError("no rear camera with a torch").also { lastError = "no rear camera with a torch" }
        val chars = mgr.getCameraCharacteristics(id)
        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList() ?: emptyList()
        if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR !in caps) {
            lastError = "camera has no manual sensor control"; return onError(lastError!!)
        }
        val t = HandlerThread("ppg-camera").also { it.start() }
        thread = t; handler = Handler(t.looper)
        val ir = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 4)
        reader = ir
        ir.setOnImageAvailableListener({ r ->
            val img = r.acquireNextImage() ?: return@setOnImageAvailableListener
            try { onFrame(centreMeans(img)) } finally { img.close() }
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
                            try { s.setRepeatingRequest(buildRequest(cam, chars, ir).build(), null, handler) }
                            catch (e: Exception) { lastError = "request failed: ${e.message}"; onError(lastError!!) }
                        }
                        override fun onConfigureFailed(s: CameraCaptureSession) { lastError = "camera session failed"; onError(lastError!!) }
                    },
                )
                cam.createCaptureSession(cfg)
            }
            override fun onDisconnected(cam: CameraDevice) { cam.close(); lastError = "camera disconnected"; onError(lastError!!) }
            override fun onError(cam: CameraDevice, error: Int) { cam.close(); lastError = "camera error $error"; onError(lastError!!) }
        }, handler)
    }

    private fun buildRequest(cam: CameraDevice, chars: CameraCharacteristics, ir: ImageReader): CaptureRequest.Builder {
        val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        b.addTarget(ir.surface)
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF)
        b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
        b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
        b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
        b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
        b.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
        val expRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exposure = (8_000_000L).coerceIn(expRange?.lower ?: 1_000_000L, expRange?.upper ?: 30_000_000L)
        val iso = (100).coerceIn(isoRange?.lower ?: 100, isoRange?.upper ?: 800)
        b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure)
        b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
        b.set(CaptureRequest.SENSOR_FRAME_DURATION, 33_333_333L)
        b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF)
        b.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_OFF)
        b.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
        b.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_OFF)
        b.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
        val maxTorch = chars.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1
        val torch = (maxTorch * 0.6).toInt().coerceIn(1, maxTorch)
        if (maxTorch > 1) b.set(CaptureRequest.FLASH_STRENGTH_LEVEL, torch)
        applied = Applied(exposure, iso, 33_333_333L, torch)
        return b
    }

    /** Mean red and green over the centre 100x100 pixels (YUV to RGB per pixel, sampled every second pixel). */
    private fun centreMeans(img: Image): PpgFrame {
        val w = img.width; val h = img.height
        val yP = img.planes[0]; val uP = img.planes[1]; val vP = img.planes[2]
        val yBuf = yP.buffer; val uBuf = uP.buffer; val vBuf = vP.buffer
        var sumR = 0.0; var sumG = 0.0; var n = 0
        val x0 = w / 2 - 50; val y0 = h / 2 - 50
        var y = y0
        while (y < y0 + 100) {
            var x = x0
            while (x < x0 + 100) {
                val yy = yBuf.get(y * yP.rowStride + x * yP.pixelStride).toInt() and 0xFF
                val ci = (y / 2) * uP.rowStride + (x / 2) * uP.pixelStride
                val u = (uBuf.get(ci).toInt() and 0xFF) - 128
                val v = (vBuf.get((y / 2) * vP.rowStride + (x / 2) * vP.pixelStride).toInt() and 0xFF) - 128
                sumR += yy + 1.402 * v
                sumG += yy - 0.344136 * u - 0.714136 * v
                n++
                x += 2
            }
            y += 2
        }
        return PpgFrame(img.timestamp, sumR / n, sumG / n)
    }

    fun stop() {
        try { session?.stopRepeating() } catch (_: Exception) {}
        session?.close(); session = null
        device?.close(); device = null
        reader?.close(); reader = null
        thread?.quitSafely(); thread = null; handler = null
    }
}
