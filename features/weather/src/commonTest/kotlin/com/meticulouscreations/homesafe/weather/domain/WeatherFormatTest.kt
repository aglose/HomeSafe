package com.meticulouscreations.homesafe.weather.domain

import com.meticulouscreations.homesafe.text.UiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.weather_compass_e
import homesafe.shared.generated.resources.weather_compass_n
import homesafe.shared.generated.resources.weather_compass_ne
import homesafe.shared.generated.resources.weather_compass_nw
import homesafe.shared.generated.resources.weather_compass_s
import homesafe.shared.generated.resources.weather_compass_se
import homesafe.shared.generated.resources.weather_compass_sw
import homesafe.shared.generated.resources.weather_compass_w
import homesafe.shared.generated.resources.weather_date_month_day
import homesafe.shared.generated.resources.weather_month_dec
import homesafe.shared.generated.resources.weather_month_oct
import homesafe.shared.generated.resources.weather_time_am
import homesafe.shared.generated.resources.weather_time_pm
import homesafe.shared.generated.resources.weather_unit_cm
import homesafe.shared.generated.resources.weather_unit_hpa
import homesafe.shared.generated.resources.weather_unit_in
import homesafe.shared.generated.resources.weather_unit_inhg
import homesafe.shared.generated.resources.weather_unit_km
import homesafe.shared.generated.resources.weather_unit_kmh
import homesafe.shared.generated.resources.weather_unit_mi
import homesafe.shared.generated.resources.weather_unit_mm
import homesafe.shared.generated.resources.weather_unit_mph
import homesafe.shared.generated.resources.weather_weekday_fri
import homesafe.shared.generated.resources.weather_weekday_long_thu
import homesafe.shared.generated.resources.weather_weekday_mon
import homesafe.shared.generated.resources.weather_weekday_sat
import homesafe.shared.generated.resources.weather_weekday_sun
import homesafe.shared.generated.resources.weather_weekday_thu
import homesafe.shared.generated.resources.weather_weekday_wed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WeatherFormatTest {

    private val imperial = WeatherUnits(TemperatureUnit.FAHRENHEIT, MeasureSystem.IMPERIAL)
    private val metric = WeatherUnits(TemperatureUnit.CELSIUS, MeasureSystem.METRIC)

    /** Local midnight on Thursday 9 October 2025 in Portland (UTC-7). */
    private val midnight = 1_759_993_200L
    private val pdt = -25_200
    private val hour = 3_600L

    // ---- Temperatures -------------------------------------------------------------------------

    @Test
    fun degreesConvertsToFahrenheitAndRounds() {
        assertEquals("72°", WeatherFormat.degrees(22.0, imperial))
        assertEquals("32°", WeatherFormat.degrees(0.0, imperial))
        assertEquals("-40°", WeatherFormat.degrees(-40.0, imperial))
    }

    @Test
    fun degreesInCelsiusRoundsToTheNearestWhole() {
        assertEquals("22°", WeatherFormat.degrees(21.6, metric))
        assertEquals("21°", WeatherFormat.degrees(21.4, metric))
        assertEquals("3°", WeatherFormat.degrees(2.5, metric))
        assertEquals("-4°", WeatherFormat.degrees(-3.6, metric))
    }

    @Test
    fun degreesIsNeverMinusZero() {
        assertEquals("0°", WeatherFormat.degrees(-0.4, metric))
        assertEquals("0°", WeatherFormat.degrees(-0.0, metric))
        // -17.8 C is 0.04 F.
        assertEquals("0°", WeatherFormat.degrees(-17.8, imperial))
    }

    @Test
    fun degreesBetweenIsASizeSoItHasNoSign() {
        assertEquals("5°", WeatherFormat.degreesBetween(-5.0, metric))
        assertEquals("5°", WeatherFormat.degreesBetween(5.0, metric))
    }

    @Test
    fun degreesBetweenScalesByNineFifthsButDoesNotAddThirtyTwo() {
        assertEquals("9°", WeatherFormat.degreesBetween(5.0, imperial))
        assertEquals("0°", WeatherFormat.degreesBetween(0.0, imperial))
    }

    @Test
    fun degreesBetweenValueIsTheSameDifferenceAsANumberInTheChosenUnit() {
        assertEquals(9, WeatherFormat.degreesBetweenValue(-5.0, imperial))
        assertEquals(5, WeatherFormat.degreesBetweenValue(-5.0, metric))
    }

    @Test
    fun toDisplayConvertsOnlyForFahrenheit() {
        assertEquals(212.0, WeatherFormat.toDisplay(100.0, imperial), 1e-9)
        assertEquals(100.0, WeatherFormat.toDisplay(100.0, metric), 1e-9)
    }

    // ---- decimal ------------------------------------------------------------------------------

    @Test
    fun decimalPadsAndRoundsToTheRequestedPlaces() {
        assertEquals("3.142", WeatherFormat.decimal(3.14159, 3))
        assertEquals("0.50", WeatherFormat.decimal(0.5, 2))
        assertEquals("1234.57", WeatherFormat.decimal(1234.5678, 2))
        assertEquals("5", WeatherFormat.decimal(5.0, 0))
        assertEquals("0.05", WeatherFormat.decimal(0.05, 2))
    }

    @Test
    fun decimalKeepsTheSignOfNegativeNumbers() {
        assertEquals("-12", WeatherFormat.decimal(-12.34, 0))
        assertEquals("-0.5", WeatherFormat.decimal(-0.5, 1))
    }

    @Test
    fun decimalNeverWritesMinusZero() {
        assertEquals("0.0", WeatherFormat.decimal(-0.04, 1))
        assertEquals("0", WeatherFormat.decimal(-0.4, 0))
    }

    // ---- Wind, rain, snow, distance, pressure ---------------------------------------------------

    @Test
    fun speedIsMilesPerHourInImperialAndKilometresPerHourInMetric() {
        assertEquals(UiText.of(Res.string.weather_unit_mph, "62"), WeatherFormat.speed(100.0, imperial))
        assertEquals(UiText.of(Res.string.weather_unit_kmh, "100"), WeatherFormat.speed(100.0, metric))
        assertEquals(62, WeatherFormat.speedValue(100.0, imperial))
        assertEquals(Res.string.weather_unit_mph, WeatherFormat.speedUnit(imperial))
        assertEquals(Res.string.weather_unit_kmh, WeatherFormat.speedUnit(metric))
    }

    @Test
    fun precipitationIsInchesWithTwoPlacesUntilItReachesAnInch() {
        assertEquals(UiText.of(Res.string.weather_unit_in, "0.42"), WeatherFormat.precipitation(10.7, imperial))
        assertEquals(UiText.of(Res.string.weather_unit_in, "1.2"), WeatherFormat.precipitation(30.0, imperial))
    }

    @Test
    fun precipitationIsMillimetresWithOnePlaceUntilItReachesTen() {
        assertEquals(UiText.of(Res.string.weather_unit_mm, "3.3"), WeatherFormat.precipitation(3.26, metric))
        assertEquals(UiText.of(Res.string.weather_unit_mm, "11"), WeatherFormat.precipitation(10.7, metric))
        assertEquals(UiText.of(Res.string.weather_unit_mm, "0.0"), WeatherFormat.precipitation(0.0, metric))
    }

    @Test
    fun snowIsInchesInImperialAndCentimetresInMetric() {
        assertEquals(UiText.of(Res.string.weather_unit_in, "3.5"), WeatherFormat.snow(9.0, imperial))
        assertEquals(UiText.of(Res.string.weather_unit_in, "12"), WeatherFormat.snow(30.0, imperial))
        assertEquals(UiText.of(Res.string.weather_unit_cm, "9.0"), WeatherFormat.snow(9.0, metric))
        assertEquals(UiText.of(Res.string.weather_unit_cm, "25"), WeatherFormat.snow(25.0, metric))
    }

    @Test
    fun distanceIsMilesOrKilometresWithOnePlaceUnderTen() {
        assertEquals(UiText.of(Res.string.weather_unit_mi, "10"), WeatherFormat.distance(16_093.44, imperial))
        assertEquals(UiText.of(Res.string.weather_unit_mi, "3.1"), WeatherFormat.distance(5_000.0, imperial))
        assertEquals(UiText.of(Res.string.weather_unit_km, "16"), WeatherFormat.distance(16_000.0, metric))
        assertEquals(UiText.of(Res.string.weather_unit_km, "5.0"), WeatherFormat.distance(5_000.0, metric))
    }

    @Test
    fun pressureIsInchesOfMercuryOrHectopascals() {
        assertEquals(UiText.of(Res.string.weather_unit_inhg, "30.12"), WeatherFormat.pressure(1020.0, imperial))
        assertEquals(UiText.of(Res.string.weather_unit_hpa, "1020"), WeatherFormat.pressure(1019.6, metric))
    }

    @Test
    fun percentIsTheNumberAndASign() {
        assertEquals("85%", WeatherFormat.percent(85))
    }

    // ---- The place's clock --------------------------------------------------------------------

    @Test
    fun hourAtMidnightIsTwelveAm() {
        assertEquals(UiText.of(Res.string.weather_time_am, "12"), WeatherFormat.hour(midnight, pdt))
    }

    @Test
    fun hourAtNoonIsTwelvePm() {
        assertEquals(UiText.of(Res.string.weather_time_pm, "12"), WeatherFormat.hour(midnight + 12 * hour, pdt))
    }

    @Test
    fun hourAtOnePmIsOnePm() {
        assertEquals(UiText.of(Res.string.weather_time_pm, "1"), WeatherFormat.hour(midnight + 13 * hour, pdt))
        assertEquals(UiText.of(Res.string.weather_time_am, "11"), WeatherFormat.hour(midnight + 11 * hour, pdt))
    }

    @Test
    fun hourReadsTheOffsetNotTheUtcClock() {
        // 07:00 UTC is midnight in Portland and 4 PM in Tokyo (+9).
        assertEquals(UiText.of(Res.string.weather_time_am, "12"), WeatherFormat.hour(midnight, pdt))
        assertEquals(UiText.of(Res.string.weather_time_pm, "4"), WeatherFormat.hour(midnight, 9 * 3_600))
    }

    @Test
    fun clockWritesMinutesWithTwoDigits() {
        assertEquals(UiText.of(Res.string.weather_time_pm, "3:15"), WeatherFormat.clock(midnight + 15 * hour + 15 * 60, pdt))
        assertEquals(UiText.of(Res.string.weather_time_am, "12:05"), WeatherFormat.clock(midnight + 5 * 60, pdt))
        assertEquals(UiText.of(Res.string.weather_time_pm, "12:00"), WeatherFormat.clock(midnight + 12 * hour, pdt))
    }

    @Test
    fun hourOrClockIsTheHourOnTheHourAndTheClockOtherwise() {
        assertEquals(WeatherFormat.hour(midnight + 15 * hour, pdt), WeatherFormat.hourOrClock(midnight + 15 * hour, pdt))
        assertEquals(WeatherFormat.hour(midnight + 15 * hour, pdt), WeatherFormat.hourOrClock(midnight + 15 * hour + 59, pdt))
        assertEquals(WeatherFormat.clock(midnight + 15 * hour + 60, pdt), WeatherFormat.hourOrClock(midnight + 15 * hour + 60, pdt))
        assertEquals(UiText.of(Res.string.weather_time_pm, "3:30"), WeatherFormat.hourOrClock(midnight + 15 * hour + 1_800, pdt))
    }

    @Test
    fun theWeekdayIsCountedFromMondayAtZero() {
        // 1 January 1970 was a Thursday.
        assertEquals(3, WeatherFormat.weekdayIndex(0, 0))
        assertEquals(3, WeatherFormat.weekdayIndex(midnight, pdt))
        assertEquals(4, WeatherFormat.weekdayIndex(midnight + 24 * hour, pdt))
        assertEquals(0, WeatherFormat.weekdayIndex(midnight + 4 * 24 * hour, pdt))
    }

    @Test
    fun theWeekdayBeforeTheEpochCountsBackwards() {
        assertEquals(2, WeatherFormat.weekdayIndex(-1, 0))
        assertEquals(2, WeatherFormat.weekdayIndex(-86_400, 0))
    }

    @Test
    fun theWeekdayFollowsThePlacesClock() {
        // 03:00 UTC on the 10th is still Thursday evening in Portland.
        val lateThursdayEvening = midnight + 20 * hour
        assertEquals(3, WeatherFormat.weekdayIndex(lateThursdayEvening, pdt))
        assertEquals(4, WeatherFormat.weekdayIndex(lateThursdayEvening, 0))
    }

    @Test
    fun saturdayAndSundayAreTheWeekend() {
        val friday = midnight + 24 * hour
        assertFalse(WeatherFormat.isWeekend(friday, pdt))
        assertTrue(WeatherFormat.isWeekend(friday + 24 * hour, pdt))
        assertTrue(WeatherFormat.isWeekend(friday + 48 * hour, pdt))
        assertFalse(WeatherFormat.isWeekend(friday + 72 * hour, pdt))
    }

    @Test
    fun weekdayNamesAreResources() {
        assertEquals(Res.string.weather_weekday_thu, WeatherFormat.weekday(midnight, pdt))
        assertEquals(Res.string.weather_weekday_long_thu, WeatherFormat.weekdayLong(midnight, pdt))
        assertEquals(Res.string.weather_weekday_fri, WeatherFormat.weekday(midnight + 24 * hour, pdt))
        assertEquals(Res.string.weather_weekday_sat, WeatherFormat.weekday(midnight + 48 * hour, pdt))
        assertEquals(Res.string.weather_weekday_sun, WeatherFormat.weekday(midnight + 72 * hour, pdt))
        assertEquals(Res.string.weather_weekday_mon, WeatherFormat.weekday(midnight + 96 * hour, pdt))
        assertEquals(Res.string.weather_weekday_wed, WeatherFormat.weekday(midnight - 24 * hour, pdt))
    }

    @Test
    fun dateIsTheMonthNameAndTheDay() {
        assertEquals(UiText.of(Res.string.weather_date_month_day, UiText.of(Res.string.weather_month_oct), "9"), WeatherFormat.date(midnight, pdt))
        // 31 days on is 9 November, and 25 December is another month name again.
        val christmas = 1_766_646_000L // 2025-12-25T07:00:00Z, midnight in Portland
        assertEquals(UiText.of(Res.string.weather_date_month_day, UiText.of(Res.string.weather_month_dec), "25"), WeatherFormat.date(christmas, pdt))
    }

    @Test
    fun sameDayComparesCalendarDaysOnThePlacesClock() {
        val onePm = midnight + 13 * hour
        val eightPm = midnight + 20 * hour
        // 8 PM Thursday in Portland is already Friday in UTC.
        assertTrue(WeatherFormat.sameDay(onePm, eightPm, pdt))
        assertFalse(WeatherFormat.sameDay(onePm, eightPm, 0))
        assertFalse(WeatherFormat.sameDay(eightPm, eightPm + 5 * hour, pdt))
    }

    @Test
    fun sameDayHoldsAtTheMinuteEitherSideOfMidnight() {
        assertFalse(WeatherFormat.sameDay(midnight - 1, midnight, pdt))
        assertTrue(WeatherFormat.sameDay(midnight, midnight + 86_399, pdt))
    }

    // ---- compass ------------------------------------------------------------------------------

    @Test
    fun compassNamesTheEightPoints() {
        assertEquals(Res.string.weather_compass_n, WeatherFormat.compass(0))
        assertEquals(Res.string.weather_compass_ne, WeatherFormat.compass(45))
        assertEquals(Res.string.weather_compass_e, WeatherFormat.compass(90))
        assertEquals(Res.string.weather_compass_se, WeatherFormat.compass(135))
        assertEquals(Res.string.weather_compass_s, WeatherFormat.compass(180))
        assertEquals(Res.string.weather_compass_sw, WeatherFormat.compass(225))
        assertEquals(Res.string.weather_compass_w, WeatherFormat.compass(270))
        assertEquals(Res.string.weather_compass_nw, WeatherFormat.compass(315))
    }

    @Test
    fun compassPicksTheNearestPointAtTheEdgesOfEachSector() {
        assertEquals(Res.string.weather_compass_n, WeatherFormat.compass(22))
        assertEquals(Res.string.weather_compass_ne, WeatherFormat.compass(23))
        assertEquals(Res.string.weather_compass_nw, WeatherFormat.compass(337))
        assertEquals(Res.string.weather_compass_n, WeatherFormat.compass(338))
    }

    @Test
    fun compassWrapsAroundTheCircle() {
        assertEquals(Res.string.weather_compass_n, WeatherFormat.compass(360))
        assertEquals(Res.string.weather_compass_n, WeatherFormat.compass(720))
        assertEquals(Res.string.weather_compass_e, WeatherFormat.compass(450))
        assertEquals(Res.string.weather_compass_nw, WeatherFormat.compass(-45))
        assertEquals(Res.string.weather_compass_n, WeatherFormat.compass(-1))
    }

    // ---- roundedMinutes -----------------------------------------------------------------------

    @Test
    fun roundedMinutesRoundsToTheNearestFive() {
        assertEquals(25, WeatherFormat.roundedMinutes(25 * 60L))
        assertEquals(25, WeatherFormat.roundedMinutes(25 * 60L + 30))
        assertEquals(30, WeatherFormat.roundedMinutes(27 * 60L + 30))
        assertEquals(65, WeatherFormat.roundedMinutes(4_000))
    }

    @Test
    fun roundedMinutesIsNeverLessThanFive() {
        assertEquals(5, WeatherFormat.roundedMinutes(0))
        assertEquals(5, WeatherFormat.roundedMinutes(60))
        assertEquals(5, WeatherFormat.roundedMinutes(-600))
    }
}
