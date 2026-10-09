package com.thakaan.inference

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Helpers for the "N/N ops on NPU" number. The TFLite Interpreter has no public op-count API, so:
 *  - total ops are read straight out of the .tflite flatbuffer (subgraph 0), and
 *  - delegated ops are read from TFLite's own log line "Replacing X out of Y node(s) with delegate ...".
 */
object ModelOps {
    /** Number of operators in subgraph 0 of a .tflite model, or 0 if it cannot be read. */
    fun totalOps(model: ByteBuffer): Int = try {
        val b = model.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val root = b.getInt(0)

        // flatbuffer: a table starts with a signed offset back to its vtable; field i is the short at vtable[4 + 2i].
        fun field(table: Int, idx: Int): Int {
            val vt = table - b.getInt(table)
            val o = 4 + 2 * idx
            return if (o < b.getShort(vt).toInt()) b.getShort(vt + o).toInt() else 0
        }

        val subgraphsField = field(root, 2) // Model.subgraphs
        if (subgraphsField == 0) 0 else {
            val vec = root + subgraphsField + b.getInt(root + subgraphsField)
            if (b.getInt(vec) == 0) 0 else {
                val first = vec + 4 + b.getInt(vec + 4)
                val opsField = field(first, 3) // SubGraph.operators
                if (opsField == 0) 0 else b.getInt(first + opsField + b.getInt(first + opsField))
            }
        }
    } catch (_: Throwable) {
        0
    }

    private val replacing = Regex("""Replacing (\d+)(?: out of (\d+))? node\(s\) with delegate""")

    /** Parses a TFLite delegate log line. Returns (delegated, total or null if the line does not say). */
    fun parseDelegateLog(line: String): Pair<Int, Int?>? {
        val m = replacing.find(line) ?: return null
        return m.groupValues[1].toInt() to m.groupValues[2].takeIf { it.isNotEmpty() }?.toInt()
    }
}
