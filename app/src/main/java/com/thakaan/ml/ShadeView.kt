package com.thakaan.ml

/** What the rear camera sees overhead, from a street-scene class map. Shares are 0 to 1. */
class ShadeView(
    val skyShare: Double,
    val vegetationShare: Double,
    val buildingShare: Double,
    /** Share of the upper part of the picture that is NOT open sky: a proxy for overhead cover. */
    val overheadCover: Double,
) {
    /** Words for Diagnostics. The shade map and the GPS sky view stay in charge: this is a cross-check shown beside them. */
    val summary: String
        get() = when {
            overheadCover >= 0.6 && vegetationShare >= buildingShare -> "mostly covered by trees"
            overheadCover >= 0.6 -> "mostly covered by buildings"
            overheadCover >= 0.3 -> "partly covered"
            else -> "open sky"
        }
}

/**
 * Reads a Cityscapes class map (see tools/models/convert_segformer.py: 8 vegetation, 2 building, 10 sky) into [ShadeView].
 * Pure, so it is tested on the laptop. Shadow mode: it never changes shade or a level; unknown shade is still sun.
 */
object ShadeAnalysis {
    const val BUILDING = 2
    const val VEGETATION = 8
    const val SKY = 10

    /** [classes] is row-major, [rows] x [cols]; the top [upperFraction] of the rows count as "overhead". */
    fun analyse(classes: IntArray, rows: Int, cols: Int, upperFraction: Double = 0.45): ShadeView? {
        if (rows <= 0 || cols <= 0 || classes.size != rows * cols) return null
        val upperRows = (rows * upperFraction).toInt().coerceAtLeast(1)
        var sky = 0; var veg = 0; var bld = 0; var upperSky = 0
        for (r in 0 until rows) for (c in 0 until cols) {
            when (classes[r * cols + c]) {
                SKY -> { sky++; if (r < upperRows) upperSky++ }
                VEGETATION -> veg++
                BUILDING -> bld++
            }
        }
        val n = classes.size.toDouble()
        val upperN = (upperRows * cols).toDouble()
        return ShadeView(sky / n, veg / n, bld / n, 1.0 - upperSky / upperN)
    }
}
