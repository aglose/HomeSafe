package com.meticulouscreations.homesafe.domain.model

import com.meticulouscreations.homesafe.text.UiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.moments_seen_last_seen
import homesafe.shared.generated.resources.moments_seen_still_there
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Shapes taken from Front Yard on 2026-09-07: a car parked at the curb whose path is jitter pairs
 * around one spot, ended by the burst of far-away points a passing car leaves when it steals the
 * tracker; and the string of re-detections that followed.
 */
class VehicleVisitsTest {

    private val curb = DetectionBox(0.75, 0.34, 0.18, 0.21)
    private val day = LocalDate(2026, 9, 7)
    private val utc = TimeZone.UTC

    private fun at(hour: Int, minute: Int): Double = day.toEpochDays() * 86_400.0 + hour * 3_600 + minute * 60

    private fun event(
        id: String,
        start: Double,
        end: Double? = start + 60,
        label: String = "car",
        camera: String = "hikvision_1",
        subLabel: String? = null,
        subLabelScore: Double? = null,
        box: DetectionBox? = curb,
        path: List<Pair<Double, Double>> = parkedPath,
        zones: List<String> = emptyList(),
    ) = MomentEvent(
        id = id, cameraName = camera, label = label, subLabel = subLabel,
        startEpochSeconds = start, endEpochSeconds = end, topScore = 0.8, hasClip = true, hasSnapshot = false,
        zones = zones, pathPoints = path.map { (x, y) -> MaskPoint(x, y) }, box = box, subLabelScore = subLabelScore,
    )

    /** Six jitter pairs around the parked spot, then the four-point burst of the passer-by that stole the tracker. */
    private val parkedPath: List<Pair<Double, Double>> =
        List(6) { listOf(0.84 to 0.54, 0.85 to 0.40) }.flatten() + listOf(0.60 to 0.30, 0.45 to 0.28, 0.30 to 0.25, 0.15 to 0.22)

    /** Twenty samples spread along the street. */
    private val driveThrough: List<Pair<Double, Double>> = List(20) { 0.10 + it * 0.0447 to 0.30 }

    /** How many of [TrackedCars.andrewsPath]'s points are the drive in; the rest are flickers of the box once parked. */
    private val andrewsArrivalPoints = 25

    /**
     * Front Yard, 2026-10-05 11:26, one event of 3 h 19 min: Andrew's Tesla down to the garage (18
     * points), its box flickering there (17), and back up the street and away (13).
     */
    private val morningVisit: List<Pair<Double, Double>> = listOf(
        0.4945 to 0.1028, 0.5047 to 0.1097, 0.5531 to 0.1444, 0.6164 to 0.1833, 0.6906 to 0.3028, 0.7219 to 0.1944, 0.7922 to 0.1903, 0.7297 to 0.1833,
        0.675 to 0.2028, 0.6188 to 0.2194, 0.5594 to 0.2306, 0.5039 to 0.2653, 0.4492 to 0.2903, 0.3875 to 0.3236, 0.3336 to 0.3486, 0.2687 to 0.3931,
        0.2211 to 0.4375, 0.1766 to 0.4819,
        0.1859 to 0.4014, 0.1719 to 0.4889, 0.1734 to 0.4264, 0.1711 to 0.4875, 0.2195 to 0.3861, 0.1719 to 0.4917, 0.2289 to 0.4319, 0.1375 to 0.4431,
        0.1719 to 0.4931, 0.2164 to 0.4431, 0.1727 to 0.4917, 0.2289 to 0.4403, 0.1719 to 0.4944, 0.2352 to 0.3986, 0.1336 to 0.4597, 0.2297 to 0.4347,
        0.1336 to 0.4556,
        0.1883 to 0.4764, 0.2367 to 0.4319, 0.2945 to 0.375, 0.3578 to 0.3444, 0.4125 to 0.3139, 0.4758 to 0.2806, 0.5359 to 0.25, 0.6008 to 0.2333,
        0.6711 to 0.2333, 0.732 to 0.2458, 0.8016 to 0.2708, 0.8664 to 0.325, 0.9258 to 0.3514,
    )
    private val morningBox = DetectionBox(0.0203, 0.1875, 0.3047, 0.3056)
    private val morningArrivalPoints = 18
    private val morningLeavingPoints = 13

