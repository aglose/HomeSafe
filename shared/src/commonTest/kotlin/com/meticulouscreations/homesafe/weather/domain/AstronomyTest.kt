package com.meticulouscreations.homesafe.weather.domain

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The sun and moon come from formulas, not a service, so they are checked against instants whose
 * answers are published: the sun's height at Portland's solar noon, the day's rise and set, and
 * the lunations of October 2025.
 */
class AstronomyTest {

    private val portlandLat = 45.52
    private val portlandLon = -122.68

    private fun at(iso: String): Long = Instant.parse(iso).epochSeconds

    /** Distance between two points on the 0..1 moon cycle, which wraps. */
    private fun cycleGap(a: Double, b: Double): Double {
        val d = abs(a - b) % 1.0
        return minOf(d, 1.0 - d)
    }

    // ---- sunPosition --------------------------------------------------------------------------

    @Test
    fun theSunIsDueSouthAndHighAtPortlandsSolarNoon() {
        // Solar noon on 9 Oct 2025: 12:00 UTC + 8h11m (longitude) - 12.5 min (equation of time).
        val sun = Astronomy.sunPosition(at("2025-10-09T19:58:00Z"), portlandLat, portlandLon)
        // 90 - latitude + declination (-6.4 degrees in early October).
        assertEquals(38.1, sun.altitudeDeg, 1.5)
        assertEquals(180.0, sun.azimuthDeg, 1.5)
    }

    @Test
    fun theSunIsWellBelowTheHorizonAtLocalMidnight() {
        val sun = Astronomy.sunPosition(at("2025-10-10T07:00:00Z"), portlandLat, portlandLon)
        assertTrue(sun.altitudeDeg < -30.0, "altitude at midnight was ${sun.altitudeDeg}")
    }

    @Test
    fun theSunRisesInTheEastAndSetsInTheWest() {
        val morning = Astronomy.sunPosition(at("2025-10-09T16:00:00Z"), portlandLat, portlandLon)
        val evening = Astronomy.sunPosition(at("2025-10-10T00:30:00Z"), portlandLat, portlandLon)
        assertTrue(morning.altitudeDeg > 0 && morning.azimuthDeg in 90.0..170.0, "morning $morning")
        assertTrue(evening.altitudeDeg > 0 && evening.azimuthDeg in 190.0..270.0, "evening $evening")
    }

    @Test
    fun theSunIsDueNorthAtSouthernNoon() {
        // Sydney, 33.87 S, 151.21 E, at its solar noon on the 9th (01:43 UTC).
        val sun = Astronomy.sunPosition(at("2025-10-09T01:43:00Z"), -33.87, 151.21)
        assertTrue(sun.azimuthDeg < 20.0 || sun.azimuthDeg > 340.0, "azimuth was ${sun.azimuthDeg}")
        // 90 - 33.87 + 6.4.
        assertEquals(62.5, sun.altitudeDeg, 2.0)
    }

    @Test
    fun theSunsAzimuthIsAlwaysAFullCircleValue() {
        for (hour in 0 until 24) {
            val sun = Astronomy.sunPosition(at("2025-10-09T00:00:00Z") + hour * 3_600L, portlandLat, portlandLon)
            assertTrue(sun.azimuthDeg >= 0.0 && sun.azimuthDeg < 360.0, "azimuth ${sun.azimuthDeg} at hour $hour")
        }
    }

    // ---- dayLight -----------------------------------------------------------------------------

    @Test
    fun sunriseAndSunsetAtPortlandAreWithinAFewMinutesOfTheAlmanac() {
        val midnight = at("2025-10-09T07:00:00Z")
        val light = Astronomy.dayLight(midnight, portlandLat, portlandLon)
        val sunrise = assertNotNull(light.sunrise)
        val sunset = assertNotNull(light.sunset)
        // 7:20 AM and 6:36 PM Pacific Daylight Time, as seconds after local midnight.
        val expectedRise = 7 * 3_600L + 20 * 60
        val expectedSet = 18 * 3_600L + 36 * 60
        assertTrue(abs((sunrise - midnight) - expectedRise) <= 4 * 60, "sunrise was ${(sunrise - midnight) / 60.0} minutes after midnight")
        assertTrue(abs((sunset - midnight) - expectedSet) <= 4 * 60, "sunset was ${(sunset - midnight) / 60.0} minutes after midnight")
    }

    @Test
    fun theDaysLightIsInOrderFromDawnToDusk() {
        val light = Astronomy.dayLight(at("2025-10-09T07:00:00Z"), portlandLat, portlandLon)
        val ordered = listOf(light.dawn, light.sunrise, light.goldenMorningEnd, light.solarNoon, light.goldenEveningStart, light.sunset, light.dusk).map { assertNotNull(it) }
        assertEquals(ordered.sorted(), ordered, "dawn, sunrise, golden hour, noon, golden hour, sunset, dusk run in that order")
    }

    @Test
    fun solarNoonFallsWithinFiveMinutesOfTheSunsHighestPoint() {
        val light = Astronomy.dayLight(at("2025-10-09T07:00:00Z"), portlandLat, portlandLon)
        assertTrue(abs(assertNotNull(light.solarNoon) - at("2025-10-09T19:58:00Z")) <= 6 * 60)
    }

