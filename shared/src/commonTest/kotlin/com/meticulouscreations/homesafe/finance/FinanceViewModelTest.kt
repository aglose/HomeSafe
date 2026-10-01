package com.meticulouscreations.homesafe.finance

import com.meticulouscreations.homesafe.finance.domain.ChartRange
import com.meticulouscreations.homesafe.finance.domain.FinanceRepository
import com.meticulouscreations.homesafe.finance.domain.Indicator
import com.meticulouscreations.homesafe.finance.domain.IndicatorCatalog
import com.meticulouscreations.homesafe.finance.domain.IndicatorReading
import com.meticulouscreations.homesafe.finance.domain.PersonalFinance
import com.meticulouscreations.homesafe.finance.domain.PriceHistory
import com.meticulouscreations.homesafe.finance.domain.Quote
import com.meticulouscreations.homesafe.finance.domain.Series
import com.meticulouscreations.homesafe.finance.domain.SheetProblem
import com.meticulouscreations.homesafe.finance.domain.SheetUnavailableException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class FinanceViewModelTest {

    // viewModelScope dispatches on Dispatchers.Main, which the JVM test target has no implementation of.
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochSeconds(1_790_000_000)
    }

    /** Counts what's asked of it; histories come from [histories] in the order they're asked for. */
    private class FakeRepository : FinanceRepository {
        var quoteCalls = 0
        var indicatorCalls = 0
        var sheetCalls = 0
        val histories = ArrayDeque<CompletableDeferred<PriceHistory>>()

        override suspend fun quotes(symbols: List<String>, maxAgeMillis: Long): Result<List<Quote>> {
            quoteCalls++
            return Result.success(emptyList())
        }

        override suspend fun history(symbol: String, range: ChartRange): Result<PriceHistory> = Result.success(histories.removeFirst().await())

        override suspend fun fredSeries(seriesId: String, startDate: String): Result<Series> = Result.success(Series.Empty)

        override suspend fun indicator(indicator: Indicator): Result<IndicatorReading> {
            indicatorCalls++
            return Result.success(IndicatorReading(indicator, Series.Empty))
        }

        override suspend fun personalFinance(refresh: Boolean): Result<PersonalFinance> {
            sheetCalls++
            return Result.failure(SheetUnavailableException(SheetProblem.NOT_SHARED, "share it"))
        }
    }

    private fun history(price: Double) = PriceHistory("^GSPC", ChartRange.DAY, Series.of(listOf(1L to price, 2L to price)), null, 0)

    @Test
    fun theDrawersTeaserLoadsQuotesAndTheSheetButNotTheEconomy() = runTest(dispatcher) {
        val repo = FakeRepository()
        val vm = FinanceViewModel(repo, clock)
        vm.setActive(true, full = false)
        runCurrent()
        assertTrue(repo.quoteCalls >= 1)
        assertEquals(1, repo.sheetCalls)
        assertEquals(0, repo.indicatorCalls)
        assertEquals(SheetProblem.NOT_SHARED, vm.uiState.value.sheetIssue?.problem)

        vm.setActive(true, full = true)
        runCurrent()
        assertEquals(IndicatorCatalog.all.size, repo.indicatorCalls, "the full app asks for every reading, once")
        assertEquals(1, repo.sheetCalls, "and doesn't ask for the sheet again")
        vm.setActive(false)
    }

    @Test
    fun goingInactiveStopsThePolling() = runTest(dispatcher) {
        val repo = FakeRepository()
        val vm = FinanceViewModel(repo, clock)
        vm.setActive(true)
        runCurrent()
        val first = repo.quoteCalls
        // No session is open in these quotes, so it polls every minute.
        advanceTimeBy(3 * 60_000L + 1)
        assertTrue(repo.quoteCalls > first)

        vm.setActive(false)
        runCurrent()
        val stopped = repo.quoteCalls
        advanceTimeBy(10 * 60_000L)
        assertEquals(stopped, repo.quoteCalls)
    }

    @Test
    fun aRefreshEndsRefreshing() = runTest(dispatcher) {
        val repo = FakeRepository()
        val vm = FinanceViewModel(repo, clock)
        vm.refresh()
        assertTrue(vm.uiState.value.refreshing)
        runCurrent()
        assertFalse(vm.uiState.value.refreshing)
        assertEquals(IndicatorCatalog.all.size, repo.indicatorCalls)
    }

    @Test
    fun aSlowOlderChartAnswerDoesNotReplaceANewerOne() = runTest(dispatcher) {
        val repo = FakeRepository()
        val vm = FinanceViewModel(repo, clock)
        val first = CompletableDeferred<PriceHistory>()
        val older = CompletableDeferred<PriceHistory>()
        val newer = CompletableDeferred<PriceHistory>()
        repo.histories += listOf(first, older, newer)

        vm.requestHistory("^GSPC", ChartRange.DAY)
        runCurrent()
        first.complete(history(100.0))
        runCurrent()

        vm.requestHistory("^GSPC", ChartRange.DAY)
        runCurrent()
        vm.requestHistory("^GSPC", ChartRange.DAY)
        runCurrent()
        newer.complete(history(300.0))
        runCurrent()
        older.complete(history(200.0))
        runCurrent()

        assertEquals(300.0, vm.uiState.value.chart("^GSPC", ChartRange.DAY)?.history?.series?.lastValue)
    }
}