    @Test
    fun parkedCarWithJitterAndAStolenTrackerBurstIsStill() {
        assertTrue(event("parked", at(6, 6)).isStill(), "12 of 16 points sit at the spot: still")
    }

    @Test
    fun carDrivingThroughIsMoved() {
        assertFalse(event("drive", at(9, 43), box = DetectionBox(0.62, 0.24, 0.14, 0.10), path = driveThrough).isStill())
    }

    @Test
    fun anArrivalThatEndsUpCloseToTheCameraIsMovedHoweverLargeItsBestFrameBox() {
        // Front Yard, 2026-10-05: Andrew's Tesla came up the street, turned and came down to the
        // garage, 0.76 of the frame in 32 seconds. Its best frame is the close-up by the garage,
        // a box 0.36 of the frame tall, and 22 of these 25 points are within that of the median.
        val arrival = TrackedCars.event("andrew", TrackedCars.ANDREW_ARRIVED, TrackedCars.andrewsBox, TrackedCars.andrewsPath.take(andrewsArrivalPoints), "andrews_tesla", endedAfter = 32.0)
        assertTrue(maxOf(TrackedCars.andrewsBox.w, TrackedCars.andrewsBox.h) > VehicleVisits.STILL_RADIUS_CAP, "the box alone would allow more than the cap")
        assertFalse(arrival.isStill(), "it crossed three quarters of the frame")
        assertEquals(listOf(arrival), listOf(arrival).mergeVehicleVisits(), "so the arrival is a moment")

        // As it stood at 270 and at 279 minutes, with 2 and then 12 flickers of the box at the garage.
        assertFalse(TrackedCars.andrewParked.isStill())
        assertFalse(TrackedCars.event("andrew", TrackedCars.ANDREW_ARRIVED, TrackedCars.andrewsBox, TrackedCars.andrewsPath, "andrews_tesla").isStill())
    }

    @Test
    fun aCarParkedCloseToTheCameraStaysStill() {
        // The same car at the garage once it has stopped: the last four points of the arrival and
        // the twelve flickers that followed, all within 0.13 of the frame of one another.
        val flickers = TrackedCars.andrewsPath.drop(andrewsArrivalPoints - 4)
        assertTrue(TrackedCars.event("andrew", TrackedCars.ANDREW_ARRIVED, TrackedCars.andrewsBox, flickers, "andrews_tesla").isStill())
    }

    @Test
    fun sittingAfterwardsDoesNotUndoADrive() {
        // Frigate keeps the one event on the car for as long as it sits, and every flicker of its
        // box adds a point at the garage: 12 in the first four and a half hours. Three times that
        // and the points at the garage are 70% of the path, the drive in among them.
        val arrival = TrackedCars.andrewsPath.take(andrewsArrivalPoints)
        val flickers = TrackedCars.andrewsPath.drop(andrewsArrivalPoints)
        for (times in listOf(3, 20)) {
            val path = arrival + List(times) { flickers }.flatten()
            assertFalse(TrackedCars.event("andrew", TrackedCars.ANDREW_ARRIVED, TrackedCars.andrewsBox, path, "andrews_tesla").isStill(), "${flickers.size * times} points at the garage")
        }
    }

    @Test
    fun aCarThatCameSatAndLeftInOneEventMoved() {
        assertFalse(event("visit", at(11, 26), box = morningBox, path = morningVisit).isStill())
        // The same visit had it sat all day: the flickers outnumber the drive in and the drive out together.
        val flickers = morningVisit.subList(morningArrivalPoints, morningVisit.size - morningLeavingPoints)
        val allDay = morningVisit.take(morningArrivalPoints) + List(6) { flickers }.flatten() + morningVisit.takeLast(morningLeavingPoints)
        assertFalse(event("visit", at(11, 26), box = morningBox, path = allDay).isStill())
    }

