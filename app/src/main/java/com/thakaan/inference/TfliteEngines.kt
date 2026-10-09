package com.thakaan.inference

import android.content.Context
import android.system.Os
import android.util.Log
import com.qualcomm.qti.QnnDelegate
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "THK_INF"

private fun readModel(ctx: Context, path: String): ByteBuffer {
    val bytes = ctx.assets.open(path).use { it.readBytes() }
    return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() }
}

private fun runFloat(interp: Interpreter, input: FloatArray): FloatArray {
    val inBuf = ByteBuffer.allocateDirect(input.size * 4).order(ByteOrder.nativeOrder())
    input.forEach { inBuf.putFloat(it) }
    inBuf.rewind()
    val outCount = interp.getOutputTensor(0).shape().fold(1) { a, b -> a * b }
    val outBuf = ByteBuffer.allocateDirect(outCount * 4).order(ByteOrder.nativeOrder())
    interp.run(inBuf, outBuf)
    outBuf.rewind()
    return FloatArray(outCount) { outBuf.getFloat() }
}

/**
 * The last TFLite "Replacing ... with delegate (<name>)" line this process logged for [delegateName].
 * Filtering by name matters: TFLite also logs a line for its own CPU (XNNPACK) delegate right after ours.
 */
private fun lastDelegateLine(delegateName: String): String? = try {
    Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "400", "-s", "tflite:I"))
        .inputStream.bufferedReader().readLines().lastOrNull { it.contains("with delegate ($delegateName)") }
} catch (_: Throwable) {
    null
}

class CpuEngine(private val ctx: Context) : InferenceEngine {
    override val backend = Backend.CPU
    private var interp: Interpreter? = null

    override fun load(model: ModelSpec): ComputeReport {
        val buf = readModel(ctx, model.path)
        interp = Interpreter(buf, Interpreter.Options().setNumThreads(2))
        val total = ModelOps.totalOps(buf)
        return ComputeReport(Backend.CPU, Backend.CPU, delegatedOps = total, totalOps = total)
    }

    override fun run(input: FloatArray) = runFloat(checkNotNull(interp), input)
    override fun close() { interp?.close(); interp = null }
}

/** Adreno GPU via the TFLite GPU delegate. Same honesty rule as the NPU: the count comes from the delegate's log line. */
class GpuEngine(private val ctx: Context) : InferenceEngine {
    override val backend = Backend.GPU
    private var interp: Interpreter? = null
    private var delegate: GpuDelegate? = null

    override fun load(model: ModelSpec): ComputeReport {
        val buf = readModel(ctx, model.path)
        val d = GpuDelegate()
        delegate = d
        interp = Interpreter(buf, Interpreter.Options().addDelegate(d))
        val total = ModelOps.totalOps(buf)
        val parsed = lastDelegateLine("TfLiteGpuDelegateV2")?.let { ModelOps.parseDelegateLog(it) }
        val delegated = parsed?.first ?: 0
        return ComputeReport(
            requested = Backend.GPU,
            actual = if (delegated > 0) Backend.GPU else Backend.CPU,
            delegatedOps = delegated,
            totalOps = parsed?.second ?: total,
            note = if (parsed == null) "no delegate log line found; counted as 0 delegated" else "",
        )
    }

    override fun run(input: FloatArray) = runFloat(checkNotNull(interp), input)

    override fun close() {
        interp?.close(); interp = null
        delegate?.close(); delegate = null
    }
}

/**
 * Qualcomm HTP (NPU) via the QNN TFLite delegate. The delegate count comes from TFLite's log, not from the
 * fact that the session was created: a session can create fine and still run every op on the CPU.
 */
class QnnEngine(private val ctx: Context) : InferenceEngine {
    override val backend = Backend.QNN_NPU
    private var interp: Interpreter? = null
    private var delegate: QnnDelegate? = null

    override fun load(model: ModelSpec): ComputeReport {
        val libDir = ctx.applicationInfo.nativeLibraryDir
        Os.setenv("ADSP_LIBRARY_PATH", libDir, true)

        val buf = readModel(ctx, model.path)
        val opts = QnnDelegate.Options().apply {
            setBackendType(QnnDelegate.Options.BackendType.HTP_BACKEND)
            setSkelLibraryDir(libDir)
            setHtpPerformanceMode(QnnDelegate.Options.HtpPerformanceMode.HTP_PERFORMANCE_BURST)
            setHtpPrecision(QnnDelegate.Options.HtpPrecision.HTP_PRECISION_FP16)
        }
        val d = QnnDelegate(opts)
        delegate = d
        interp = Interpreter(buf, Interpreter.Options().addDelegate(d))

        val total = ModelOps.totalOps(buf)
        val line = lastDelegateLine("TfLiteQnnDelegate")
        val parsed = line?.let { ModelOps.parseDelegateLog(it) }
        Log.i(TAG, "QNN delegate log: $line")
        val delegated = parsed?.first ?: 0
        val totalOps = parsed?.second ?: total
        return ComputeReport(
            requested = Backend.QNN_NPU,
            actual = if (delegated > 0) Backend.QNN_NPU else Backend.CPU,
            delegatedOps = delegated,
            totalOps = totalOps,
            note = if (parsed == null) "no delegate log line found; counted as 0 delegated" else "",
        )
    }

    override fun run(input: FloatArray) = runFloat(checkNotNull(interp), input)

    override fun close() {
        interp?.close(); interp = null
        delegate?.close(); delegate = null
    }
}
