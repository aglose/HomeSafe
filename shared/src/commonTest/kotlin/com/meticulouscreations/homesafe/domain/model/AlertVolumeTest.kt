package com.meticulouscreations.homesafe.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class AlertVolumeTest {

    private fun poly(vararg xy: Double) = MaskPolygon(xy.toList().chunked(2) { (x, y) -> MaskPoint(x, y) })

    // Two halves of the Front Yard frame: a street that only wants birds, and a driveway that wants anything.
    private val street = DetectionZone("street", "Street", poly(0.0, 0.0, 1.0, 0.0, 1.0, 0.5, 0.0, 0.5), listOf("bird"))
    private val driveway = DetectionZone("driveway", "Driveway", poly(0.0, 0.5, 1.0, 0.5, 1.0, 1.0, 0.0, 1.0), emptyList())
    private val zones = mapOf("hikvision_1" to listOf(street, driveway))

    private val now = 2_000_000.0
    private val weekAgo = now - 7 * 86_400.0
    private val box = DetectionBox(0.4, 0.6, 0.1, 0.1)

    private fun event(
        id: String,
        label: String,
        camera: String = "hikvision_1",
        start: Double = now - 3_600.0,
        zones: List<String> = emptyList(),
        path: List<Pair<Double, Double>> = emptyList(),
        box: DetectionBox? = null,
    ) = MomentEvent(
        id = id, cameraName = camera, label = label, subLabel = null,
        startEpochSeconds = start, endEpochSeconds = start + 30, topScore = 0.8, hasClip = true, hasSnapshot = false,
        zones = zones, pathPoints = path.map { (x, y) -> MaskPoint(x, y) }, box = box,
    )

    /** A car crossing the driveway half: plenty of travel, so it isn't parked. */
    private val drivingThrough = List(8) { 0.05 + it * 0.12 to 0.75 }

    @Test
    fun countsEachPlaceAndCategoryOverTheWeek() {
        val events = List(14) { event("p$it", "person", zones = listOf("driveway")) } +
            List(7) { event("d$it", "dog", camera = "hikvision_2") } +
            event("c", "car", path = drivingThrough, box = box)
        val volume = AlertVolume.estimate(events, zones, weekAgo, now, sampleLimit = 500)
        assertEquals(7.0, volume.days)
        assertEquals(2.0, volume.perDay(AlertZone("hikvision_1", "driveway"), MomentCategory.PEOPLE))
        assertEquals(1.0, volume.perDay(AlertZone("hikvision_2", null), MomentCategory.ANIMALS), "no zones drawn: the camera's anywhere")
        assertEquals(1.0 / 7, volume.perDay(AlertZone("hikvision_1", "driveway"), MomentCategory.VEHICLES), "placed from its path")
        assertEquals(0.0, volume.perDay(AlertZone("hikvision_1", "street"), MomentCategory.PEOPLE))
    }

    @Test
    fun dropsWhatThePollerWouldDrop() {
        val events = listOf(
            // Parked: a box and a jittery handful of points, never going anywhere.
            event("parked", "car", zones = listOf("driveway"), path = listOf(0.45 to 0.65, 0.46 to 0.66, 0.45 to 0.65, 0.46 to 0.66), box = box),
            // A car on the birds-only street: no zone wants it, so on a camera with zones it isn't "anywhere else" either.
            event("street", "car", path = List(8) { 0.05 + it * 0.12 to 0.25 }, box = box),
            // A label no category covers.
            event("thing", "umbrella", camera = "hikvision_2"),
        )
        val volume = AlertVolume.estimate(events, zones, weekAgo, now, sampleLimit = 500)
        assertEquals(emptyMap(), volume.perDay)
    }

    @Test
    fun aDetectionCountsOnceForEveryPlaceItTouched() {
        val zonesWithCarsOnTheStreet = mapOf("hikvision_1" to listOf(street.copy(objects = emptyList()), driveway))
        val turningIn = event("c", "car", path = listOf(0.1 to 0.2, 0.3 to 0.3, 0.5 to 0.6, 0.7 to 0.8), box = box)
        val volume = AlertVolume.estimate(listOf(turningIn), zonesWithCarsOnTheStreet, weekAgo, now, sampleLimit = 500)
        assertEquals(1.0 / 7, volume.perDay(AlertZone("hikvision_1", "street"), MomentCategory.VEHICLES))
        assertEquals(1.0 / 7, volume.perDay(AlertZone("hikvision_1", "driveway"), MomentCategory.VEHICLES))
        assertEquals(0.0, volume.perDay(AlertZone("hikvision_1", null), MomentCategory.VEHICLES))
    }

    @Test
    fun aFullSampleIsSpreadOverTheSpanItCoversNotTheWholeWeek() {
        // 48 people over the last two days fills a sample of 48: that's 24 a day, not 48 over seven.
        val events = List(48) { event("p$it", "person", camera = "hikvision_2", start = now - (it + 1) * 3_600.0) }
        val volume = AlertVolume.estimate(events, zones, weekAgo, now, sampleLimit = 48)
        assertEquals(2.0, volume.days)
        assertEquals(24.0, volume.perDay(AlertZone("hikvision_2", null), MomentCategory.PEOPLE))
    }

    @Test
    fun aFullSampleFromOneBusyMinuteIsTakenAsAtLeastAnHour() {
        val events = List(10) { event("p$it", "person", camera = "hikvision_2", start = now - 60.0) }
        val volume = AlertVolume.estimate(events, zones, weekAgo, now, sampleLimit = 10)
        assertEquals(240.0, volume.perDay(AlertZone("hikvision_2", null), MomentCategory.PEOPLE), "10 an hour, not 10 a minute")
    }
}