    @Test
    fun aCarFirstSeenParkedIsStillThoughItsPathEndsInAFewPointsOfTravel() {
        // What a stolen tracker leaves, and what this car's own leaving looks like without the
        // drive in: the path can't tell the two apart, so the points at the spot still decide.
        val flickers = morningVisit.subList(morningArrivalPoints, morningVisit.size - morningLeavingPoints)
        val path = List(3) { flickers }.flatten() + morningVisit.takeLast(morningLeavingPoints)
        assertTrue(event("parked", at(11, 26), box = morningBox, path = path).isStill())
    }

    @Test
    fun aBoxJumpingBetweenTwoPlacesIsNotTravel() {
        // Front Yard, 2026-10-05: a car parked across the street whose box jumps 0.12 sideways,
        // just over its 0.11 width. With five points, three at one place and two at the other,
        // the path isn't gathered; but it has gone nowhere, and with eight it is.
        val here = 0.47 to 0.03
        val there = 0.35 to 0.02
        val jumping = listOf(here, here, there, here, there, here, there, there)
        assertTrue(event("jumping", at(6, 6), box = DetectionBox(0.41, 0.0, 0.11, 0.05), path = jumping).isStill())
    }

    @Test
    fun aSmallBoxIsJudgedByItsOwnSize() {
        // Sarah's car at the curb across the street: a box of 0.12, well under the cap.
        assertTrue(maxOf(TrackedCars.sarahsBox.w, TrackedCars.sarahsBox.h) < VehicleVisits.STILL_RADIUS_CAP)
        val arrival = TrackedCars.sarahsPathParked.take(TrackedCars.SARAHS_ARRIVAL_POINTS)
        assertFalse(TrackedCars.event("sarah", TrackedCars.SARAH_ARRIVED, TrackedCars.sarahsBox, arrival, "sarahs_car").isStill())
        assertFalse(TrackedCars.sarahParked.isStill())
        assertFalse(TrackedCars.event("passing", TrackedCars.SARAH_ARRIVED, TrackedCars.passingBox, TrackedCars.passingPath, "andrews_tesla", endedAfter = 13.0).isStill())
        // A path that strays 0.15 from its median is still under a 0.21 box and moved under a 0.12 one.
        val strays = listOf(0.50 to 0.30, 0.50 to 0.30, 0.50 to 0.30, 0.65 to 0.30, 0.65 to 0.30)
        assertTrue(event("large", at(6, 6), path = strays).isStill())
        assertFalse(event("small", at(6, 6), box = DetectionBox(0.44, 0.18, 0.12, 0.12), path = strays).isStill())
    }

    @Test
    fun fewerThanFourPointsIsStillAndNoBoxIsNeverStill() {
        assertTrue(event("one", at(6, 6), path = listOf(0.84 to 0.54)).isStill())
        assertTrue(event("none", at(6, 6), path = emptyList()).isStill())
        // Front Yard 2026-09-15: a parked car's box flips from part of it to all of it — one jump
        // of a fifth of the frame, no journey.
        assertTrue(event("jump", at(6, 6), box = DetectionBox(0.30, 0.41, 0.08, 0.17), path = listOf(0.34 to 0.58, 0.34 to 0.58, 0.53 to 0.60)).isStill())
        assertFalse(event("four", at(6, 6), box = DetectionBox(0.30, 0.41, 0.08, 0.17), path = listOf(0.10 to 0.58, 0.30 to 0.58, 0.50 to 0.58, 0.70 to 0.58)).isStill())
        assertFalse(event("boxless", at(6, 6), box = null, path = listOf(0.84 to 0.54)).isStill())
    }

    @Test
    fun boxOverlapIsSymmetricFullForTheSameBoxAndZeroWhenApart() {
        assertEquals(1.0, curb.iou(curb), 1e-9)
        assertEquals(0.0, curb.iou(DetectionBox(0.1, 0.1, 0.2, 0.2)), 1e-9)
        val shifted = DetectionBox(0.80, 0.39, 0.18, 0.21)
        assertEquals(curb.iou(shifted), shifted.iou(curb), 1e-9)
        assertEquals(0.38, curb.iou(shifted), 0.01, "a box nudged by 0.05 still overlaps well past the merge threshold")
        assertNull(DetectionBox.fromFractions(listOf(0.1, 0.2, 0.0, 0.5)), "no area, no box")
        assertNull(DetectionBox.fromFractions(listOf(0.1, 0.2)))
    }

