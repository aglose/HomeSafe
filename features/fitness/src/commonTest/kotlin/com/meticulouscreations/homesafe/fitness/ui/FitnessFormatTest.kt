package com.meticulouscreations.homesafe.fitness.ui

import com.meticulouscreations.homesafe.fitness.domain.NotesParser
import com.meticulouscreations.homesafe.text.UiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.fitness_history_date
import homesafe.shared.generated.resources.fitness_history_date_year
import homesafe.shared.generated.resources.fitness_history_hours_minutes
import homesafe.shared.generated.resources.fitness_history_minutes
import homesafe.shared.generated.resources.fitness_history_month_december
import homesafe.shared.generated.resources.fitness_history_month_january
import homesafe.shared.generated.resources.fitness_history_month_october
import homesafe.shared.generated.resources.fitness_history_month_year
import homesafe.shared.generated.resources.fitness_history_time_am
import homesafe.shared.generated.resources.fitness_history_time_pm
import homesafe.shared.generated.resources.fitness_history_volume
import homesafe.shared.generated.resources.fitness_history_weekday_monday
import homesafe.shared.generated.resources.fitness_history_weekday_sunday
import homesafe.shared.generated.resources.fitness_history_weekday_thursday
import kotlin.test.Test
import kotlin.test.assertEquals

/** How the History tab writes a month, a day, a time of day, a workout's length and its volume. */
class FitnessFormatTest {

    private val day = 86_400L

    @Test
    fun theFirstOfAMonthIsCountedFrom1970AndAMonthRunsToTheNextOnesFirst() {
        assertEquals(0L, FitnessFormat.firstOfMonth(1970, 1))
        assertEquals(31L, FitnessFormat.firstOfMonth(1970, 2))
        // A leap February, and a year's end.
        assertEquals(29L, FitnessFormat.firstOfMonth(2024, 3) - FitnessFormat.firstOfMonth(2024, 2))
        assertEquals(31L, FitnessFormat.firstOfMonth(2027, 1) - FitnessFormat.firstOfMonth(2026, 12))
    }

    @Test
    fun weekdaysAreCountedFromMonday() {
        // 1 January 1970 was a Thursday.
        assertEquals(3, FitnessFormat.weekday(0))
        assertEquals(0, FitnessFormat.weekday(4))
        assertEquals(6, FitnessFormat.weekday(3))
        // And before 1970 the count still runs the same way.
        assertEquals(2, FitnessFormat.weekday(-1))
        assertEquals(Res.string.fitness_history_weekday_monday, FitnessFormat.weekdayName(0))
        assertEquals(Res.string.fitness_history_weekday_sunday, FitnessFormat.weekdayName(6))
    }

    @Test
    fun aMonthIsHeadedByItsNameAndYear() {
        assertEquals(Res.string.fitness_history_month_january, FitnessFormat.monthName(1))
        assertEquals(Res.string.fitness_history_month_december, FitnessFormat.monthName(12))
        assertEquals(UiText.of(Res.string.fitness_history_month_year, UiText.of(Res.string.fitness_history_month_october), 2026), FitnessFormat.monthYear(2026, 10))
    }

    @Test
    fun aDayIsWrittenWithItsWeekdayAndTheYearOnlyWhenAsked() {
        val thursday = NotesParser.epochDay(2026, 10, 8)
        val weekday = UiText.of(Res.string.fitness_history_weekday_thursday)
        val month = UiText.of(Res.string.fitness_history_month_october)
        assertEquals(UiText.of(Res.string.fitness_history_date, weekday, month, 8), FitnessFormat.day(thursday))
        assertEquals(UiText.of(Res.string.fitness_history_date_year, weekday, month, 8, 2026), FitnessFormat.day(thursday, withYear = true))
    }

    @Test
    fun theTimeOfDayIsByATwelveHourClockInTheGivenOffset() {
        val midnight = NotesParser.epochDay(2026, 10, 8) * day
        assertEquals(UiText.of(Res.string.fitness_history_time_am, 12, "00"), FitnessFormat.timeOfDay(midnight, 0))
        assertEquals(UiText.of(Res.string.fitness_history_time_am, 9, "05"), FitnessFormat.timeOfDay(midnight + 9 * 3_600 + 5 * 60, 0))
        assertEquals(UiText.of(Res.string.fitness_history_time_pm, 12, "30"), FitnessFormat.timeOfDay(midnight + 12 * 3_600 + 30 * 60, 0))
        assertEquals(UiText.of(Res.string.fitness_history_time_pm, 5, "00"), FitnessFormat.timeOfDay(midnight + 17 * 3_600, 0))
        // Five in the afternoon UTC is ten in the morning seven hours west, and the day before's evening is still that day's.
        assertEquals(UiText.of(Res.string.fitness_history_time_am, 10, "00"), FitnessFormat.timeOfDay(midnight + 17 * 3_600, -7 * 3_600))
        assertEquals(UiText.of(Res.string.fitness_history_time_pm, 11, "00"), FitnessFormat.timeOfDay(midnight, -3_600))
    }

    @Test
    fun aWorkoutsLengthIsInMinutesAndPastAnHourInHoursAndMinutes() {
        assertEquals(UiText.of(Res.string.fitness_history_minutes, 0), FitnessFormat.duration(20))
        assertEquals(UiText.of(Res.string.fitness_history_minutes, 55), FitnessFormat.duration(3_300))
        assertEquals(UiText.of(Res.string.fitness_history_minutes, 59), FitnessFormat.duration(59 * 60 + 29))
        assertEquals(UiText.of(Res.string.fitness_history_hours_minutes, 1, 0), FitnessFormat.duration(59 * 60 + 30))
        assertEquals(UiText.of(Res.string.fitness_history_hours_minutes, 1, 5), FitnessFormat.duration(65 * 60))
        assertEquals(UiText.of(Res.string.fitness_history_minutes, 0), FitnessFormat.duration(-40))
    }

    @Test
    fun aWholeNumberHasItsThousandsMarkedOff() {
        assertEquals("0", FitnessFormat.grouped(0))
        assertEquals("999", FitnessFormat.grouped(999))
        assertEquals("1,000", FitnessFormat.grouped(1_000))
        assertEquals("12,400", FitnessFormat.grouped(12_400))
        assertEquals("1,234,567", FitnessFormat.grouped(1_234_567))
        assertEquals(UiText.of(Res.string.fitness_history_volume, "7,722"), FitnessFormat.volume(7_721.6))
    }
}
