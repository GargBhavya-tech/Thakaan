package com.thakaan.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class LosClassifierTest {
    /** A one-tree model written by hand: split on elevation (feature 2) at 30. */
    private val tiny = """{"features":["cn0","delta_cn0","elevation","doppler_residual","l1_l5_gap"],"base_score":0.5,"trees":[
        {"nodeid":0,"depth":0,"split":"f2","split_condition":30.0,"yes":1,"no":2,"missing":1,"children":[
          {"nodeid":1,"leaf":-2.0},{"nodeid":2,"leaf":3.0}]}]}"""

    @Test fun walksTheTreeAndAppliesTheSigmoid() {
        val c = LosClassifier.fromJson(tiny)
        assertEquals(1.0 / (1 + Math.exp(2.0)), c.probability(40.0, 0.0, 10.0, 0.0, 0.0), 1e-9)
        assertEquals(1.0 / (1 + Math.exp(-3.0)), c.probability(40.0, 0.0, 50.0, 0.0, 0.0), 1e-9)
    }

    @Test fun aMissingFeatureFollowsTheMissingBranch() {
        val c = LosClassifier.fromJson(tiny)
        assertEquals(c.probability(40.0, 0.0, 10.0, 0.0, 0.0), c.probability(40.0, 0.0, Double.NaN, 0.0, 0.0), 1e-9)
    }

    @Test fun theTrainedModelSeparatesClearAndBlockedSatellites() {
        val f = File("src/main/assets/models/los_xgboost.json"); assumeTrue("model not generated", f.exists())
        val c = LosClassifier.fromJson(f.readText())
        val open = c.probability(46.0, 0.5, 60.0, 0.1, 0.5)
        val blocked = c.probability(24.0, -5.0, 25.0, 1.2, 6.0)
        assertTrue("open $open blocked $blocked", open > 0.9 && blocked < 0.1)
        assertEquals(40, c.treeCount)
    }
}