    @Test
    fun nineRedetectionsOfTheParkedTeslaBecomeOneCard() {
        // The car pulls in, then is re-detected sitting there. Each ends before the next starts,
        // never more than half an hour apart; the classifier flip-flops between the two Teslas
        // and is surest of Sarah's on the fourth look.
        val starts = listOf(at(6, 6), at(9, 51), at(9, 52), at(10, 34), at(11, 57), at(12, 12), at(12, 31), at(12, 37), at(14, 7))
        val ends = listOf(at(9, 42), at(9, 52), at(10, 30), at(12, 10), at(12, 2), at(12, 21), at(12, 37), at(14, 3), at(14, 24))
        val names = listOf("andrews_tesla" to 0.7, "sarahs_tesla" to 0.85, "andrews_tesla" to 0.6, "sarahs_tesla" to 0.99, null to null, "sarahs_tesla" to 0.9, "andrews_tesla" to 0.8, "sarahs_tesla" to 0.95, "andrews_tesla" to 0.75)
        val events = starts.indices.map { i ->
            event("e$i", starts[i], ends[i], subLabel = names[i].first, subLabelScore = names[i].second, path = if (i == 0) driveThrough else parkedPath)
        }

        val merged = events.reversed().mergeVehicleVisits()

        val only = merged.single()
        assertEquals("e0", only.id, "the first sighting keeps the card, its thumbnail and clip")
        assertEquals(9, only.sightings)
        assertEquals(at(6, 6), only.startEpochSeconds)
        assertEquals(at(14, 24), only.endEpochSeconds)
        assertEquals("sarahs_tesla", only.subLabel, "the surest name wins")
        assertEquals(0.99, only.subLabelScore)
        assertEquals(UiText.plural(Res.plurals.moments_seen_last_seen, 9, 9, "2:24 PM"), only.present(day, utc).sightingsLabel)
        assertEquals(MomentTexts.detected(MomentTexts.named("Sarah's Tesla")), only.present(day, utc).title)
    }

    @Test
    fun anInProgressRedetectionLeavesTheMomentInProgress() {
        val merged = listOf(event("a", at(6, 6), at(6, 20), path = driveThrough), event("b", at(6, 30), end = null)).mergeVehicleVisits()
        val only = merged.single()
        assertTrue(only.isInProgress)
        assertEquals(UiText.plural(Res.plurals.moments_seen_still_there, 2), only.present(day, utc).sightingsLabel)
    }

    @Test
    fun aCarLeavingRightAfterIsPartOfTheSameVisit() {
        val arrived = event("arrived", at(6, 6), at(9, 42), path = driveThrough)
        val leaving = event("leaving", at(9, 45), at(9, 46), path = driveThrough)
        val only = listOf(arrived, leaving).mergeVehicleVisits().single()
        assertEquals("arrived", only.id)
        assertEquals(2, only.sightings)
    }

    @Test
    fun aMovingCarBackAtTheSameSpotLongAfterStartsANewMoment() {
        // Ten minutes is past the visit window, and a moving sighting doesn't get the parked one.
        val morning = event("morning", at(6, 6), at(6, 20), path = driveThrough)
        val evening = event("evening", at(6, 30), at(6, 31), path = driveThrough)
        val merged = listOf(morning, evening).mergeVehicleVisits()
        assertEquals(listOf("evening", "morning"), merged.map { it.id })
        assertTrue(merged.all { it.sightings == 1 })
    }