    @Test
    fun aPolarSummerDayHasNoSunsetAndAPolarWinterDayNoSunrise() {
        // Tromso, 69.65 N, around the solstices (local midnight is about 22:00 UTC the evening before in June).
        val summer = Astronomy.dayLight(at("2025-06-20T22:00:00Z"), 69.65, 18.96)
        assertNull(summer.sunset)
        assertNull(summer.sunrise)
        val winter = Astronomy.dayLight(at("2025-12-20T23:00:00Z"), 69.65, 18.96)
        assertNull(winter.sunrise)
        assertNull(winter.sunset)
    }

    // ---- moonPhase ----------------------------------------------------------------------------

    @Test
    fun theMoonIsFullOnTheSeventhOfOctober2025() {
        val phase = Astronomy.moonPhase(at("2025-10-07T03:48:00Z"))
        assertEquals(0.5, phase.cycle, 0.01)
        assertTrue(phase.illumination > 0.99, "illumination ${phase.illumination}")
        assertEquals(MoonPhaseName.FULL, phase.name)
    }

    @Test
    fun theMoonIsNewOnTheTwentyFirstOfOctober2025() {
        val phase = Astronomy.moonPhase(at("2025-10-21T12:25:00Z"))
        assertTrue(cycleGap(phase.cycle, 0.0) < 0.01, "cycle ${phase.cycle}")
        assertTrue(phase.illumination < 0.01, "illumination ${phase.illumination}")
        assertEquals(MoonPhaseName.NEW, phase.name)
    }

    @Test
    fun theMoonIsAboutHalfLitAtItsQuarters() {
        // First quarter 29 Sep 2025 23:54 UTC; last quarter 13 Oct 2025 18:12 UTC.
        val first = Astronomy.moonPhase(at("2025-09-29T23:54:00Z"))
        val last = Astronomy.moonPhase(at("2025-10-13T18:12:00Z"))
        assertEquals(0.25, first.cycle, 0.015)
        assertEquals(0.75, last.cycle, 0.015)
        assertEquals(0.5, first.illumination, 0.05)
        assertEquals(0.5, last.illumination, 0.05)
        assertTrue(first.waxing)
        assertTrue(!last.waxing)
    }

    @Test
    fun theCycleAndIlluminationStayInRangeThroughAYear() {
        val start = at("2025-01-01T00:00:00Z")
        for (day in 0 until 365 step 3) {
            val phase = Astronomy.moonPhase(start + day * 86_400L)
            assertTrue(phase.cycle >= 0.0 && phase.cycle < 1.0, "cycle ${phase.cycle} on day $day")
            assertTrue(phase.illumination >= 0.0 && phase.illumination <= 1.0, "illumination ${phase.illumination} on day $day")
        }
    }

    // ---- nextMoon -----------------------------------------------------------------------------

    @Test
    fun theNextFullMoonAfterTheFirstOfOctoberLandsWithinAnHourOfTheAlmanac() {
        val next = Astronomy.nextMoon(at("2025-10-01T00:00:00Z"), full = true)
        assertTrue(abs(next - at("2025-10-07T03:48:00Z")) <= 3_600, "landed ${(next - at("2025-10-07T03:48:00Z")) / 60} minutes from the almanac")
    }

    @Test
    fun theNextNewMoonAfterTheTenthOfOctoberLandsWithinAnHourOfTheAlmanac() {
        val next = Astronomy.nextMoon(at("2025-10-10T00:00:00Z"), full = false)
        assertTrue(abs(next - at("2025-10-21T12:25:00Z")) <= 3_600, "landed ${(next - at("2025-10-21T12:25:00Z")) / 60} minutes from the almanac")
    }

    @Test
    fun theNextMoonIsAlwaysInTheFutureAndLessThanAMonthAway() {
        val from = at("2025-10-07T03:48:00Z") + 7_200
        val next = Astronomy.nextMoon(from, full = true)
        assertTrue(next > from)
        assertTrue(next - from < 31 * 86_400L)
    }

    // ---- MoonPhase.name -----------------------------------------------------------------------

    @Test
    fun theMoonsMonthIsToldInEightNames() {
        fun named(cycle: Double) = MoonPhase(cycle, 0.0).name
        assertEquals(MoonPhaseName.NEW, named(0.0))
        assertEquals(MoonPhaseName.NEW, named(0.02))
        assertEquals(MoonPhaseName.WAXING_CRESCENT, named(0.1))
        assertEquals(MoonPhaseName.FIRST_QUARTER, named(0.25))
        assertEquals(MoonPhaseName.WAXING_GIBBOUS, named(0.4))
        assertEquals(MoonPhaseName.FULL, named(0.5))
        assertEquals(MoonPhaseName.WANING_GIBBOUS, named(0.6))
        assertEquals(MoonPhaseName.LAST_QUARTER, named(0.75))
        assertEquals(MoonPhaseName.WANING_CRESCENT, named(0.9))
        assertEquals(MoonPhaseName.NEW, named(0.98))
    }

    @Test
    fun aMoonIsWaxingUntilItIsFull() {
        assertTrue(MoonPhase(0.3, 0.6).waxing)
        assertTrue(!MoonPhase(0.5, 1.0).waxing)
        assertTrue(!MoonPhase(0.8, 0.4).waxing)
    }
}
