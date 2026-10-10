package com.thakaan.settings

import com.thakaan.risk.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserProfileTest {
    @Test fun jsonRoundTrips() {
        val p = UserProfile("Ravi", Lang.KN, 62.0, 1.65, 41, 12, 66.0, "+919812345678", true)
        val q = UserProfile.fromJson(p.toJson())
        assertEquals(Lang.KN, q.language); assertEquals(62.0, q.massKg, 0.0); assertEquals(41, q.ageYears)
        assertEquals(12, q.acclimatisationDays); assertEquals("+919812345678", q.emergencyContact); assertTrue(q.shareLocationWithSupervisor)
    }

    @Test fun garbageAndOutOfRangeFallBackToCautiousDefaults() {
        val q = UserProfile.fromJson("""{"massKg": 900, "heightM": 0.2, "age": 3, "accl": 400, "restHr": 5, "lang": "XX"}""")
        val d = UserProfile()
        assertEquals(d.massKg, q.massKg, 0.0); assertEquals(d.heightM, q.heightM, 0.0); assertEquals(d.ageYears, q.ageYears)
        assertEquals(30, q.acclimatisationDays); assertEquals(d.restSeatedHr, q.restSeatedHr, 0.0); assertEquals(Lang.HI, q.language)
        assertEquals(0, UserProfile.fromJson("not json").acclimatisationDays)
        assertEquals(0, UserProfile.fromJson(null).acclimatisationDays)
    }

    @Test fun unknownAcclimatisationMeansNotAcclimatised() {
        assertEquals(0, UserProfile().acclimatisationDays)
        assertEquals(0, UserProfile().toShiftConfig(0).acclimatisationDays)
    }

    @Test fun profileFeedsTheShiftConfig() {
        val c = UserProfile("", Lang.TA, 80.0, 1.80, 35, 5, 60.0).toShiftConfig(1000L, "dev1")
        assertEquals(80.0, c.bodyMassKg, 0.0); assertEquals(1.80, c.heightM, 0.0); assertEquals("dev1", c.deviceId)
    }

    @Test fun bodyStepBlocksUntilValid() {
        val f = OnboardingFlow()
        assertTrue(f.next()) // language
        assertEquals(OnboardingStep.WORK, f.step)
        assertTrue(f.next()) // kind of work
        assertEquals(OnboardingStep.BODY, f.step)
        assertFalse(f.next()); assertNotNull(f.problem())
        f.massKg = 70.0; f.heightM = 1.7; f.age = 30
        assertNull(f.problem()); assertTrue(f.next())
    }

    @Test fun shortPhoneNumberIsRejectedButBlankIsFine() {
        val f = OnboardingFlow()
        f.next(); f.next(); f.massKg = 70.0; f.heightM = 1.7; f.age = 30; f.next(); f.next()
        assertEquals(OnboardingStep.CONTACT, f.step)
        f.contact = "123"; assertNotNull(f.problem())
        f.contact = ""; assertNull(f.problem())
    }

    @Test fun buildOnlyAfterTheLastStep() {
        val f = OnboardingFlow()
        assertNull(f.build())
        f.language = Lang.TE; f.next()
        f.workType = WorkType.DELIVERY; f.next()
        f.massKg = 75.0; f.heightM = 1.75; f.age = 28; f.next(); f.next(); f.next(); f.next()
        assertEquals(OnboardingStep.DONE, f.step)
        val p = f.build()!!
        assertEquals(Lang.TE, p.language); assertEquals(75.0, p.massKg, 0.0)
        assertEquals(UserProfile().restSeatedHr, p.restSeatedHr, 0.0) // blank resting pulse keeps the default
        assertFalse(p.shareLocationWithSupervisor) // sharing is opt-in
        assertEquals(WorkType.DELIVERY, p.workType)
    }

    @Test fun workTypeSurvivesStorageAndUnknownMeansTheGeneralCheck() {
        val p = UserProfile("", Lang.HI, 70.0, 1.7, 30, 0, 70.0, "", false, WorkType.CONSTRUCTION)
        assertEquals(WorkType.CONSTRUCTION, UserProfile.fromJson(p.toJson()).workType)
        assertEquals(WorkType.OTHER_GIG, UserProfile.fromJson("{\"work\": \"ASTRONAUT\"}").workType)
        assertEquals(WorkType.OTHER_GIG, UserProfile.fromJson("{}").workType) // a profile saved before this field existed
        assertEquals(WorkType.SECURITY, p.withWorkType(WorkType.SECURITY).workType)
        assertEquals(p.massKg, p.withWorkType(WorkType.SECURITY).massKg, 0.0) // changing the work changes nothing else
    }
}
