package com.meticulouscreations.homesafe.weather.domain

import com.meticulouscreations.homesafe.weather.WeatherData
import com.meticulouscreations.homesafe.weather.WeatherData.DAY
import com.meticulouscreations.homesafe.weather.WeatherData.HOUR
import com.meticulouscreations.homesafe.weather.WeatherData.OFFSET
import com.meticulouscreations.homesafe.weather.WeatherData.at
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a forecast hands out as "from now on", most of all once it is an old one read back from
 * the device: hours and days that are over are not the hours and days ahead.
 */
class WeatherReportTest {

    private val now = at(0, 15, 30)
    private val report = WeatherData.report(now, minutely = WeatherData.slices(at(0, 15, 15), 0.0, 0.0, 0.4, 0.0))

    // ---- days ---------------------------------------------------------------------------------

    @Test
    fun theDaysRunFromTodayOn() {
        assertEquals(listOf(at(0, 0), at(1, 0), at(2, 0)), report.daysFromToday(now).map { it.epochSeconds })
    }

    @Test
    fun theLastDayIsStillTodayUntilItEnds() {
        assertEquals(listOf(at(2, 0)), report.daysFromToday(at(2, 23, 59)).map { it.epochSeconds })
    }

    @Test
    fun onceTheLastDayIsOverThereAreNoDaysAhead() {
        // A day is given two hours' grace past its twenty-four, for the night the clocks go back.
        assertTrue(report.daysFromToday(at(3, 2)).isEmpty())
        assertTrue(report.daysFromToday(at(9, 12)).isEmpty())
    }

    @Test
    fun beforeTheFirstDayEveryDayIsAhead() {
        assertEquals(4, report.daysFromToday(at(-3, 12)).size)
    }

    // ---- hours --------------------------------------------------------------------------------

    @Test
    fun theHoursRunFromTheOneInProgress() {
        assertEquals(at(0, 15), report.hoursFrom(now).first().epochSeconds)
    }

    @Test
    fun theLastHourCountsUntilItIsOver() {
        assertEquals(listOf(at(2, 23)), report.hoursFrom(at(2, 23, 59)).map { it.epochSeconds })
        assertTrue(report.hoursFrom(at(3, 0)).isEmpty())
        assertTrue(report.hoursFrom(at(5, 9)).isEmpty())
    }

    @Test
    fun aDaysHoursEndWhereTheNextDayStarts() {
        // A day the service cut an hour long, as it would be on the night the clocks go back.
        val long = report.copy(daily = listOf(WeatherData.day(0), WeatherData.day(1).copy(epochSeconds = at(1, 1)), WeatherData.day(2)))
        assertEquals(25, long.hoursOf(long.daily[0]).size)
        assertEquals(23, long.hoursOf(long.daily[1]).size)
        // The last day has no next one to end at: twenty-four hours.
        assertEquals(24, long.hoursOf(long.daily[2]).size)
    }

    // ---- quarter hours ------------------------------------------------------------------------

    @Test
    fun theQuarterHoursRunFromTheOneInProgress() {
        assertEquals(at(0, 15, 30), report.slicesFrom(now).first().epochSeconds)
        assertEquals(at(0, 15, 15), report.slicesFrom(at(0, 15, 29)).first().epochSeconds)
    }

    @Test
    fun onceTheLastQuarterHourIsOverThereAreNone() {
        assertEquals(1, report.slicesFrom(at(0, 16, 14)).size)
        assertTrue(report.slicesFrom(at(0, 16, 15)).isEmpty())
        assertTrue(report.slicesFrom(now + DAY).isEmpty())
    }

    // ---- the clock ----------------------------------------------------------------------------

    @Test
    fun aMomentIsReadByTheClockAsItStandsThen() {
        // Portland's clocks went back at 2 AM on Sunday 2 November 2025.
        val before = 1_762_070_400L // 1 AM PDT that morning
        assertEquals(OFFSET, report.offsetAt(before))
        assertEquals(OFFSET - HOUR.toInt(), report.offsetAt(before + 2 * HOUR))
        // So the hour after that morning's second 1 AM is 2 AM, not 3.
        assertEquals(2, report.localHour(before + 2 * HOUR))
        assertEquals(1, report.localHour(before))
    }

    @Test
    fun aPlaceWhoseZoneIsNotKnownKeepsTheForecastsOwnOffset() {
        assertEquals(OFFSET, report.copy(timeZoneId = "").offsetAt(now + 60 * DAY))
        assertEquals(OFFSET, report.copy(timeZoneId = "Nowhere/Much").offsetAt(now + 60 * DAY))
    }
}
