package com.meticulouscreations.homesafe.domain.model

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The home screen's "In view now" strip, which answers a different question from the Moments feed
 * and therefore keeps what the feed throws away: the car that has only ever been seen sitting in
 * the driveway. Fixtures are the Front Yard shapes [VehicleVisitsTest] uses — a parked car's
 * jitter around one spot, and a car crossing the frame.
 */
class StationaryObjectsTest {

    private val driveway = DetectionBox(0.55, 0.40, 0.20, 0.22)
    private val day = LocalDate(2026, 9, 16)
    private val utc = TimeZone.UTC

    private fun at(hour: Int, minute: Int): Double = day.toEpochDays() * 86_400.0 + hour * 3_600 + minute * 60

    /** Eight samples inside the box: the detector's box flickering on a car that is going nowhere. */
    private val parkedPath = List(8) { 0.65 + (it % 2) * 0.01 to 0.62 }

    /** Twenty samples clear across the frame: a car on the move. */
    private val movingPath = List(20) { 0.05 + it * 0.045 to 0.60 }

    private fun sighting(
        id: String,
        start: Double,
        end: Double? = start + 60,
        label: String = "car",
        camera: String = "hikvision_1",
        subLabel: String? = null,
        subLabelScore: Double? = null,
        box: DetectionBox? = driveway,
        path: List<Pair<Double, Double>> = parkedPath,
        zones: List<String> = listOf("driveway"),
    ) = MomentEvent(
        id = id, cameraName = camera, label = label, subLabel = subLabel,
        startEpochSeconds = start, endEpochSeconds = end, topScore = 0.8, hasClip = true, hasSnapshot = false,
        zones = zones, pathPoints = path.map { (x, y) -> MaskPoint(x, y) }, box = box, subLabelScore = subLabelScore,
    )

    @Test
    fun aCarOnlyEverSeenParkedIsInView() {
        // Exactly what mergeVehicleVisits drops — nothing here ever moved — and exactly what the strip is for.
        val sightings = listOf(
            sighting("s1", at(8, 12)),
            sighting("s2", at(8, 26)),
            sighting("s3", at(8, 41)),
        )

        val inView = sightings.stationaryObjects(nowEpochSeconds = at(8, 45))

        assertEquals(emptyList(), sightings.mergeVehicleVisits(), "the feed has nothing to say about a car that only ever sat there")
        assertEquals(1, inView.size, "one car, not three")
        assertEquals("s3", inView[0].thumbnailEventId, "the newest sighting's crop is where the car is now")
        assertEquals(at(8, 12), inView[0].firstSeenEpochSeconds)
        assertEquals(at(8, 42), inView[0].lastSeenEpochSeconds)
        assertEquals(3, inView[0].sightings)
        assertTrue(inView[0].sinceIsKnown, "nothing said the fetch ran out, so the arrival is taken at face value")
    }

    @Test
    fun aCarDrivingPastIsNotParked() {
        val passing = listOf(sighting("drive", at(9, 0), path = movingPath, zones = listOf("street")))

        assertEquals(emptyList(), passing.stationaryObjects(nowEpochSeconds = at(9, 2)))
    }

    @Test
    fun arrivalAnchorsTheStayAndDepartureEndsIt() {
        val arriving = sighting("in", at(17, 30), path = movingPath)
        val parked = sighting("parked", at(17, 33))
        val leaving = sighting("out", at(17, 36), path = movingPath)

        val afterParking = listOf(arriving, parked).stationaryObjects(nowEpochSeconds = at(17, 40))
        assertEquals(1, afterParking.size)
        assertEquals(at(17, 30), afterParking[0].firstSeenEpochSeconds, "the stay starts when the car pulled in, not when it settled")
        assertEquals(2, afterParking[0].sightings)

        val afterLeaving = listOf(arriving, parked, leaving).stationaryObjects(nowEpochSeconds = at(17, 40))
        assertEquals(emptyList(), afterLeaving, "a car whose latest sighting is moving is not furniture")
    }

    @Test
    fun aCarNotSeenForHalfAnHourIsNoLongerClaimed() {
        val parked = listOf(sighting("parked", at(6, 0), end = at(6, 1)))

        assertEquals(1, parked.stationaryObjects(nowEpochSeconds = at(6, 30)).size, "inside the window it is still there")
        assertEquals(emptyList(), parked.stationaryObjects(nowEpochSeconds = at(6, 32)), "past it the app has no evidence either way")
    }

    @Test
    fun aSightingStillInProgressIsInViewHoweverLongItRuns() {
        val parked = listOf(sighting("tracking", at(6, 0), end = null))

        val inView = parked.stationaryObjects(nowEpochSeconds = at(11, 0))

        assertEquals(1, inView.size, "Frigate is tracking the object right now, whatever the clock says")
        assertTrue(inView[0].seenRecently, "an unfinished sighting is as current as it gets")
        assertEquals(at(6, 0), inView[0].lastSeenEpochSeconds, "an unfinished sighting is last seen when it began")
    }

