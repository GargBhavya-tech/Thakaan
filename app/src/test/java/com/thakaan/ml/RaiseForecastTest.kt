package com.thakaan.ml

import com.thakaan.risk.Forecast
import org.junit.Assert.assertEquals
import org.junit.Test

class RaiseForecastTest {
    private val rule = Forecast(60, 55, 66, 70, 62, 78, 10, null)

    @Test fun aLowerModelNeverLowersTheRuleForecast() {
        val r = ShadowModels.raiseForecast(rule, 20, 30)
        assertEquals(60, r.tss15); assertEquals(70, r.tss30)
    }

    @Test fun aMissingModelChangesNothing() {
        val r = ShadowModels.raiseForecast(rule, null, null)
        assertEquals(60, r.tss15); assertEquals(70, r.tss30); assertEquals(10, r.minutesToStrained)
    }

    @Test fun aHigherModelRaisesTheForecastAndItsBand() {
        val r = ShadowModels.raiseForecast(rule, 75, 90)
        assertEquals(75, r.tss15); assertEquals(90, r.tss30)
        assertEquals(75, r.tss15High); assertEquals(90, r.tss30High)
        assertEquals(55, r.tss15Low)
    }
}
