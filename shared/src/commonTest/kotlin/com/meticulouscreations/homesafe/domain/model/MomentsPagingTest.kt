package com.meticulouscreations.homesafe.domain.model

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

class MomentsPagingTest {

    @Test
    fun theEndOfADayIsTheFirstInstantOfTheNextOneInThatZone() {
        val day = LocalDate(2026, 9, 10)
        // 2026-09-11T00:00Z
        assertEquals(1_789_084_800.0, day.endOfDayEpochSeconds(TimeZone.UTC))
        // Pacific daylight time is seven hours behind: the day ends seven hours later on the epoch clock.
        assertEquals(1_789_084_800.0 + 7 * 3600, day.endOfDayEpochSeconds(TimeZone.of("America/Los_Angeles")))
    }

    @Test
    fun theLastDayOfAMonthEndsInTheNextMonth() {
        // Not a month-arithmetic slip: 30 Sep + 1 day is 1 Oct.
        assertEquals(LocalDate(2026, 10, 1).endOfDayEpochSeconds(TimeZone.UTC) - 86_400, LocalDate(2026, 9, 30).endOfDayEpochSeconds(TimeZone.UTC))
    }
}
