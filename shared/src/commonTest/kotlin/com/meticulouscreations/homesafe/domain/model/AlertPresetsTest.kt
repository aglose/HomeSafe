package com.meticulouscreations.homesafe.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlertPresetsTest {

    // The Front Yard as it's drawn today, plus two cameras with no zones at all.
    private val lawn = AlertZone("hikvision_1", "front_lawn")
    private val driveway = AlertZone("hikvision_1", "driveway")
    private val sidewalk = AlertZone("hikvision_1", "sidewalk")
    private val street = AlertZone("hikvision_1", "street")
    private val yardElse = AlertZone("hikvision_1", null)
    private val backyard = AlertZone("hikvision_2", null)
    private val frontDoor = AlertZone("amcrest_1", null)
    private val places = listOf(lawn, driveway, sidewalk, street, yardElse, backyard, frontDoor)

    private val people = setOf(MomentCategory.PEOPLE)
    private val peopleAndVehicles = setOf(MomentCategory.PEOPLE, MomentCategory.VEHICLES)

    @Test
    fun outOfTheBoxRulesReadAsPeoplePlusVehicles() {
        assertEquals(AlertPreset.PEOPLE_AND_VEHICLES, AlertSettings.DEFAULT.matchingPreset(places))
    }

    @Test
    fun applyingAPresetWritesEveryPlaceAndThenMatchesIt() {
        AlertPreset.entries.forEach { preset ->
            val applied = AlertSettings.DEFAULT.withPreset(preset, places)
            assertEquals(places.toSet(), applied.zoneRules.keys, "$preset writes every place out")
            assertEquals(preset, applied.matchingPreset(places), "$preset is recognised once applied")
        }
    }

    @Test
    fun drivewayCarsMeansVehiclesOnlyWhereACarIsSomeoneArriving() {
        val applied = AlertSettings.DEFAULT.withPreset(AlertPreset.PEOPLE_AND_DRIVEWAY_CARS, places)
        assertEquals(peopleAndVehicles, applied.categoriesFor(driveway))
        listOf(lawn, sidewalk, street, yardElse, backyard, frontDoor).forEach { place ->
            assertEquals(people, applied.categoriesFor(place), "$place: people only")
        }
        assertFalse(applied.notifies("hikvision_1", listOf("street"), MomentCategory.VEHICLES), "passing traffic stays quiet")
        assertTrue(applied.notifies("hikvision_1", listOf("street", "driveway"), MomentCategory.VEHICLES), "a car that turns in does not")
    }

    @Test
    fun arrivalZonesAreRecognisedByName() {
        listOf("driveway", "Driveway", "front_drive", "garage_door", "carport", "parking_pad", "side-gate").forEach {
            assertTrue(isArrivalZone(it), it)
        }
        listOf("street", "sidewalk", "front_lawn", "porch", "backyard").forEach {
            assertFalse(isArrivalZone(it), it)
        }
    }

    @Test
    fun theDrivewayPresetIsOnlyOfferedWhereThereIsADriveway() {
        assertEquals(AlertPreset.entries, AlertPreset.offeredFor(places))
        val noDriveway = listOf(lawn, street, yardElse, backyard)
        assertEquals(
            listOf(AlertPreset.PEOPLE_ONLY, AlertPreset.PEOPLE_AND_VEHICLES, AlertPreset.EVERYTHING),
            AlertPreset.offeredFor(noDriveway),
        )
        // Without a driveway it would be the same rules as "People only", which is the one that shows.
        assertEquals(AlertPreset.PEOPLE_ONLY, AlertSettings.DEFAULT.withPreset(AlertPreset.PEOPLE_ONLY, noDriveway).matchingPreset(noDriveway))
    }

    @Test
    fun anyTweakAfterwardsReadsAsCustom() {
        val applied = AlertSettings.DEFAULT.withPreset(AlertPreset.PEOPLE_ONLY, places)
        val tweaked = applied.withCategory(backyard, MomentCategory.ANIMALS, true)
        assertNull(tweaked.matchingPreset(places))
        val undone = tweaked.withCategory(backyard, MomentCategory.ANIMALS, false)
        assertEquals(AlertPreset.PEOPLE_ONLY, undone.matchingPreset(places), "undoing the tweak finds the preset again")
    }

    @Test
    fun placesOutsideTheListKeepTheirRules() {
        val elsewhere = AlertZone("disabled_cam", null)
        val start = AlertSettings.DEFAULT.withCategory(elsewhere, MomentCategory.ANIMALS, true)
        val applied = start.withPreset(AlertPreset.PEOPLE_ONLY, places)
        assertEquals(start.categoriesFor(elsewhere), applied.categoriesFor(elsewhere))
        assertEquals(AlertPreset.PEOPLE_ONLY, applied.matchingPreset(places), "only the listed places are compared")
    }

    @Test
    fun noPlacesMatchNothing() {
        assertNull(AlertSettings.DEFAULT.matchingPreset(emptyList()))
    }

    @Test
    fun presetsLeaveEverythingButTheZoneRulesAlone() {
        val start = AlertSettings(
            pushNotificationsEnabled = true,
            quietFamiliarPeople = true,
            quietHours = QuietHours(enabled = true),
            onlyWhenAway = true,
        )
        val applied = start.withPreset(AlertPreset.EVERYTHING, places)
        assertEquals(start, applied.copy(zoneRules = start.zoneRules))
    }
}
