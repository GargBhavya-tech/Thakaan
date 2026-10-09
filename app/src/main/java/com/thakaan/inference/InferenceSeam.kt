package com.thakaan.inference

/**
 * The one place models are loaded. Tries back ends in the given order and keeps the first that loads,
 * reporting the compute unit it really got. Swapping a model or a back end changes nothing for callers.
 *
 * A back end that "loads" but delegates 0 ops is still returned (the model works, on CPU) but the report says so,
 * so Diagnostics and tests can see a silent CPU fallback instead of trusting a session that did not throw.
 */
class InferenceSeam(private val engines: List<InferenceEngine>) {
    var active: InferenceEngine? = null
        private set
    var report: ComputeReport? = null
        private set
    val failures = mutableListOf<String>()

    fun load(model: ModelSpec): ComputeReport {
        close()
        failures.clear()
        for (e in engines) {
            try {
                val r = e.load(model)
                active = e
                report = r
                return r
            } catch (t: Throwable) {
                failures += "${e.backend.label}: ${t.message ?: t::class.simpleName}"
            }
        }
        error("no back end could load ${model.name}: ${failures.joinToString("; ")}")
    }

    fun run(input: FloatArray): FloatArray = checkNotNull(active) { "no model loaded" }.run(input)

    fun close() {
        active?.close()
        active = null
        report = null
    }
}
