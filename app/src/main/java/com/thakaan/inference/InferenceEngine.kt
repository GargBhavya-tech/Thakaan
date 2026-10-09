package com.thakaan.inference

/** Where a model can run. Order of preference for small models: NPU, then GPU, then CPU. */
enum class Backend(val label: String) { QNN_NPU("NPU"), GPU("GPU"), CPU("CPU") }

/** A model to load. [path] is resolved by the back end (assets or files). Model files are never committed (docs/ASSETS.md). */
data class ModelSpec(val name: String, val path: String)

/**
 * What actually happened when a model was loaded. A session that creates without throwing may still be on the CPU,
 * so back ends must fill [delegatedOps] / [totalOps] from the delegate itself, never assume them.
 */
data class ComputeReport(
    val requested: Backend,
    val actual: Backend,
    val delegatedOps: Int,
    val totalOps: Int,
    val note: String = "",
) {
    /** True only when every op ran on the backend that was asked for. */
    val fullyOnRequested: Boolean get() = actual == requested && totalOps > 0 && delegatedOps == totalOps

    /** The text shown on Diagnostics, e.g. "12/12 ops on NPU" or "0/12 ops on NPU (running on CPU)". */
    val summary: String
        get() = buildString {
            append("$delegatedOps/$totalOps ops on ${requested.label}")
            if (actual != requested || delegatedOps < totalOps) append(" (running on ${actual.label})")
        }
}

/** One compute back end behind the seam. Implementations: QNN (NPU), GPU, CPU. */
interface InferenceEngine {
    val backend: Backend

    /** Loads [model] and returns the honest compute report. Throws if this back end cannot load it at all. */
    fun load(model: ModelSpec): ComputeReport

    fun run(input: FloatArray): FloatArray

    fun close()
}
