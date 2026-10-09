package com.thakaan.inference

import android.content.Context

/**
 * App-wide holder for the inference seam. Order of preference: NPU, then GPU, then CPU.
 */
object ModelHost {
    @Volatile var seam: InferenceSeam = InferenceSeam(emptyList())
        private set

    /** Result of the last NPU-vs-CPU output comparison, for Diagnostics. */
    @Volatile var lastCheck: String = ""

    private val tiny = ModelSpec("tiny", "tiny.tflite")

    fun init(ctx: Context) {
        if (seam.report == null && seam.active == null) {
            val app = ctx.applicationContext
            seam = InferenceSeam(listOf(QnnEngine(app), GpuEngine(app), CpuEngine(app)))
        }
    }

    /** Loads the tiny test model through the seam, runs it, and checks the output against a plain CPU run. */
    fun loadTinyAndCheck(ctx: Context, only: Backend? = null) {
        val app = ctx.applicationContext
        if (only != null) {
            // Force one back end to prove the model can be swapped behind the seam.
            seam.close()
            seam = InferenceSeam(
                listOf(
                    when (only) {
                        Backend.QNN_NPU -> QnnEngine(app)
                        Backend.GPU -> GpuEngine(app)
                        Backend.CPU -> CpuEngine(app)
                    },
                ),
            )
        } else {
            seam.close()
            seam = InferenceSeam(listOf(QnnEngine(app), GpuEngine(app), CpuEngine(app)))
        }
        val r = seam.load(tiny)
        val x = FloatArray(16) { (it - 8) / 8f }
        val got = seam.run(x)
        val cpu = CpuEngine(ctx.applicationContext).also { it.load(tiny) }
        val want = try { cpu.run(x) } finally { cpu.close() }
        val diff = got.indices.maxOf { kotlin.math.abs(got[it] - want[it]) }
        lastCheck = "ran on ${r.actual.label}, max |diff| vs CPU = %.5f".format(diff)
    }
}
