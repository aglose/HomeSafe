package com.meticulouscreations.homesafe.domain.model

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The polygons are Front Yard's real zones as of 2026-09-06 (hikvision_1), with the object
 * lists the user wants: only birds on the street and sidewalk (Frigate has no "nothing"), people
 * and dogs on the lawn, people, cars and dogs in the driveway. The paths are real `path_data`
 * samples from that day's events.
 */
class ZoneInferenceTest {

    private fun poly(vararg xy: Double) = MaskPolygon(xy.toList().chunked(2) { (x, y) -> MaskPoint(x, y) })

    private val frontLawn = DetectionZone("front_lawn", "Front lawn", poly(0.556, 0.494, 0.958, 0.722, 1.0, 1.0, 0.179, 1.0, 0.135, 0.685), listOf("person", "dog"))
    private val driveway = DetectionZone("driveway", "Driveway", poly(0.419, 0.458, 0.504, 0.5, 0.138, 0.676, 0.217, 0.504), listOf("person", "car", "dog"))
    private val sidewalk = DetectionZone("sidewalk", "Sidewalk", poly(1.0, 0.6, 0.998, 0.75, 0.331, 0.41, 0.44, 0.353, 0.704, 0.46), listOf("bird"))
    private val street = DetectionZone("street", "Street", poly(0.311, 0.113, 0.994, 0.33, 1.0, 0.607, 0.301, 0.287), listOf("bird"))
    private val frontYard = listOf(frontLawn, driveway, sidewalk, street)

    private fun event(label: String, zones: List<String> = emptyList(), vararg path: Pair<Double, Double>, subLabel: String? = null) = MomentEvent(
        id = "1", cameraName = "hikvision_1", label = label, subLabel = subLabel,
        startEpochSeconds = 1788726566.0, endEpochSeconds = null, topScore = 0.8, hasClip = true, hasSnapshot = false,
        zones = zones, pathPoints = path.map { (x, y) -> MaskPoint(x, y) },
    )

    private val today = LocalDate(2026, 9, 6)

    @Test
    fun carDrivingDownTheStreetIsDropped() {
        val car = event("car", emptyList(), 0.6219 to 0.2861, 0.7734 to 0.3222, 0.725 to 0.3667, 0.775 to 0.3222)
        assertNull(car.inZones(frontYard))
    }

    @Test
    fun carParkedAtTheCurbWhoseBoxJittersOntoTheSidewalkIsDroppedToo() {
        // The real 1:41 PM car: 13 samples in the street, one stray one in the sidewalk polygon,
        // and Frigate's own "sidewalk" tag. Neither place wants a car.
        val car = event(
            "car", listOf("sidewalk"),
            0.959 to 0.411, 0.967 to 0.472, 0.961 to 0.403, 0.958 to 0.467, 0.983 to 0.597, 0.969 to 0.408, 0.947 to 0.483,
        )
        assertNull(car.inZones(frontYard))
    }

    @Test
    fun personWalkingAlongTheSidewalkIsDropped() {
        val person = event("person", listOf("sidewalk"), 0.55 to 0.44, 0.6 to 0.45, 0.7 to 0.5)
        assertNull(person.inZones(frontYard))
    }

    @Test
    fun personWhoSteppedOntoTheLawnIsKeptAndPlacedThere() {
        val person = event("person", listOf("sidewalk"), 0.55 to 0.44, 0.6 to 0.75)
        val placed = assertNotNull(person.inZones(frontYard))
        assertEquals(listOf("front_lawn"), placed.zones, "the sidewalk leg is not a place that wanted them")
        val p = placed.present(today)
        assertEquals("Person on the front lawn", p.title)
        assertEquals("Front Yard · Front lawn", p.locationLabel)
    }

    @Test
    fun frigatesDrivewayTagIsKeptWhenTheSparsePathMissesTheSliver() {
        // Real shape of every driveway visit on record: Frigate tagged the driveway, the path samples all sit on the sidewalk.
        val person = event("person", listOf("sidewalk", "driveway"), 0.55 to 0.44, 0.5 to 0.43, 0.45 to 0.42)
        val placed = assertNotNull(person.inZones(frontYard))
        assertEquals(listOf("driveway"), placed.zones)
        assertEquals("Person in the driveway", placed.present(today).title)
    }

    @Test
    fun recognizedCarAtTheCurbIsKeptAndSaysWhereItIs() {
        // Sarah's Model Y parked in the street: nothing there wants a car, but Frigate named it.
        val tesla = event("car", listOf("sidewalk"), 0.959 to 0.411, 0.967 to 0.472, 0.983 to 0.597, subLabel = "sarahs_tesla")
        val placed = assertNotNull(tesla.inZones(frontYard))
        assertEquals(listOf("sidewalk", "street"), placed.zones)
        val p = placed.present(today)
        assertEquals("Sarah's Tesla on the street", p.title)
        assertEquals("Front Yard · Sidewalk, Street", p.locationLabel)
    }

    @Test
    fun frigatesUnknownMarkerIsNotARecognition() {
        val stranger = event("person", emptyList(), 0.55 to 0.44, subLabel = FaceLibrary.UNKNOWN_GUESS)
        assertNull(stranger.inZones(frontYard))
    }

    @Test
    fun unrecognizedObjectThatEnteredNoZoneIsDroppedOnACameraWithZones() {
        val cat = event("cat", emptyList(), 0.05 to 0.05, 0.1 to 0.1)
        assertNull(cat.inZones(frontYard))
    }

    @Test
    fun recognizedObjectThatEnteredNoZoneIsKeptAsJustTheCamera() {
        val andrew = event("person", emptyList(), 0.05 to 0.05, subLabel = "andrew")
        val placed = assertNotNull(andrew.inZones(frontYard))
        assertEquals(emptyList(), placed.zones)
        assertEquals("Front Yard", placed.present(today).locationLabel)
    }

    @Test
    fun cameraWithoutZonesLeavesEveryEventAlone() {
        val car = event("car", emptyList(), 0.6219 to 0.2861)
        assertSame(car, car.inZones(emptyList()))
        assertEquals(listOf(car), listOf(car).inZones(mapOf("hikvision_1" to emptyList())))
        assertEquals(listOf(car), listOf(car).inZones(emptyMap()))
    }

    @Test
    fun labelMatchingIgnoresCase() {
        val bird = event("Bird", emptyList(), 0.6219 to 0.2861)
        assertEquals(listOf("street"), assertNotNull(bird.inZones(frontYard)).zones)
    }

    @Test
    fun zoneFrigateTaggedThatWeCannotSeeIsTrusted() {
        val car = event("car", listOf("porch"))
        assertEquals(listOf("porch"), assertNotNull(car.inZones(frontYard)).zones)
    }

    @Test
    fun feedFiltersPerCamera() {
        val streetCar = event("car", emptyList(), 0.6219 to 0.2861)
        val backyardCar = streetCar.copy(id = "2", cameraName = "hikvision_2")
        val lawnPerson = event("person", emptyList(), 0.6 to 0.75).copy(id = "3")
        val feed = listOf(streetCar, backyardCar, lawnPerson).inZones(mapOf("hikvision_1" to frontYard))
        assertEquals(listOf("2", "3"), feed.map { it.id })
        assertEquals(listOf("front_lawn"), feed[1].zones)
    }
}
