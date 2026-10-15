package com.meticulouscreations.homesafe.fitness.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The zones, the measurement a sensor sends, the zone the heart has settled in, and a workout's time in zone. All numbers made up. */
class HeartRateTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    // ---- Zones --------------------------------------------------------------------------------

    @Test
    fun zonesAreTenPercentBandsFromHalfOfTheMaximum() {
        val bounds = assertNotNull(HeartZones.bounds(HeartProfile(maxBpm = 190)))
        assertEquals(190, bounds.maxBpm)
        assertEquals(listOf(95, 114, 133, 152, 171), bounds.floors)
        assertFalse(bounds.estimated)
        assertNull(bounds.restingBpm)
    }

    @Test
    fun withoutAMaximumItIsEstimatedFromAge() {
        // 208 - 0.7 x 40
        val bounds = assertNotNull(HeartZones.bounds(HeartProfile(age = 40)))
        assertEquals(180, bounds.maxBpm)
        assertTrue(bounds.estimated)
        assertEquals(listOf(90, 108, 126, 144, 162), bounds.floors)
    }

    @Test
    fun theEstimateFallsSevenBeatsADecade() {
        assertEquals(194, HeartZones.estimatedMax(20))
        assertEquals(187, HeartZones.estimatedMax(30))
        assertEquals(173, HeartZones.estimatedMax(50))
        assertEquals(166, HeartZones.estimatedMax(60))
    }

    @Test
    fun aMaximumThatWasEnteredWinsOverAnAge() {
        val bounds = assertNotNull(HeartZones.bounds(HeartProfile(maxBpm = 200, age = 40)))
        assertEquals(200, bounds.maxBpm)
        assertFalse(bounds.estimated)
    }

    @Test
    fun withNeitherAMaximumNorAnAgeThereAreNoZones() {
        assertNull(HeartZones.bounds(HeartProfile()))
        assertNull(HeartZones.bounds(HeartProfile(restingBpm = 60)))
    }

    @Test
    fun aMaximumNoHeartHasIsNotBelieved() {
        assertNull(HeartZones.bounds(HeartProfile(maxBpm = 90)))
        assertNull(HeartZones.bounds(HeartProfile(maxBpm = 260)))
        assertNull(HeartZones.bounds(HeartProfile(age = 3)))
        // But the age beside it still is.
        assertEquals(180, HeartZones.bounds(HeartProfile(maxBpm = 90, age = 40))?.maxBpm)
    }

    @Test
    fun withARestingRateTheZonesAreDrawnOnTheReserve() {
        // The reserve is 190 - 60 = 130; half of it on top of 60 is 125.
        val bounds = assertNotNull(HeartZones.bounds(HeartProfile(maxBpm = 190, restingBpm = 60)))
        assertEquals(60, bounds.restingBpm)
        assertEquals(listOf(125, 138, 151, 164, 177), bounds.floors)
    }

    @Test
    fun aRestingRateThatLeavesNoReserveIsIgnored() {
        val plain = HeartZones.bounds(HeartProfile(maxBpm = 190))
        assertEquals(plain, HeartZones.bounds(HeartProfile(maxBpm = 190, restingBpm = 170)))
        assertEquals(plain, HeartZones.bounds(HeartProfile(maxBpm = 190, restingBpm = 12)))
    }

    @Test
    fun aReadingFallsInTheZoneWhoseFloorItHasReached() {
        val bounds = assertNotNull(HeartZones.bounds(HeartProfile(maxBpm = 190)))
        assertNull(bounds.zoneOf(60))
        assertNull(bounds.zoneOf(94))
        assertEquals(HeartZone.VERY_LIGHT, bounds.zoneOf(95))
        assertEquals(HeartZone.VERY_LIGHT, bounds.zoneOf(113))
        assertEquals(HeartZone.LIGHT, bounds.zoneOf(114))
        assertEquals(HeartZone.MODERATE, bounds.zoneOf(140))
        assertEquals(HeartZone.HARD, bounds.zoneOf(170))
        assertEquals(HeartZone.MAXIMUM, bounds.zoneOf(171))
    }

    @Test
    fun overTheMaximumIsStillTheTopZone() {
        val bounds = assertNotNull(HeartZones.bounds(HeartProfile(maxBpm = 190)))
        assertEquals(HeartZone.MAXIMUM, bounds.zoneOf(204))
    }

    @Test
    fun aZonesRangeRunsToTheBeatUnderTheNextAndTheLastToTheMaximum() {
        val bounds = assertNotNull(HeartZones.bounds(HeartProfile(maxBpm = 190)))
        assertEquals(95..113, bounds.range(HeartZone.VERY_LIGHT))
        assertEquals(133..151, bounds.range(HeartZone.MODERATE))
        assertEquals(171..190, bounds.range(HeartZone.MAXIMUM))
    }

    @Test
    fun theZonesAreNumberedOneToFive() {
        assertEquals(listOf(1, 2, 3, 4, 5), HeartZone.entries.map { it.number })
        assertEquals(HeartZone.entries.size, HeartZones.FLOORS.size)
    }

    // ---- The measurement ----------------------------------------------------------------------

    @Test
    fun anEightBitHeartRateIsTheSecondByte() {
        assertEquals(HeartRateMeasurement(72, contact = null), HeartRateMeasurement.parse(bytes(0x00, 72)))
    }

    @Test
    fun aByteOver127IsNotNegative() {
        assertEquals(200, HeartRateMeasurement.parse(bytes(0x00, 200))?.bpm)
    }

    @Test
    fun bitZeroOfTheFlagsMakesItSixteenBitsLittleEndian() {
        // 0x012C = 300: nothing a heart does, but it is what the bytes say.
        assertEquals(300, HeartRateMeasurement.parse(bytes(0x01, 0x2C, 0x01))?.bpm)
        assertEquals(96, HeartRateMeasurement.parse(bytes(0x01, 96, 0x00))?.bpm)
    }

    @Test
    fun skinContactIsReadOnlyWhenTheSensorReportsIt() {
        assertNull(HeartRateMeasurement.parse(bytes(0x00, 80))?.contact)
        // Bit 1 alone means nothing without bit 2 saying contact is reported at all.
        assertNull(HeartRateMeasurement.parse(bytes(0x02, 80))?.contact)
        assertEquals(false, HeartRateMeasurement.parse(bytes(0x04, 80))?.contact)
        assertEquals(true, HeartRateMeasurement.parse(bytes(0x06, 80))?.contact)
    }

    @Test
    fun whatFollowsTheHeartRateIsLeftAlone() {
        // Energy expended (bit 3) and two RR intervals (bit 4) after an eight-bit rate, contact detected.
        val measurement = HeartRateMeasurement.parse(bytes(0x1E, 61, 0x10, 0x00, 0xE8, 0x03, 0xF0, 0x03))
        assertEquals(HeartRateMeasurement(61, contact = true), measurement)
    }

    @Test
    fun tooFewBytesIsNoMeasurement() {
        assertNull(HeartRateMeasurement.parse(bytes()))
        assertNull(HeartRateMeasurement.parse(bytes(0x00)))
        assertNull(HeartRateMeasurement.parse(bytes(0x01, 80)))
    }

    @Test
    fun aReadingIsOnlyBelievedOnSkinAndInAHeartsRange() {
        assertEquals(80, HeartRateMeasurement(80).reading)
        assertEquals(80, HeartRateMeasurement(80, contact = true).reading)
        assertNull(HeartRateMeasurement(80, contact = false).reading)
        assertNull(HeartRateMeasurement(0).reading)
        assertNull(HeartRateMeasurement(12).reading)
        assertNull(HeartRateMeasurement(300).reading)
    }

    // ---- Settling into a zone -----------------------------------------------------------------

    @Test
    fun theFirstReadingIsTakenAsItComes() {
        val tracker = ZoneTracker().next(HeartZone.MODERATE, 1_000)
        assertTrue(tracker.settled)
        assertEquals(HeartZone.MODERATE, tracker.zone)
    }

    @Test
    fun underZoneOneIsSomewhereToBeToo() {
        val tracker = ZoneTracker().next(null, 1_000)
        assertTrue(tracker.settled)
        assertNull(tracker.zone)
    }

    @Test
    fun aNewZoneCountsOnlyOnceTheReadingsHaveStayedInIt() {
        var tracker = ZoneTracker().next(HeartZone.LIGHT, 0)
        tracker = tracker.next(HeartZone.MODERATE, 1_000)
        assertEquals(HeartZone.LIGHT, tracker.zone)
        tracker = tracker.next(HeartZone.MODERATE, 3_000)
        assertEquals(HeartZone.LIGHT, tracker.zone)
        tracker = tracker.next(HeartZone.MODERATE, 1_000 + ZoneTracker.HOLD_MILLIS)
        assertEquals(HeartZone.MODERATE, tracker.zone)
    }

    @Test
    fun aReadingOnTheLineDoesNotFlipTheZoneBackAndForth() {
        var tracker = ZoneTracker().next(HeartZone.LIGHT, 0)
        var at = 0L
        repeat(20) { beat ->
            at += 1_000
            tracker = tracker.next(if (beat % 2 == 0) HeartZone.MODERATE else HeartZone.LIGHT, at)
            assertEquals(HeartZone.LIGHT, tracker.zone)
        }
    }

    @Test
    fun passingThroughAZoneOnTheWayToAnotherStartsTheWaitAgain() {
        var tracker = ZoneTracker().next(HeartZone.LIGHT, 0)
        tracker = tracker.next(HeartZone.MODERATE, 1_000)
        tracker = tracker.next(HeartZone.HARD, 4_000)
        // Moderate was never stayed in, and hard has only just begun.
        tracker = tracker.next(HeartZone.HARD, 5_500)
        assertEquals(HeartZone.LIGHT, tracker.zone)
        tracker = tracker.next(HeartZone.HARD, 8_000)
        assertEquals(HeartZone.HARD, tracker.zone)
    }

    @Test
    fun fallingUnderZoneOneIsAChangeLikeAnyOther() {
        var tracker = ZoneTracker().next(HeartZone.VERY_LIGHT, 0)
        tracker = tracker.next(null, 1_000)
        assertEquals(HeartZone.VERY_LIGHT, tracker.zone)
        tracker = tracker.next(null, 5_000)
        assertNull(tracker.zone)
        assertTrue(tracker.settled)
    }

    // ---- Time in zone -------------------------------------------------------------------------

    @Test
    fun aReadingStandsForTheTimeSinceTheOneBefore() {
        val summary = HeartSummary()
            .plus(100, HeartZone.VERY_LIGHT, 1_000)
            .plus(120, HeartZone.LIGHT, 1_000)
            .plus(124, HeartZone.LIGHT, 2_000)
            .plus(80, null, 1_000)
        assertEquals(1_000, summary.millisIn(HeartZone.VERY_LIGHT))
        assertEquals(3_000, summary.millisIn(HeartZone.LIGHT))
        assertEquals(0, summary.millisIn(HeartZone.HARD))
        assertEquals(1_000, summary.belowMillis)
        assertEquals(5_000, summary.totalMillis)
        assertEquals(124, summary.peakBpm)
    }

    @Test
    fun theAverageIsWeightedByHowLongEachReadingStood() {
        // 100 for a second and 160 for three: (100 + 480) / 4.
        val summary = HeartSummary().plus(100, HeartZone.VERY_LIGHT, 1_000).plus(160, HeartZone.HARD, 3_000)
        assertEquals(145, summary.averageBpm)
    }

    @Test
    fun aGapTheSensorWasAwayForAddsNoTime() {
        val before = HeartSummary().plus(120, HeartZone.LIGHT, 1_000)
        val after = before.plus(130, HeartZone.LIGHT, HeartSummary.MAX_GAP_MILLIS + 1)
        assertEquals(1_000, after.totalMillis)
        assertEquals(120, after.averageBpm)
        // The reading itself was real, though.
        assertEquals(130, after.peakBpm)
    }

    @Test
    fun theFirstReadingOfAllHasNoTimeBehindIt() {
        val summary = HeartSummary().plus(140, HeartZone.MODERATE, 0)
        assertTrue(summary.isEmpty)
        assertNull(summary.averageBpm)
        assertEquals(140, summary.peakBpm)
    }

    @Test
    fun recoveryIsHowFarTheHeartHasComeDownAndNeverLessThanNothing() {
        assertEquals(27, HeartRecovery(peakBpm = 148, bpm = 121).drop)
        assertEquals(0, HeartRecovery(peakBpm = 148, bpm = 148).drop)
    }
}
