package com.meticulouscreations.homesafe.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AlertSettingsTest {

    private val driveway = AlertZone("front", "driveway")
    private val sidewalk = AlertZone("front", "sidewalk")
    private val anywhere = AlertZone("front", null)

    @Test
    fun untouchedPlacesUseTheDefaults() {
        val settings = AlertSettings.DEFAULT
        assertTrue(settings.notifies("front", listOf("driveway"), MomentCategory.PEOPLE))
        assertTrue(settings.notifies("front", listOf("driveway"), MomentCategory.VEHICLES))
        assertFalse(settings.notifies("front", listOf("driveway"), MomentCategory.ANIMALS))
        assertFalse(settings.notifies("front", emptyList(), MomentCategory.ALL), "uncategorised labels never notify")
    }

    @Test
    fun noZoneMeansTheCamerasAnywhereElse() {
        val settings = AlertSettings.DEFAULT.withCategory(anywhere, MomentCategory.PEOPLE, false)
        assertFalse(settings.notifies("front", emptyList(), MomentCategory.PEOPLE))
        assertFalse(settings.notifies("front", listOf(""), MomentCategory.PEOPLE), "a blank zone name counts as none")
        assertTrue(settings.notifies("front", listOf("driveway"), MomentCategory.PEOPLE), "zones keep their own choice")
        assertTrue(settings.notifies("back", emptyList(), MomentCategory.PEOPLE), "other cameras are unaffected")
    }

    @Test
    fun anyWantedZoneOnTheWayIsEnough() {
        val settings = AlertSettings.DEFAULT
            .withCategory(driveway, MomentCategory.PEOPLE, false)
            .withCategory(sidewalk, MomentCategory.PEOPLE, false)
        assertFalse(settings.notifies("front", listOf("sidewalk", "driveway"), MomentCategory.PEOPLE))
        val drivewayBackOn = settings.withCategory(driveway, MomentCategory.PEOPLE, true)
        assertTrue(drivewayBackOn.notifies("front", listOf("sidewalk", "driveway"), MomentCategory.PEOPLE))
    }

    @Test
    fun cameraSwitchSilencesEveryPlaceOnThatCameraOnly() {
        val zones = listOf("driveway", "sidewalk")
        val off = AlertSettings.DEFAULT.withAlertsOn("front", zones, enabled = false)
        assertFalse(off.alertsEnabledOn("front", zones))
        assertFalse(off.notifies("front", listOf("driveway"), MomentCategory.PEOPLE))
        assertFalse(off.notifies("front", emptyList(), MomentCategory.VEHICLES))
        assertTrue(off.alertsEnabledOn("back", emptyList()), "other cameras are unaffected")
        assertTrue(off.notifies("back", emptyList(), MomentCategory.PEOPLE))
    }

    @Test
    fun cameraSwitchBackOnRestoresDefaultsExceptWhereAChoiceWasMadeMeanwhile() {
        val zones = listOf("driveway", "sidewalk")
        val off = AlertSettings.DEFAULT.withAlertsOn("front", zones, enabled = false)
        val tweakedWhileOff = off.withCategory(driveway, MomentCategory.VEHICLES, true) // vehicles only there
        val backOn = tweakedWhileOff.withAlertsOn("front", zones, enabled = true)
        assertTrue(backOn.alertsEnabledOn("front", zones))
        assertEquals(AlertSettings.DEFAULT_CATEGORIES, backOn.categoriesFor(sidewalk))
        assertEquals(AlertSettings.DEFAULT_CATEGORIES, backOn.categoriesFor(anywhere))
        assertEquals(setOf(MomentCategory.VEHICLES), backOn.categoriesFor(driveway), "a choice made while off is kept")
        val plainOnAgain = off.withAlertsOn("front", zones, enabled = true)
        assertEquals(setOf(driveway, sidewalk, anywhere), plainOnAgain.zoneRules.keys, "on is written out, never left to a dropped entry")
        assertTrue(plainOnAgain.zoneRules.values.all { it == AlertSettings.DEFAULT_CATEGORIES })
    }

    @Test
    fun cameraSwitchReadsAsOnWhileAnyPlaceStillNotifies() {
        val zones = listOf("driveway")
        val onlyAnywhere = AlertSettings.DEFAULT
            .withCategory(driveway, MomentCategory.PEOPLE, false)
            .withCategory(driveway, MomentCategory.VEHICLES, false)
        assertTrue(onlyAnywhere.alertsEnabledOn("front", zones))
        val silent = onlyAnywhere
            .withCategory(anywhere, MomentCategory.PEOPLE, false)
            .withCategory(anywhere, MomentCategory.VEHICLES, false)
        assertFalse(silent.alertsEnabledOn("front", zones))
        assertTrue(silent.withAlertsOn("front", zones, enabled = true).notifies("front", listOf("driveway"), MomentCategory.PEOPLE))
    }

    @Test
    fun changingOneCategoryLeavesTheOthersAsTheyWere() {
        val settings = AlertSettings.DEFAULT.withCategory(driveway, MomentCategory.ANIMALS, true)
        assertEquals(setOf(MomentCategory.PEOPLE, MomentCategory.VEHICLES, MomentCategory.ANIMALS), settings.categoriesFor(driveway))
        val quieter = settings.withCategory(driveway, MomentCategory.VEHICLES, false)
        assertEquals(setOf(MomentCategory.PEOPLE, MomentCategory.ANIMALS), quieter.categoriesFor(driveway))
        assertEquals(AlertSettings.DEFAULT_CATEGORIES, quieter.categoriesFor(sidewalk), "other places untouched")
    }

    @Test
    fun onlyStrangersSilencesRecognisedPeopleAndNothingElse() {
        val strangersOnly = AlertSettings.DEFAULT.copy(quietFamiliarPeople = true)
        assertFalse(strangersOnly.notifies("front", listOf("driveway"), MomentCategory.PEOPLE, recognized = true), "a named face is family")
        assertTrue(strangersOnly.notifies("front", listOf("driveway"), MomentCategory.PEOPLE, recognized = false), "an unnamed person is a stranger")
        assertTrue(strangersOnly.notifies("front", listOf("driveway"), MomentCategory.VEHICLES, recognized = true), "a recognised plate is not a person")
        assertTrue(AlertSettings.DEFAULT.notifies("front", listOf("driveway"), MomentCategory.PEOPLE, recognized = true), "off by default: everyone notifies")
        val mutedDriveway = strangersOnly.withCategory(AlertZone("front", "driveway"), MomentCategory.PEOPLE, false)
        assertFalse(mutedDriveway.notifies("front", listOf("driveway"), MomentCategory.PEOPLE, recognized = false), "zone rules still apply to strangers")
    }

    @Test
    fun quietHoursWrapMidnightAndEndJustBeforeTheirEndMinute() {
        val night = QuietHours(enabled = true, startMinute = 22 * 60, endMinute = 7 * 60)
        assertTrue(night.contains(22 * 60))
        assertTrue(night.contains(23 * 60 + 59))
        assertTrue(night.contains(0))
        assertTrue(night.contains(6 * 60 + 59))
        assertFalse(night.contains(7 * 60), "7:00 is the first minute alerts are back")
        assertFalse(night.contains(21 * 60 + 59))
        assertFalse(night.contains(12 * 60))
    }

    @Test
    fun aDaytimeWindowIsAPlainRange() {
        val nap = QuietHours(enabled = true, startMinute = 13 * 60, endMinute = 15 * 60)
        assertTrue(nap.contains(13 * 60))
        assertTrue(nap.contains(14 * 60 + 59))
        assertFalse(nap.contains(15 * 60))
        assertFalse(nap.contains(12 * 60 + 59))
        assertFalse(nap.contains(23 * 60))
    }

    @Test
    fun quietHoursThatAreOffOrEmptyNeverSilence() {
        assertFalse(QuietHours.DEFAULT.contains(23 * 60), "off by default, though the 10 PM to 7 AM window is ready")
        assertFalse(QuietHours(enabled = true, startMinute = 8 * 60, endMinute = 8 * 60).contains(8 * 60), "starts where it ends: empty, not all day")
    }

    @Test
    fun ordinaryAlertsAreSilencedInQuietHoursAndAllDayWhenOnlyAwayIsWanted() {
        val night = AlertSettings.DEFAULT.copy(quietHours = QuietHours(enabled = true, startMinute = 22 * 60, endMinute = 7 * 60))
        assertTrue(night.ordinaryAlertsSilenced(23 * 60))
        assertFalse(night.ordinaryAlertsSilenced(12 * 60))
        assertFalse(AlertSettings.DEFAULT.ordinaryAlertsSilenced(23 * 60))
        val onlyAway = AlertSettings.DEFAULT.copy(onlyWhenAway = true)
        assertTrue(onlyAway.ordinaryAlertsSilenced(12 * 60))
        assertTrue(onlyAway.ordinaryAlertsSilenced(23 * 60))
        assertTrue(night.notifies("front", listOf("driveway"), MomentCategory.PEOPLE), "the rules themselves are untouched; the poller asks both")
    }
}
