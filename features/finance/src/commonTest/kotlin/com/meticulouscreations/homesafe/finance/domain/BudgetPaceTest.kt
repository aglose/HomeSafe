package com.meticulouscreations.homesafe.finance.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [BudgetPace]: where a month's spending so far leads, and what that is called. */
class BudgetPaceTest {

    private fun budget(
        daily: List<Double>,
        limit: Double? = 3_100.0,
        savingsLine: Double? = null,
        lastMonth: Double? = null,
        day: Int = daily.size,
        current: Boolean = true,
    ) = Budget(
        configured = true,
        sandbox = false,
        month = "2026-10",
        isCurrentMonth = current,
        day = day,
        daysInMonth = 31,
        syncedAtEpochSeconds = null,
        changedAtEpochSeconds = null,
        syncing = false,
        nextSyncAtEpochSeconds = null,
        ready = true,
        cards = emptyList(),
        config = BudgetConfig(limits = BudgetLimits(total = limit)),
        savingsLine = savingsLine,
        spent = daily.sum(),
        pending = 0.0,
        buckets = emptyList(),
        daily = daily,
        categories = emptyList(),
        merchants = emptyList(),
        purchases = emptyList(),
        history = listOfNotNull(lastMonth?.let { BudgetPastMonth("2026-09", it, 30) }),
    )

    @Test
    fun anEvenMonthEndsAtItsLimit() {
        // $100 a day against $3,100 for 31 days: exactly on pace.
        val pace = BudgetPace.of(budget(List(10) { 100.0 }))
        assertEquals(100.0, pace.dailyRate, 1e-9)
        assertEquals(3_100.0, pace.projected, 1e-9)
        assertEquals(1_000.0, pace.evenPaceSoFar!!, 1e-9)
        assertEquals(100.0, pace.allowancePerDay!!, 1e-9)
        assertEquals(21, pace.daysLeft)
        assertEquals(PaceStatus.UNDER, pace.status)
        assertEquals(31, pace.limitDay)
    }

    @Test
    fun oneBigShopOnTheSecondIsSteadiedByLastMonth() {
        // $600 in two days is $300 a day raw; last month ran at $100 a day.
        val pace = BudgetPace.of(budget(listOf(0.0, 600.0), lastMonth = 3_000.0))
        assertEquals((600.0 + 3 * 100.0) / 5, pace.dailyRate, 1e-9)
        assertTrue(pace.projected < 600.0 + 29 * 300.0)
    }

    @Test
    fun withNoMonthBeforeItTheLimitsOwnPaceSteadiesIt() {
        val pace = BudgetPace.of(budget(listOf(400.0)))
        assertEquals((400.0 + 3 * 100.0) / 4, pace.dailyRate, 1e-9)
    }

    @Test
    fun aMonthRunningAheadIsSaidToBeBeforeItIsOver() {
        val pace = BudgetPace.of(budget(List(10) { 150.0 }))
        assertEquals(PaceStatus.PROJECTED_OVER, pace.status)
        // $1,500 spent, about $138 a day (steadied): the limit goes on the 22nd.
        assertEquals(22, pace.limitDay)
    }

    @Test
    fun withinAFifthOfTheLimitIsClose() {
        assertEquals(PaceStatus.CLOSE, BudgetPace.of(budget(List(25) { 100.0 })).status)
        assertEquals(PaceStatus.UNDER, BudgetPace.of(budget(List(24) { 100.0 })).status)
    }

    @Test
    fun pastTheLimitIsOverAndTheDayItHappenedIsRemembered() {
        val pace = BudgetPace.of(budget(List(8) { 500.0 }))
        assertEquals(PaceStatus.OVER, pace.status)
        assertEquals(7, pace.limitDay)
        assertEquals(0.0, pace.allowancePerDay!!, 1e-9)
        assertTrue(pace.level > 1f)
        assertEquals(1f, pace.heat)
    }

