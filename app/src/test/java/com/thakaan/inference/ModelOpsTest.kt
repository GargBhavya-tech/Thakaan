package com.thakaan.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

class ModelOpsTest {
    @Test fun parsesNewAndOldDelegateLogFormats() {
        assertEquals(
            3 to 3,
            ModelOps.parseDelegateLog("I tflite  : Replacing 3 out of 3 node(s) with delegate (TfLiteQnnDelegate) node, yielding 1 partitions"),
        )
        assertEquals(
            5 to null,
            ModelOps.parseDelegateLog("I tflite  : Replacing 5 node(s) with delegate (TfLiteQnnDelegate) node, yielding 2 partitions"),
        )
        assertNull(ModelOps.parseDelegateLog("I tflite  : Created TensorFlow Lite XNNPACK delegate for CPU."))
    }

    @Test fun countsOpsInTheTinyModel() {
        val f = File("src/main/assets/tiny.tflite")
        if (!f.exists()) return // model files are gitignored; regenerate with tools/make_tiny_model.py
        val n = ModelOps.totalOps(ByteBuffer.wrap(f.readBytes()))
        println("tiny.tflite has $n ops")
        assertTrue("expected a small positive op count, got $n", n in 2..6)
    }

    @Test fun garbageIsZeroNotACrash() {
        assertEquals(0, ModelOps.totalOps(ByteBuffer.wrap(ByteArray(8))))
    }
}