    @Test
    fun theBestScoredNameWinsAndSurvivesTheSightingsThatMissedIt() {
        val stay = listOf(
            sighting("s1", at(8, 0), subLabel = "sarahs_tesla", subLabelScore = 0.62),
            sighting("s2", at(8, 10), subLabel = "ron_judys_mercedes", subLabelScore = 0.91),
            sighting("s3", at(8, 20)),
        ).stationaryObjects(nowEpochSeconds = at(8, 25))

        assertEquals("ron_judys_mercedes", stay.single().subLabel, "the classifier flips between similar cars; the surest sighting names the card")
        assertTrue(stay.single().isRecognized)
    }

    @Test
    fun onlyVehiclesCount() {
        val mixed = listOf(
            sighting("person", at(8, 0), label = "person"),
            sighting("dog", at(8, 1), label = "dog"),
            sighting("car", at(8, 2)),
        )

        assertEquals(listOf("car"), mixed.stationaryObjects(nowEpochSeconds = at(8, 5)).map { it.thumbnailEventId })
    }

    @Test
    fun twoCarsAreListedNewestArrivalFirst() {
        val inView = listOf(
            sighting("morning", at(8, 0), camera = "hikvision_1"),
            sighting("morning2", at(8, 30), camera = "hikvision_1"),
            sighting("evening", at(8, 40), camera = "hikvision_2"),
        ).stationaryObjects(nowEpochSeconds = at(8, 45))

        assertEquals(listOf("evening", "morning2"), inView.map { it.thumbnailEventId }, "the car that just pulled in leads")
    }

    @Test
    fun aStayThatStartsAtTheEdgeOfTheFetchDoesNotClaimAnArrivalTime() {
        val sightings = listOf(sighting("s1", at(8, 0)), sighting("s2", at(8, 20)))

        val atEdge = sightings.stationaryObjects(nowEpochSeconds = at(8, 25), oldestFetchedEpochSeconds = at(8, 0))
        assertEquals(false, atEdge.single().sinceIsKnown, "the car was already there when the page began: since when is a guess")
        assertNull(atEdge.single().present(day, utc).sinceLabel)

        val wellInside = sightings.stationaryObjects(nowEpochSeconds = at(8, 25), oldestFetchedEpochSeconds = at(7, 0))
        assertTrue(wellInside.single().sinceIsKnown, "the page reached back an hour further: the app watched this one arrive")
    }

    @Test
    fun aCardNamesTheCarThePlaceAndWhenItGotThere() {
        val stay = listOf(sighting("s1", at(8, 12), subLabel = "sarahs_tesla", subLabelScore = 0.9, end = at(8, 13)))
            .stationaryObjects(nowEpochSeconds = at(8, 15))
            .single()

        val presentation = stay.present(today = day, timeZone = utc)

        assertEquals("Sarah's Tesla", presentation.title)
        assertEquals("Driveway", presentation.placeLabel)
        assertEquals("since 8:12 AM", presentation.sinceLabel)
        assertNull(presentation.lastSeenLabel, "seen a moment ago: the card needn't hedge")
    }

    @Test
    fun anUnknownCarIsNamedByItsLabelAndPlacedByItsCameraWhenItIsInNoZone() {
        val stay = listOf(sighting("s1", at(8, 12), label = "truck", zones = emptyList()))
            .stationaryObjects(nowEpochSeconds = at(8, 15))
            .single()

        val presentation = stay.present(today = day, timeZone = utc)

        assertEquals("Truck", presentation.title)
        assertEquals("Front Yard", presentation.placeLabel)
    }

    @Test
    fun aCarParkedSinceLastNightSaysSoOnTheDayItIsStillThere() {
        val lastNight = at(0, 0) - 5 * 3_600
        val stay = listOf(sighting("tracking", lastNight, end = null))
            .stationaryObjects(nowEpochSeconds = at(6, 20))
            .single()

        assertEquals("since 7:00 PM yesterday", stay.present(today = day, timeZone = utc).sinceLabel)
    }

    @Test
    fun aCarThatHasGoneQuietSaysWhenItWasLastSeen() {
        // Still inside the half hour that keeps it on the strip, but no longer a live confirmation.
        val stay = listOf(sighting("s1", at(6, 0), end = at(6, 1)))
            .stationaryObjects(nowEpochSeconds = at(6, 20))
            .single()

        assertEquals("last seen 6:01 AM", stay.present(today = day, timeZone = utc).lastSeenLabel)
        assertEquals(false, stay.seenRecently)
    }
}
