package com.thakaan.chhaya

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RiderLogTest {
    @Test fun aStreetIsColouredOnlyAfterEnoughSamples() {
        val l = RiderLog()
        l.addSignal(7, 4); l.addSignal(7, 4)
        assertNull("two samples are not enough", l.signalMean(7))
        assertFalse(7 in l.signalEdges())
        l.addSignal(7, 1)
        assertEquals(3.0, l.signalMean(7)!!, 1e-9)
        assertTrue(7 in l.signalEdges())
    }

    @Test fun signalBandsFollowTheMeanBars() {
        assertEquals(SignalBand.GOOD, SignalBand.of(3.0))
        assertEquals(SignalBand.WEAK, SignalBand.of(2.0))
        assertEquals(SignalBand.POOR, SignalBand.of(0.4))
    }

    @Test fun speedRatioIsCappedAtFreeFlowSoAFastRiderIsNeverCreditedWithNegativeTraffic() {
        val l = RiderLog()
        repeat(3) { l.addSpeed(2, 1.8) }
        assertEquals(1.0, l.speedRatio(2)!!, 1e-9)
    }

    @Test fun theLogSurvivesAJsonRoundTripAndBadJsonGivesAnEmptyLog() {
        val l = RiderLog()
        repeat(4) { l.addSignal(3, 2); l.addSpeed(9, 0.25) }
        val back = RiderLog.fromJson(l.toJson())
        assertEquals(2.0, back.signalMean(3)!!, 1e-9)
        assertEquals(0.25, back.speedRatio(9)!!, 1e-9)
        assertTrue(RiderLog.fromJson("not json").signalEdges().isEmpty())
    }

    @Test fun measuredSlowRidingIsJammedAndFreeRidingIsFree() {
        assertEquals(TrafficEstimate.Band.JAMMED, TrafficEstimate.bandOfRatio(0.3))
        assertEquals(TrafficEstimate.Band.FREE, TrafficEstimate.bandOfRatio(0.95))
    }

    @Test fun theTrafficEstimateIsBusiestOnMainRoadsAtWeekdayRushHour() {
        val mon = 2; val sat = 7
        val mainPeak = TrafficEstimate.slowdown(30.0, mon, 9 * 60)
        val sidePeak = TrafficEstimate.slowdown(20.0, mon, 9 * 60)
        val mainNight = TrafficEstimate.slowdown(30.0, mon, 2 * 60)
        val mainSatPeakHour = TrafficEstimate.slowdown(30.0, sat, 9 * 60)
        assertTrue(mainPeak > sidePeak)
        assertTrue(mainPeak > mainNight)
        assertTrue("weekend mornings are lighter", mainSatPeakHour < mainPeak)
        assertEquals(TrafficEstimate.Band.JAMMED, TrafficEstimate.band(mainPeak))
        assertEquals(TrafficEstimate.Band.FREE, TrafficEstimate.band(mainNight))
    }

    @Test fun pm25BandsFollowTheCpcbBreakpoints() {
        assertEquals(PmBand.GOOD, PmBand.of(30.0))
        assertEquals(PmBand.SATISFACTORY, PmBand.of(30.1))
        assertEquals(PmBand.MODERATE, PmBand.of(90.0))
        assertEquals(PmBand.VERY_POOR, PmBand.of(250.0))
        assertEquals(PmBand.SEVERE, PmBand.of(251.0))
        assertNotNull(PmBand.of(0.0))
    }
}
