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

    private fun event(
        label: String,
        zones: List<String> = emptyList(),
        vararg path: Pair<Double, Double>,
        subLabel: String? = null,
        box: DetectionBox? = null,
    ) = MomentEvent(
        id = "1", cameraName = "hikvision_1", label = label, subLabel = subLabel,
        startEpochSeconds = 1788726566.0, endEpochSeconds = null, topScore = 0.8, hasClip = true, hasSnapshot = false,
        zones = zones, pathPoints = path.map { (x, y) -> MaskPoint(x, y) }, box = box,
    )

    /** A street car's box on the Front Yard's detect frame: about a tenth of it wide. */
    private val carBox = DetectionBox(0.9, 0.35, 0.1, 0.12)

    private val today = LocalDate(2026, 9, 6)

    /** A minute into [event]'s detections, which are all still in progress. */
    private val now = 1788726566.0 + 60

    @Test
    fun carDrivingDownTheStreetIsDropped() {
        val car = event("car", emptyList(), 0.6219 to 0.2861, 0.7734 to 0.3222, 0.725 to 0.3667, 0.775 to 0.3222)
        assertNull(car.inZones(frontYard, now))
    }

    @Test
    fun carParkedAtTheCurbWhoseBoxJittersOntoTheSidewalkIsDroppedToo() {
        // The real 1:41 PM car: 13 samples in the street, one stray one in the sidewalk polygon,
        // and Frigate's own "sidewalk" tag. Neither place wants a car.
        val car = event(
            "car", listOf("sidewalk"),
            0.959 to 0.411, 0.967 to 0.472, 0.961 to 0.403, 0.958 to 0.467, 0.983 to 0.597, 0.969 to 0.408, 0.947 to 0.483,
        )
        assertNull(car.inZones(frontYard, now))
    }

    @Test
    fun personWalkingAlongTheSidewalkIsDropped() {
        val person = event("person", listOf("sidewalk"), 0.55 to 0.44, 0.6 to 0.45, 0.7 to 0.5)
        assertNull(person.inZones(frontYard, now))
    }

    @Test
    fun personWhoSteppedOntoTheLawnIsKeptAndPlacedThere() {
        val person = event("person", listOf("sidewalk"), 0.55 to 0.44, 0.6 to 0.75)
        val placed = assertNotNull(person.inZones(frontYard, now))
        assertEquals(listOf("front_lawn"), placed.zones, "the sidewalk leg is not a place that wanted them")
        val p = placed.present(today)
        assertEquals(MomentTexts.onThe(MomentTexts.person, "front lawn"), p.title)
        assertEquals(MomentTexts.location("Front Yard", "Front lawn"), p.locationLabel)
    }

    @Test
    fun frigatesDrivewayTagIsKeptWhenTheSparsePathMissesTheSliver() {
        // Real shape of every driveway visit on record: Frigate tagged the driveway, the path samples all sit on the sidewalk.
        val person = event("person", listOf("sidewalk", "driveway"), 0.55 to 0.44, 0.5 to 0.43, 0.45 to 0.42)
        val placed = assertNotNull(person.inZones(frontYard, now))
        assertEquals(listOf("driveway"), placed.zones)
        assertEquals(MomentTexts.inThe(MomentTexts.person, "driveway"), placed.present(today).title)
    }

    @Test
    fun recognizedCarAtTheCurbIsKeptAndSaysWhereItIs() {
        // Sarah's Model Y parked in the street: nothing there wants a car, but Frigate named it and it sat still.
        val tesla = event("car", listOf("sidewalk"), 0.959 to 0.411, 0.967 to 0.472, 0.983 to 0.597, subLabel = "sarahs_tesla", box = carBox)
        val placed = assertNotNull(tesla.inZones(frontYard, now))
        assertEquals(listOf("sidewalk", "street"), placed.zones)
        val p = placed.present(today)
        assertEquals(MomentTexts.onThe(MomentTexts.named("Sarah's Tesla"), "street"), p.title)
        assertEquals(MomentTexts.location("Front Yard", "Sidewalk", "Street"), p.locationLabel)
    }

    @Test
    fun namedCarDrivingDownTheStreetIsDroppedLikeAnyOther() {
        // The 2026-09-23 failure: the classifier called most passing cars "Andrew's Tesla".
        val passing = event(
            "car", emptyList(),
            0.33 to 0.2, 0.42 to 0.23, 0.52 to 0.26, 0.62 to 0.29, 0.72 to 0.32, 0.82 to 0.36,
            subLabel = "andrews_tesla", box = carBox,
        )
        assertNull(passing.inZones(frontYard, now))
    }

    @Test
    fun namedCarPullingIntoTheDrivewayIsPlacedOnlyWhereCarsAreWanted() {
        val arriving = event(
            "car", listOf("driveway"),
            0.72 to 0.32, 0.6 to 0.29, 0.48 to 0.3, 0.36 to 0.45, 0.3 to 0.55,
            subLabel = "andrews_tesla", box = carBox,
        )
        val placed = assertNotNull(arriving.inZones(frontYard, now))
        assertEquals(listOf("driveway"), placed.zones)
        assertEquals(MomentTexts.inThe(MomentTexts.named("Andrew's Tesla"), "driveway"), placed.present(today).title)
    }

    /** The driveway as redrawn by 2026-10-05; the other three outlines had not changed. */
    private val drivewayNow = DetectionZone("driveway", "Driveway", poly(0.419, 0.458, 0.504, 0.5, 0.34, 0.72, 0.12, 0.74, 0.217, 0.504), listOf("person", "car", "dog"))
    private val frontYardNow = listOf(frontLawn, drivewayNow, sidewalk, street)

    @Test
    fun namedCarTrackedSinceItDroveInIsPlacedWhereItParkedNotAlongTheRoadItTook() {
        // 2026-10-05, 270 minutes into one event: up the street, then down to the garage, where its
        // bottom-centre rests just above the driveway's outline. The strip read "Street".
        val andrew = TrackedCars.andrewParked
        val readAt = TrackedCars.ANDREW_ARRIVED + TrackedCars.ANDREW_READ_AT

        val placed = assertNotNull(andrew.inZones(frontYardNow, readAt), "parked, so the name keeps it though no zone wanted it")
        assertEquals(emptyList(), placed.zones, "it crossed the street to get there, but it is not on the street")
        val card = listOf(placed).stationaryObjects(nowEpochSeconds = readAt).single().present(today)
        assertEquals("Front Yard", card.placeLabel)

        // Sarah's car the same day did stop on the street, and says so.
        val sarah = assertNotNull(TrackedCars.sarahParked.inZones(frontYardNow, TrackedCars.SARAH_ARRIVED + TrackedCars.SARAH_READ_AT))
        assertEquals(listOf("street"), sarah.zones)
    }

    @Test
    fun namedCarStillPullingInHasToBeWantedLikeAnyOther() {
        val arrival = TrackedCars.sarahsPathParked.take(TrackedCars.SARAHS_ARRIVAL_POINTS)
        val pullingIn = TrackedCars.event("sarah", TrackedCars.SARAH_ARRIVED, TrackedCars.sarahsBox, arrival, "sarahs_car")

        assertNull(pullingIn.inZones(frontYardNow, TrackedCars.SARAH_ARRIVED + 60), "a named car crossing the street is what the classifier gets wrong")
        assertNotNull(pullingIn.inZones(frontYardNow, TrackedCars.SARAH_ARRIVED + 6 * 60), "one that then sat at the curb for five minutes is not passing traffic")
    }

    @Test
    fun parkedCarsLastZoneIsWhereItIsWhateverOrderItsZonesWereFoundIn() {
        // Up the street and into the driveway, which Frigate tagged: Frigate's tags come first in
        // the list and the street is found afterwards from the path, but the car is in the driveway.
        val start = 1791241252.5
        val intoTheDriveway =
            listOf(Triple(0.72, 0.32, 1.0), Triple(0.6, 0.29, 2.0), Triple(0.48, 0.3, 3.0), Triple(0.4, 0.4, 4.0), Triple(0.33, 0.55, 5.0), Triple(0.3, 0.6, 6.0))
        val parked = TrackedCars.event("in", start, carBox, intoTheDriveway, "andrews_tesla", zones = listOf("driveway"))
        assertEquals(listOf("driveway"), assertNotNull(parked.inZones(frontYardNow, start + 3_600)).zones)

        // Out of the driveway and left at the curb: it was wanted in the driveway, and it is on the street.
        val toTheCurb = intoTheDriveway.reversed().mapIndexed { i, (x, y, _) -> Triple(x, y, 1.0 + i) }
        val atTheCurb = TrackedCars.event("out", start, carBox, toTheCurb, "andrews_tesla", zones = listOf("driveway"))
        val placed = assertNotNull(atTheCurb.inZones(frontYardNow, start + 3_600))
        assertEquals(listOf("driveway", "street"), placed.zones)
        assertEquals(placed, placed.inZones(frontYardNow, start + 3_600), "placing what the cache already placed changes nothing")
    }

    @Test
    fun frigatesUnknownMarkerIsNotARecognition() {
        val stranger = event("person", emptyList(), 0.55 to 0.44, subLabel = FaceLibrary.UNKNOWN_GUESS)
        assertNull(stranger.inZones(frontYard, now))
    }

    @Test
    fun unrecognizedObjectThatEnteredNoZoneIsDroppedOnACameraWithZones() {
        val cat = event("cat", emptyList(), 0.05 to 0.05, 0.1 to 0.1)
        assertNull(cat.inZones(frontYard, now))
    }

    @Test
    fun recognizedObjectThatEnteredNoZoneIsKeptAsJustTheCamera() {
        val andrew = event("person", emptyList(), 0.05 to 0.05, subLabel = "andrew")
        val placed = assertNotNull(andrew.inZones(frontYard, now))
        assertEquals(emptyList(), placed.zones)
        assertEquals(MomentTexts.location("Front Yard"), placed.present(today).locationLabel)
    }

    @Test
    fun cameraWithoutZonesLeavesEveryEventAlone() {
        val car = event("car", emptyList(), 0.6219 to 0.2861)
        assertSame(car, car.inZones(emptyList(), now))
        assertEquals(listOf(car), listOf(car).inZones(mapOf("hikvision_1" to emptyList()), now))
        assertEquals(listOf(car), listOf(car).inZones(emptyMap(), now))
    }

    @Test
    fun labelMatchingIgnoresCase() {
        val bird = event("Bird", emptyList(), 0.6219 to 0.2861)
        assertEquals(listOf("street"), assertNotNull(bird.inZones(frontYard, now)).zones)
    }

    @Test
    fun zoneFrigateTaggedThatWeCannotSeeIsTrusted() {
        val car = event("car", listOf("porch"))
        assertEquals(listOf("porch"), assertNotNull(car.inZones(frontYard, now)).zones)
    }

    @Test
    fun feedFiltersPerCamera() {
        val streetCar = event("car", emptyList(), 0.6219 to 0.2861)
        val backyardCar = streetCar.copy(id = "2", cameraName = "hikvision_2")
        val lawnPerson = event("person", emptyList(), 0.6 to 0.75).copy(id = "3")
        val feed = listOf(streetCar, backyardCar, lawnPerson).inZones(mapOf("hikvision_1" to frontYard), now)
        assertEquals(listOf("2", "3"), feed.map { it.id })
        assertEquals(listOf("front_lawn"), feed[1].zones)
    }
}
