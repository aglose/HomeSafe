package com.meticulouscreations.homesafe.finance

import com.meticulouscreations.homesafe.finance.data.InMemoryFinanceTipLedger
import com.meticulouscreations.homesafe.finance.data.PersonalFinanceParser
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
import kotlin.test.assertNull
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

        /** What each read of the sheet answers; by default, not shared. */
        var sheet: () -> Result<PersonalFinance> = { Result.failure(SheetUnavailableException(SheetProblem.NOT_SHARED, "share it")) }

        /** When set, every read of the sheet waits on it: a relay that's slow to answer. */
        var slowSheet: CompletableDeferred<Result<PersonalFinance>>? = null

        override suspend fun personalFinance(refresh: Boolean): Result<PersonalFinance> {
            sheetCalls++
            slowSheet?.let { return it.await() }
            return sheet()
        }
    }

    private fun history(price: Double) = PriceHistory("^GSPC", ChartRange.DAY, Series.of(listOf(1L to price, 2L to price)), null, 0)

    @Test
    fun theDrawersTeaserLoadsQuotesAndTheSheetButNotTheEconomy() = runTest(dispatcher) {
        val repo = FakeRepository()
        val vm = FinanceViewModel(repo, clock, InMemoryFinanceTipLedger())
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
    fun theSheetIsAskedForOnEveryOpeningAndEveryCoupleOfMinutesWhileOpen() = runTest(dispatcher) {
        val repo = FakeRepository()
        val ticking = object : Clock {
            override fun now(): Instant = Instant.fromEpochSeconds(1_790_000_000 + testScheduler.currentTime / 1000)
        }
        val vm = FinanceViewModel(repo, ticking, InMemoryFinanceTipLedger())
        vm.setActive(true)
        runCurrent()
        assertEquals(1, repo.sheetCalls)
        // Every two minutes while open, on its own loop.
        advanceTimeBy(3 * 60_000L + 1)
        assertEquals(2, repo.sheetCalls, "asked again while open")

        vm.setActive(false)
        runCurrent()
        vm.setActive(true)
        runCurrent()
        assertEquals(3, repo.sheetCalls, "and again on opening")
        vm.setActive(false)
    }

    @Test
    fun aSlowSheetReadDoesNotHoldUpTheQuotes() = runTest(dispatcher) {
        val repo = FakeRepository()
        val never = CompletableDeferred<Result<PersonalFinance>>()
        repo.slowSheet = never
        val vm = FinanceViewModel(repo, clock, InMemoryFinanceTipLedger())
        vm.setActive(true)
        runCurrent()
        val first = repo.quoteCalls
        advanceTimeBy(3 * 60_000L + 1)
        assertTrue(repo.quoteCalls >= first + 3, "quotes kept polling while the sheet hung")
        assertEquals(1, repo.sheetCalls, "and the sheet isn't asked again over a read still in flight")
        vm.setActive(false)
    }

    @Test
    fun closingFinanceMidReadIsNotASyncError() = runTest(dispatcher) {
        val repo = FakeRepository()
        repo.slowSheet = CompletableDeferred()
        val vm = FinanceViewModel(repo, clock, InMemoryFinanceTipLedger())
        vm.setActive(true)
        runCurrent()
        vm.setActive(false)
        runCurrent()
        assertNull(vm.uiState.value.sheetIssue)
    }

    @Test
    fun aFailedSyncKeepsTheLastReadAndSaysSo() = runTest(dispatcher) {
        val repo = FakeRepository()
        val read = PersonalFinanceParser.parse("Budget", 1_790_000_000, emptyList())
        repo.sheet = { Result.success(read) }
        val vm = FinanceViewModel(repo, clock, InMemoryFinanceTipLedger())
        vm.retrySheet()
        runCurrent()
        assertEquals(read, vm.uiState.value.finance)
        assertNull(vm.uiState.value.sheetIssue)

        repo.sheet = { Result.failure(SheetUnavailableException(SheetProblem.OTHER, "Google is down")) }
        vm.retrySheet()
        runCurrent()
        assertEquals(read, vm.uiState.value.finance, "the last read stays on screen")
        assertEquals("Google is down", vm.uiState.value.sheetIssue?.message)

        repo.sheet = { Result.success(read) }
        vm.retrySheet()
        runCurrent()
        assertNull(vm.uiState.value.sheetIssue, "and the warning goes once a sync works")
    }

    @Test
    fun goingInactiveStopsThePolling() = runTest(dispatcher) {
        val repo = FakeRepository()
        val vm = FinanceViewModel(repo, clock, InMemoryFinanceTipLedger())
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
        val vm = FinanceViewModel(repo, clock, InMemoryFinanceTipLedger())
        vm.refresh()
        assertTrue(vm.uiState.value.refreshing)
        runCurrent()
        assertFalse(vm.uiState.value.refreshing)
        assertEquals(IndicatorCatalog.all.size, repo.indicatorCalls)
    }

    @Test
    fun aSlowOlderChartAnswerDoesNotReplaceANewerOne() = runTest(dispatcher) {
        val repo = FakeRepository()
        val vm = FinanceViewModel(repo, clock, InMemoryFinanceTipLedger())
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

    @Test
    fun theNewToThisTipComesUpOnTheFirstTwoOpeningsOnlyEvenWhenPutAway() {
        val ledger = InMemoryFinanceTipLedger()
        val first = FinanceViewModel(FakeRepository(), clock, ledger)
        assertFalse(first.uiState.value.explainTipVisible, "nothing shows before the app is opened")

        first.onAppOpened()
        assertTrue(first.uiState.value.explainTipVisible)
        first.dismissExplainTip()
        assertFalse(first.uiState.value.explainTipVisible)

        // A later launch: a new view model over the same install's ledger.
        val second = FinanceViewModel(FakeRepository(), clock, ledger)
        second.onAppOpened()
        assertTrue(second.uiState.value.explainTipVisible, "put away the first time, it still has its second showing")

        second.onAppOpened()
        assertFalse(second.uiState.value.explainTipVisible, "the third opening is past the limit")
        assertEquals(FinanceViewModel.EXPLAIN_TIP_SHOWINGS, ledger.timesShown(FinanceViewModel.EXPLAIN_TIP))
    }
}