    @Test
    fun theRealArrivalThatMadeSevenCardsBecomesOne() {
        // Front Yard, 2026-09-07 19:40 to 19:45: Andrew's Tesla pulling in and parking. Frigate lost
        // and re-acquired it seven times; the boxes all overlap and no gap exceeds two minutes. The
        // first three looks were jitter at one spot, so the visit starts with the first that moved.
        val boxes = listOf(
            DetectionBox(0.290, 0.403, 0.147, 0.169),
            DetectionBox(0.239, 0.403, 0.198, 0.175),
            DetectionBox(0.203, 0.406, 0.234, 0.250),
            DetectionBox(0.250, 0.403, 0.188, 0.175),
            DetectionBox(0.325, 0.403, 0.113, 0.164),
            DetectionBox(0.198, 0.406, 0.241, 0.253),
            DetectionBox(0.194, 0.403, 0.245, 0.253),
        )
        val spans = listOf(
            at(19, 40) + 30 to at(19, 40) + 38,
            at(19, 41) + 12 to at(19, 41) + 13,
            at(19, 42) + 5 to at(19, 42) + 7,
            at(19, 42) + 16 to at(19, 42) + 19,
            at(19, 44) + 17 to at(19, 44) + 19,
            at(19, 44) + 44 to at(19, 45) + 0,
            at(19, 45) + 16 to at(19, 45) + 21,
        )
        // Some sightings sit still, others sweep as the car moves; the still ones before any move
        // are not moments, the still ones after it belong to the visit.
        val paths = listOf(parkedPath, parkedPath, parkedPath, driveThrough, driveThrough, parkedPath, parkedPath)
        val events = spans.indices.map { i ->
            event("v$i", spans[i].first, spans[i].second, subLabel = "andrews_tesla", subLabelScore = 0.9, box = boxes[i], path = paths[i])
        }

        val only = events.reversed().mergeVehicleVisits().single()
        assertEquals("v3", only.id, "the first sighting that moved anchors the visit")
        assertEquals(4, only.sightings)
        assertEquals(UiText.plural(Res.plurals.moments_seen_last_seen, 4, 4, "7:45 PM"), only.present(day, utc).sightingsLabel)
    }

    @Test
    fun aParkedCarHoldsItsMomentForHalfAnHour() {
        val merged = listOf(event("a", at(6, 6), at(6, 20), path = driveThrough), event("b", at(6, 51), at(7, 0))).mergeVehicleVisits()
        assertEquals(listOf("a"), merged.map { it.id }, "past half an hour it is not the same visit, and a still car on its own is no moment")
        val chained = listOf(event("a", at(6, 6), at(6, 20), path = driveThrough), event("b", at(6, 50), at(7, 0))).mergeVehicleVisits()
        assertEquals(1, chained.size, "exactly 30 minutes is still the same parked car")
        assertEquals(2, chained.single().sightings)
    }

    @Test
    fun aMovingSightingOnlyGetsTheShorterVisitWindow() {
        val anchor = event("anchor", at(6, 6), at(6, 20), path = driveThrough)
        val soon = event("soon", at(6, 24), at(6, 25), path = driveThrough)
        assertEquals(1, listOf(anchor, soon).mergeVehicleVisits().size, "four minutes later is the same visit")
        val late = event("late", at(6, 26), at(6, 27), path = driveThrough)
        assertEquals(2, listOf(anchor, late).mergeVehicleVisits().size, "six minutes later is not")
    }

    @Test
    fun aDifferentCameraOrADifferentSpotNeverMerges() {
        val here = event("here", at(6, 6), at(6, 20), path = driveThrough)
        val otherCamera = event("other-camera", at(6, 25), at(6, 30), camera = "amcrest_1", path = driveThrough)
        val otherSpot = event("other-spot", at(6, 26), at(6, 30), box = DetectionBox(0.25, 0.40, 0.19, 0.16), path = driveThrough)
        val merged = listOf(here, otherCamera, otherSpot).mergeVehicleVisits()
        assertEquals(setOf("here", "other-camera", "other-spot"), merged.map { it.id }.toSet())
    }

    @Test
    fun nonVehiclesPassThroughUntouched() {
        val car = event("car", at(6, 6), at(6, 20), path = driveThrough)
        val person = event("person", at(6, 10), at(6, 12), label = "person", path = listOf(0.84 to 0.54, 0.84 to 0.55))
        val secondPerson = event("person2", at(6, 15), at(6, 16), label = "person", path = listOf(0.84 to 0.54, 0.84 to 0.55))
        val merged = listOf(car, person, secondPerson).mergeVehicleVisits()
        assertEquals(listOf("person2", "person", "car"), merged.map { it.id })
        assertTrue(merged.all { it.sightings == 1 })
    }

