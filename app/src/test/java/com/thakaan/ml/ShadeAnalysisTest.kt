package com.thakaan.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShadeAnalysisTest {
    private fun scene(rows: Int, cols: Int, f: (Int, Int) -> Int) = IntArray(rows * cols) { f(it / cols, it % cols) }

    @Test fun openSkyAboveARoadHasNoOverheadCover() {
        val v = ShadeAnalysis.analyse(scene(20, 40) { r, _ -> if (r < 12) ShadeAnalysis.SKY else 0 }, 20, 40)!!
        assertEquals(0.0, v.overheadCover, 1e-9)
        assertEquals("open sky", v.summary)
    }

    @Test fun aTreeCanopyOverheadIsCover() {
        val v = ShadeAnalysis.analyse(scene(20, 40) { r, _ -> if (r < 10) ShadeAnalysis.VEGETATION else 0 }, 20, 40)!!
        assertEquals(1.0, v.overheadCover, 1e-9)
        assertEquals("mostly covered by trees", v.summary)
    }

    @Test fun buildingsOnBothSidesWithAStripOfSkyIsPartialCover() {
        val v = ShadeAnalysis.analyse(scene(20, 40) { r, c -> if (r < 9 && c in 10..29) ShadeAnalysis.SKY else if (r < 12) ShadeAnalysis.BUILDING else 0 }, 20, 40)!!
        assertEquals("partly covered", v.summary)
    }

    @Test fun aMapOfTheWrongSizeGivesNothing() {
        assertNull(ShadeAnalysis.analyse(IntArray(10), 4, 4))
    }
}
