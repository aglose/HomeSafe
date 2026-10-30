package com.meticulouscreations.homesafe.finance.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [BudgetCompare]: an earlier month against the month under way, day for day. */
class BudgetCompareTest {

    private fun budget(month: String, daily: List<Double>, current: Boolean, daysInMonth: Int = 31) = Budget(
        configured = true,
        sandbox = false,
        month = month,
        isCurrentMonth = current,
        day = if (current) daily.size else daysInMonth,
        daysInMonth = daysInMonth,
        syncedAtEpochSeconds = null,
        changedAtEpochSeconds = null,
        syncing = false,
        nextSyncAtEpochSeconds = null,
        ready = true,
        cards = emptyList(),
        config = BudgetConfig(),
        savingsLine = null,
        spent = daily.sum(),
        pending = 0.0,
        buckets = emptyList(),
        daily = daily,
        categories = emptyList(),
        merchants = emptyList(),
        purchases = emptyList(),
        history = emptyList(),
    )

    private val october = budget("2026-10", List(12) { 100.0 }, current = true)

    @Test
    fun anEarlierMonthIsCountedOnlyUpToToday() {
        // September spent $80 a day all month; by the 12th that was $960, against October's $1,200.
        val compare = BudgetCompare.of(budget("2026-09", List(30) { 80.0 }, current = false, daysInMonth = 30), october)!!
        assertEquals(12, compare.day)
        assertEquals(960.0, compare.then, 1e-9)
        assertEquals(1_200.0, compare.now, 1e-9)
        assertEquals(240.0, compare.difference, 1e-9)
        assertFalse(compare.level)
    }

    @Test
    fun aShorterMonthIsCountedToItsLastDayAndThisOneToTheSame() {
        val late = budget("2026-03", List(30) { 100.0 }, current = true)
        val february = budget("2026-02", List(28) { 50.0 }, current = false, daysInMonth = 28)
        val compare = BudgetCompare.of(february, late)!!
        assertEquals(28, compare.day)
        assertEquals(1_400.0, compare.then, 1e-9)
        assertEquals(2_800.0, compare.now, 1e-9)
    }

    @Test
    fun monthsWithinAFiftiethOfEachOtherAreLevel() {
        val compare = BudgetCompare.of(budget("2026-09", List(30) { 99.0 }, current = false, daysInMonth = 30), october)!!
        assertTrue(compare.level)
    }

    @Test
    fun onlyAnEarlierMonthAgainstTheOneUnderWayIsCompared() {
        assertNull(BudgetCompare.of(october, october))
        val september = budget("2026-09", List(30) { 80.0 }, current = false, daysInMonth = 30)
        assertNull(BudgetCompare.of(october, september))
        assertNull(BudgetCompare.of(september, budget("2026-08", List(31) { 80.0 }, current = false)))
    }
}