    @Test
    fun pastWhatTakeHomeLeavesIsDippingWhateverTheLimit() {
        val over = BudgetPace.of(budget(List(8) { 500.0 }, savingsLine = 4_500.0))
        assertEquals(PaceStatus.OVER, over.status)
        assertEquals(10, over.savingsDay)
        val dipping = BudgetPace.of(budget(List(10) { 500.0 }, savingsLine = 4_500.0))
        assertEquals(PaceStatus.DIPPING, dipping.status)
        assertEquals(9, dipping.savingsDay)
        assertEquals(PaceStatus.DIPPING, BudgetPace.of(budget(List(10) { 500.0 }, limit = null, savingsLine = 4_500.0)).status)
    }

    @Test
    fun withoutALimitThereIsNothingToPaceAgainst() {
        val pace = BudgetPace.of(budget(List(10) { 100.0 }, limit = null))
        assertEquals(PaceStatus.NO_LIMIT, pace.status)
        assertNull(pace.limitDay)
        assertNull(pace.allowancePerDay)
        assertEquals(0f, pace.level)
        assertEquals(0f, pace.heat)
    }

    @Test
    fun aMonthThatIsOverIsNotProjected() {
        val pace = BudgetPace.of(budget(List(31) { 90.0 }, current = false))
        assertEquals(2_790.0, pace.projected, 1e-9)
        assertEquals(0, pace.daysLeft)
        assertNull(pace.limitDay)
    }

    @Test
    fun aMonthNotYetBegunHasNoPace() {
        val pace = BudgetPace.of(budget(emptyList(), day = 0, current = false))
        assertEquals(0.0, pace.dailyRate, 1e-9)
        assertEquals(0.0, pace.projected, 1e-9)
    }

    @Test
    fun theMonthWarmsOnlyOnceItIsWellSpent() {
        assertEquals(0f, BudgetPace.heatOf(0.5f))
        assertTrue(BudgetPace.heatOf(0.8f) in 0.5f..0.6f)
        assertEquals(1f, BudgetPace.heatOf(1.3f))
    }

    @Test
    fun theSheetsBillsAreTheLinesThatAreNotPaidByCard() {
        val finance = PersonalFinance(
            title = "Budget", fetchedAtEpochSeconds = 0, sourceUrl = null, people = listOf("Alex", "Sam"),
            income = listOf(IncomeLine("Alex", 6_000.0), IncomeLine("Sam", 4_000.0)), monthlyIncome = null, monthlyExpenses = null, netMonthly = null,
            expenses = listOf(ExpenseLine("Mortgage", 3_000.0), ExpenseLine("Groceries", 900.0), ExpenseLine("Daycare", 2_000.0)),
            accounts = emptyList(), investmentsTotal = null, totalAssets = null, emergencyTarget = null, debts = emptyList(), home = null, vesting = emptyList(),
            watchlist = emptyList(), history = emptyList(), taxYears = emptyList(), mortgagePlan = null, oldHouse = null,
        )
        assertEquals(BudgetSheetFigures(takeHome = 10_000.0, billsOffCard = 5_000.0), BudgetSheetFigures.of(finance, listOf("Groceries")))
        assertEquals(BudgetSheetFigures(takeHome = 9_500.0, billsOffCard = null), BudgetSheetFigures.of(finance.copy(monthlyIncome = 9_500.0, expenses = emptyList()), emptyList()))
    }

    @Test
    fun aBucketsNameOnTheWireReadsBackAsItself() {
        listOf(BucketId.Family, BucketId.Person("Sam"), BucketId.Unassigned).forEach { assertEquals(it, BucketId.ofWire(it.wire)) }
        assertEquals(BucketId.Unassigned, BucketId.ofWire("unassigned"))
        listOf(CardRole.Split, CardRole.Family, CardRole.Ignore, CardRole.Person("Sam"), CardRole.Unset).forEach { assertEquals(it, CardRole.ofWire(it.wire)) }
    }
}
