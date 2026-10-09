package com.thakaan.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InferenceSeamTest {
    private class Fake(
        override val backend: Backend,
        private val delegated: Int = 0,
        private val total: Int = 10,
        private val fails: Boolean = false,
        private val scale: Float = 1f,
    ) : InferenceEngine {
        var closed = false
        override fun load(model: ModelSpec): ComputeReport {
            if (fails) error("delegate unavailable")
            return ComputeReport(backend, backend, delegated, total)
        }
        override fun run(input: FloatArray) = FloatArray(input.size) { input[it] * scale }
        override fun close() { closed = true }
    }

    private val spec = ModelSpec("tiny", "tiny.tflite")

    @Test fun firstWorkingBackendWins() {
        val seam = InferenceSeam(listOf(Fake(Backend.QNN_NPU, 10, 10), Fake(Backend.CPU)))
        val r = seam.load(spec)
        assertEquals(Backend.QNN_NPU, r.actual)
        assertEquals("10/10 ops on NPU", r.summary)
        assertTrue(r.fullyOnRequested)
    }

    @Test fun failingNpuFallsBackAndRecordsWhy() {
        val seam = InferenceSeam(listOf(Fake(Backend.QNN_NPU, fails = true), Fake(Backend.GPU, 8, 10), Fake(Backend.CPU)))
        val r = seam.load(spec)
        assertEquals(Backend.GPU, r.actual)
        assertEquals(1, seam.failures.size)
        assertTrue(seam.failures[0].startsWith("NPU"))
    }

    @Test fun sessionThatLoadsButDelegatesNothingIsNotReportedAsNpu() {
        // The trap from the docs: no exception, but 0 of 12 ops on the NPU.
        val r = InferenceSeam(listOf(Fake(Backend.QNN_NPU, delegated = 0, total = 12))).load(spec)
        assertFalse(r.fullyOnRequested)
        assertEquals("0/12 ops on NPU", r.summary.substringBefore(" (running"))
    }

    @Test fun backendCanBeSwappedBehindTheSeam() {
        val a = Fake(Backend.CPU, scale = 2f)
        val b = Fake(Backend.CPU, scale = 3f)
        assertEquals(2f, InferenceSeam(listOf(a)).also { it.load(spec) }.run(floatArrayOf(1f))[0], 0f)
        assertEquals(3f, InferenceSeam(listOf(b)).also { it.load(spec) }.run(floatArrayOf(1f))[0], 0f)
    }

    @Test fun reloadClosesThePreviousEngine() {
        val a = Fake(Backend.CPU)
        val seam = InferenceSeam(listOf(a))
        seam.load(spec)
        seam.load(spec)
        assertTrue(a.closed)
    }

    @Test(expected = IllegalStateException::class)
    fun allBackendsFailingIsAnError() {
        InferenceSeam(listOf(Fake(Backend.QNN_NPU, fails = true), Fake(Backend.CPU, fails = true))).load(spec)
    }
}
