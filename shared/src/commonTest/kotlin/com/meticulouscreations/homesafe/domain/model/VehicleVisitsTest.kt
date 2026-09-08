package com.meticulouscreations.homesafe.domain.model

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

    @Test
    fun parkedCarWithJitterAndAStolenTrackerBurstIsStill() {
        assertTrue(event("parked", at(6, 6)).isStill(), "12 of 16 points sit at the spot: still")
    }

    @Test
    fun carDrivingThroughIsMoved() {
        assertFalse(event("drive", at(9, 43), box = DetectionBox(0.62, 0.24, 0.14, 0.10), path = driveThrough).isStill())
    }

    @Test
    fun fewerThanTwoPointsIsStillAndNoBoxIsNeverStill() {
        assertTrue(event("one", at(6, 6), path = listOf(0.84 to 0.54)).isStill())
        assertTrue(event("none", at(6, 6), path = emptyList()).isStill())
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
        // Each ends before the next starts, never more than half an hour apart; the classifier
        // flip-flops between the two Teslas and is surest of Sarah's on the fourth look.
        val starts = listOf(at(6, 6), at(9, 51), at(9, 52), at(10, 34), at(11, 57), at(12, 12), at(12, 31), at(12, 37), at(14, 7))
        val ends = listOf(at(9, 42), at(9, 52), at(10, 30), at(12, 10), at(12, 2), at(12, 21), at(12, 37), at(14, 3), at(14, 24))
        val names = listOf("andrews_tesla" to 0.7, "sarahs_tesla" to 0.85, "andrews_tesla" to 0.6, "sarahs_tesla" to 0.99, null to null, "sarahs_tesla" to 0.9, "andrews_tesla" to 0.8, "sarahs_tesla" to 0.95, "andrews_tesla" to 0.75)
        val events = starts.indices.map { i -> event("e$i", starts[i], ends[i], subLabel = names[i].first, subLabelScore = names[i].second) }

        val merged = events.reversed().mergeVehicleVisits()

        val only = merged.single()
        assertEquals("e0", only.id, "the first sighting keeps the card, its thumbnail and clip")
        assertEquals(9, only.sightings)
        assertEquals(at(6, 6), only.startEpochSeconds)
        assertEquals(at(14, 24), only.endEpochSeconds)
        assertEquals("sarahs_tesla", only.subLabel, "the surest name wins")
        assertEquals(0.99, only.subLabelScore)
        assertEquals("Seen 9 times · last seen 2:24 PM", only.present(day, utc).sightingsLabel)
        assertEquals("Sarah's Tesla detected", only.present(day, utc).title)
    }

    @Test
    fun anInProgressRedetectionLeavesTheMomentInProgress() {
        val merged = listOf(event("a", at(6, 6), at(6, 20)), event("b", at(6, 30), end = null)).mergeVehicleVisits()
        val only = merged.single()
        assertTrue(only.isInProgress)
        assertEquals("Seen 2 times · still there", only.present(day, utc).sightingsLabel)
    }

    @Test
    fun aCarLeavingRightAfterIsPartOfTheSameVisit() {
        val parked = event("parked", at(6, 6), at(9, 42))
        val leaving = event("leaving", at(9, 45), at(9, 46), path = driveThrough)
        val only = listOf(parked, leaving).mergeVehicleVisits().single()
        assertEquals("parked", only.id)
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
        // and re-acquired it seven times; the boxes all overlap and no gap exceeds two minutes.
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
        // Some sightings sit still, others sweep as the car moves; both belong to the one visit.
        val paths = listOf(parkedPath, parkedPath, parkedPath, driveThrough, driveThrough, parkedPath, parkedPath)
        val events = spans.indices.map { i ->
            event("v$i", spans[i].first, spans[i].second, subLabel = "andrews_tesla", subLabelScore = 0.9, box = boxes[i], path = paths[i])
        }

        val only = events.reversed().mergeVehicleVisits().single()
        assertEquals("v0", only.id)
        assertEquals(7, only.sightings)
        assertEquals("Seen 7 times · last seen 7:45 PM", only.present(day, utc).sightingsLabel)
    }

    @Test
    fun aParkedCarHoldsItsMomentForHalfAnHour() {
        val merged = listOf(event("a", at(6, 6), at(6, 20)), event("b", at(6, 51), at(7, 0))).mergeVehicleVisits()
        assertEquals(listOf("b", "a"), merged.map { it.id }, "past half an hour it is a new moment")
        val chained = listOf(event("a", at(6, 6), at(6, 20)), event("b", at(6, 50), at(7, 0))).mergeVehicleVisits()
        assertEquals(1, chained.size, "exactly 30 minutes is still the same parked car")
    }

    @Test
    fun aMovingSightingOnlyGetsTheShorterVisitWindow() {
        val anchor = event("anchor", at(6, 6), at(6, 20))
        val soon = event("soon", at(6, 24), at(6, 25), path = driveThrough)
        assertEquals(1, listOf(anchor, soon).mergeVehicleVisits().size, "four minutes later is the same visit")
        val late = event("late", at(6, 26), at(6, 27), path = driveThrough)
        assertEquals(2, listOf(anchor, late).mergeVehicleVisits().size, "six minutes later is not")
    }

    @Test
    fun aDifferentCameraOrADifferentSpotNeverMerges() {
        val here = event("here", at(6, 6), at(6, 20))
        val otherCamera = event("other-camera", at(6, 25), at(6, 30), camera = "amcrest_1")
        val otherSpot = event("other-spot", at(6, 26), at(6, 30), box = DetectionBox(0.25, 0.40, 0.19, 0.16))
        val merged = listOf(here, otherCamera, otherSpot).mergeVehicleVisits()
        assertEquals(setOf("here", "other-camera", "other-spot"), merged.map { it.id }.toSet())
    }

    @Test
    fun nonVehiclesPassThroughUntouched() {
        val car = event("car", at(6, 6), at(6, 20))
        val person = event("person", at(6, 10), at(6, 12), label = "person", path = listOf(0.84 to 0.54, 0.84 to 0.55))
        val secondPerson = event("person2", at(6, 15), at(6, 16), label = "person", path = listOf(0.84 to 0.54, 0.84 to 0.55))
        val merged = listOf(car, person, secondPerson).mergeVehicleVisits()
        assertEquals(listOf("person2", "person", "car"), merged.map { it.id })
        assertTrue(merged.all { it.sightings == 1 })
    }

    @Test
    fun foldingKeepsTheAnchorsClipAndAddsTheZonesItGained() {
        val anchor = event("anchor", at(6, 6), at(6, 20), zones = listOf("street"))
        val later = event("later", at(6, 30), at(6, 40), zones = listOf("sidewalk", "street"), subLabel = "sarahs_tesla", subLabelScore = 0.9)
        val only = listOf(anchor, later).mergeVehicleVisits().single()
        assertEquals("anchor", only.id)
        assertEquals(anchor.pathPoints, only.pathPoints, "the anchor's own path, not a concatenation")
        assertEquals(listOf("street", "sidewalk"), only.zones)
        assertEquals("sarahs_tesla", only.subLabel, "a named sighting beats an unnamed anchor")
    }

    @Test
    fun outputIsNewestFirstLikeTheApi() {
        val feed = listOf(event("c", at(8, 0), at(8, 1), path = driveThrough), event("b", at(7, 0), at(7, 1), path = driveThrough), event("a", at(6, 0), at(6, 1), path = driveThrough))
        assertEquals(listOf("c", "b", "a"), feed.mergeVehicleVisits().map { it.id })
        assertEquals(listOf("c", "b", "a"), feed.reversed().mergeVehicleVisits().map { it.id })
    }
}