    @Test
    fun foldingKeepsTheAnchorsClipAndAddsTheZonesItGained() {
        val anchor = event("anchor", at(6, 6), at(6, 20), zones = listOf("street"), path = driveThrough)
        val later = event("later", at(6, 30), at(6, 40), zones = listOf("sidewalk", "street"), subLabel = "sarahs_tesla", subLabelScore = 0.9)
        val only = listOf(anchor, later).mergeVehicleVisits().single()
        assertEquals("anchor", only.id)
        assertEquals(anchor.pathPoints, only.pathPoints, "the anchor's own path, not a concatenation")
        assertEquals(listOf("sidewalk", "street"), only.zones, "the newest sighting's zones come last")
        assertEquals("sarahs_tesla", only.subLabel, "a named sighting beats an unnamed anchor")
    }

    @Test
    fun theCardIsNamedForWhereTheCarEndedUpNotWhereItPassed() {
        // The real complaint: a car that crossed the street on its way in was titled "on the
        // street" while it sat in the driveway, because the anchor's zones were kept last.
        val arriving = event("arriving", at(19, 40), at(19, 41), zones = listOf("driveway", "street"), subLabel = "andrews_tesla", subLabelScore = 0.9, path = driveThrough)
        val parked = event("parked", at(19, 42), at(19, 45), zones = listOf("driveway"), subLabel = "andrews_tesla", subLabelScore = 0.9)
        val only = listOf(arriving, parked).mergeVehicleVisits().single()
        assertEquals(listOf("street", "driveway"), only.zones)
        assertEquals(MomentTexts.inThe(MomentTexts.named("Andrew's Tesla"), "driveway"), only.present(day, utc).title)
    }

    @Test
    fun aSightingThatLandedInNoZoneLeavesTheNameAlone() {
        val parked = event("parked", at(19, 40), at(19, 41), zones = listOf("driveway"), path = driveThrough)
        val unplaced = event("unplaced", at(19, 42), at(19, 43))
        val only = listOf(parked, unplaced).mergeVehicleVisits().single()
        assertEquals(listOf("driveway"), only.zones, "nothing to add, nothing to reorder")
        assertEquals(MomentTexts.inThe(MomentTexts.car, "driveway"), only.present(day, utc).title)
    }

    @Test
    fun aVehicleOnlyEverSeenSittingStillIsNoMoment() {
        // The Front Yard's parked Tesla, 2026-09-15: re-detected every few minutes all evening, a
        // two-point path and no travel each time. None of it is a moment...
        val flicker = listOf(0.3406 to 0.5833, 0.3406 to 0.5806)
        val sittings = listOf(event("one", at(17, 23), at(17, 23) + 10, path = flicker), event("two", at(17, 26), at(17, 26) + 13, path = flicker), event("three", at(17, 30), at(17, 32), path = flicker))
        assertTrue(sittings.mergeVehicleVisits().isEmpty(), "a car that never moved did nothing worth a card")

        // ...unless the car was seen arriving, in which case they are more sightings of that visit.
        val arrived = event("arrived", at(17, 10), at(17, 11), path = driveThrough)
        val visit = (listOf(arrived) + sittings).mergeVehicleVisits().single()
        assertEquals("arrived", visit.id)
        assertEquals(4, visit.sightings)

        // A sighting with no box can't be judged still, so it stays; so does anything that isn't a vehicle.
        val boxless = event("boxless", at(18, 0), at(18, 1), box = null, path = flicker)
        val person = event("person", at(18, 2), at(18, 3), label = "person", path = flicker)
        assertEquals(listOf("person", "boxless"), listOf(boxless, person).mergeVehicleVisits().map { it.id })
    }

    @Test
    fun outputIsNewestFirstLikeTheApi() {
        val feed = listOf(event("c", at(8, 0), at(8, 1), path = driveThrough), event("b", at(7, 0), at(7, 1), path = driveThrough), event("a", at(6, 0), at(6, 1), path = driveThrough))
        assertEquals(listOf("c", "b", "a"), feed.mergeVehicleVisits().map { it.id })
        assertEquals(listOf("c", "b", "a"), feed.reversed().mergeVehicleVisits().map { it.id })
    }
}
