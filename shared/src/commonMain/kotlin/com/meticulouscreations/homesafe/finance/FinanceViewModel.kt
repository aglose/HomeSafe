package com.meticulouscreations.homesafe.finance

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meticulouscreations.homesafe.finance.domain.ChartRange
import com.meticulouscreations.homesafe.finance.domain.FinanceRepository
import com.meticulouscreations.homesafe.finance.domain.Indicator
import com.meticulouscreations.homesafe.finance.domain.IndicatorCatalog
import com.meticulouscreations.homesafe.finance.domain.IndicatorReading
import com.meticulouscreations.homesafe.finance.domain.MarketCatalog
import com.meticulouscreations.homesafe.finance.domain.PersonalFinance
import com.meticulouscreations.homesafe.finance.domain.PriceHistory
import com.meticulouscreations.homesafe.finance.domain.Quote
import com.meticulouscreations.homesafe.finance.domain.Series
import com.meticulouscreations.homesafe.finance.domain.SheetProblem
import com.meticulouscreations.homesafe.finance.domain.SheetUnavailableException
import com.meticulouscreations.homesafe.finance.domain.StressScore
import com.meticulouscreations.homesafe.finance.domain.YieldCurve
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Clock

/** Why the sheet isn't showing, flattened from [SheetUnavailableException] for the UI. */
@Immutable
data class SheetIssue(val problem: SheetProblem, val message: String, val serviceAccount: String?, val activationUrl: String?)

/** A price history being fetched for one symbol and range. */
@Immutable
data class ChartLoad(val history: PriceHistory? = null, val loading: Boolean = true, val error: String? = null)

@Immutable
data class FinanceUiState(
    val quotes: Map<String, Quote> = emptyMap(),
    val quotesError: String? = null,
    val quotesUpdatedEpochSeconds: Long? = null,
    /** Keyed by [chartKey]. */
    val charts: Map<String, ChartLoad> = emptyMap(),
    val readings: Map<String, IndicatorReading> = emptyMap(),
    /** FRED ids whose fetch failed this round. */
    val failedIndicators: Set<String> = emptySet(),
    /** The Treasury curve's tenors' histories, keyed by FRED id. */
    val curve: Map<String, Series> = emptyMap(),
    /** Tenors whose fetch failed this round, so the curve can stop waiting for them. */
    val failedTenors: Set<String> = emptySet(),
    val finance: PersonalFinance? = null,
    val financeLoading: Boolean = true,
    val sheetIssue: SheetIssue? = null,
    val refreshing: Boolean = false,
) {
    val watchlist: List<String>
        get() = finance?.watchlist?.takeIf { it.isNotEmpty() } ?: MarketCatalog.defaultWatchlist

    val stress: StressScore? get() = StressScore.of(readings.values.filter { it.indicator in IndicatorCatalog.radar })

    val economyLoading: Boolean get() = readings.size + failedIndicators.size < IndicatorCatalog.all.size

    /** Every tenor of the curve has answered, one way or the other. */
    val curveSettled: Boolean get() = YieldCurve.tenors.all { it.fredId in curve || it.fredId in failedTenors }

    fun chart(symbol: String, range: ChartRange): ChartLoad? = charts[chartKey(symbol, range)]

    companion object {
        fun chartKey(symbol: String, range: ChartRange) = "$symbol|${range.name}"
    }
}

/**
 * The finance app's state: live quotes (polled while the app is up), price histories on demand,
 * every economic reading the Economy and Risk tabs show, and the household budget sheet.
 *
 * Scoped to the activity, not to a screen, so the drawer's teaser and the full app share what's
 * been loaded (under the iOS 26 host each tab's view controller has its own, but they share the
 * repository's caches); nothing is polled until [setActive] says the app (or the drawer) is on
 * screen.
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class FinanceViewModel(
    private val repository: FinanceRepository,
    private val clock: Clock,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FinanceUiState())
    val uiState: StateFlow<FinanceUiState> = _uiState.asStateFlow()

    private var pollJob: Job? = null
    private val chartTickets = HashMap<String, Int>()
    private var economyStarted = false
    private var sheetStarted = false

    /** The finance app came up ([full]) or only the drawer's teaser did; false when both are gone. */
    fun setActive(active: Boolean, full: Boolean = true) {
        if (!active) {
            pollJob?.cancel()
            pollJob = null
            return
        }
        if (pollJob?.isActive != true) pollJob = viewModelScope.launch { pollQuotes() }
        if (!sheetStarted) {
            // The sheet decides the watchlist, so it's asked for even by the teaser (it's cached).
            sheetStarted = true
            loadSheet(refresh = false)
        }
        if (full && !economyStarted) {
            economyStarted = true
            loadEconomy()
        }
    }

    fun refresh() {
        _uiState.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            val jobs = listOf(
                launch { fetchQuotes(maxAgeMillis = 0) },
                launch { fetchSheet(refresh = true) },
                launch {
                    _uiState.update { it.copy(failedIndicators = emptySet(), failedTenors = emptySet()) }
                    fetchEconomy()
                },
            )
            jobs.forEach { it.join() }
            _uiState.update { it.copy(refreshing = false) }
        }
    }

    fun retrySheet() = loadSheet(refresh = true)

    /**
     * Fetches [symbol]'s history for [range]. Asked whenever a chart comes up or changes range, and
     * the repository answers from its cache while that's fresh, so a chart opened again later
     * shows what it had at once and then catches up.
     */
    fun requestHistory(symbol: String, range: ChartRange) {
        val key = FinanceUiState.chartKey(symbol, range)
        val existing = _uiState.value.charts[key]
        if (existing?.loading == true && existing.history == null) return
        _uiState.update { s -> s.copy(charts = s.charts + (key to (existing?.copy(loading = true, error = null) ?: ChartLoad()))) }
        // Only the newest request for a chart may land: a slow older answer mustn't replace a newer one.
        val ticket = (chartTickets[key] ?: 0) + 1
        chartTickets[key] = ticket
        viewModelScope.launch {
            val result = repository.history(symbol, range)
            if (chartTickets[key] != ticket) return@launch
            result
                .onSuccess { h -> _uiState.update { s -> s.copy(charts = s.charts + (key to ChartLoad(h, loading = false))) } }
                .onFailure { e ->
                    _uiState.update { s ->
                        s.copy(charts = s.charts + (key to ChartLoad(s.charts[key]?.history, loading = false, error = e.message ?: "Couldn't load the chart")))
                    }
                }
        }
    }

    private suspend fun pollQuotes() {
        while (currentCoroutineContext().isActive) {
            fetchQuotes(maxAgeMillis = QUOTE_POLL_OPEN_MS)
            val now = clock.now().epochSeconds
            val anyOpen = _uiState.value.quotes.values.any { !it.symbol.endsWith("-USD") && it.isSessionOpen(now) }
            delay(if (anyOpen) QUOTE_POLL_OPEN_MS else QUOTE_POLL_CLOSED_MS)
        }
    }

    private suspend fun fetchQuotes(maxAgeMillis: Long) {
        val symbols = (MarketCatalog.indices + MarketCatalog.macro).map { it.symbol } + _uiState.value.watchlist
        repository.quotes(symbols.distinct(), maxAgeMillis)
            .onSuccess { list ->
                _uiState.update { s ->
                    s.copy(quotes = s.quotes + list.associateBy { it.symbol }, quotesError = null, quotesUpdatedEpochSeconds = clock.now().epochSeconds)
                }
            }
            .onFailure { e -> _uiState.update { it.copy(quotesError = e.message ?: "Markets are unreachable") } }
    }

    private fun loadEconomy() {
        viewModelScope.launch { fetchEconomy() }
    }

    private suspend fun fetchEconomy() {
        kotlinx.coroutines.coroutineScope {
            // Readings land one by one, so the Risk tab fills in as they arrive.
            IndicatorCatalog.all.forEach { indicator -> launch { fetchIndicator(indicator) } }
            YieldCurve.tenors.forEach { tenor ->
                launch {
                    repository.fredSeries(tenor.fredId, YieldCurve.START_DATE)
                        .onSuccess { series -> _uiState.update { it.copy(curve = it.curve + (tenor.fredId to series), failedTenors = it.failedTenors - tenor.fredId) } }
                        .onFailure { _uiState.update { it.copy(failedTenors = it.failedTenors + tenor.fredId) } }
                }
            }
        }
    }

    private suspend fun fetchIndicator(indicator: Indicator) {
        repository.indicator(indicator)
            .onSuccess { r -> _uiState.update { it.copy(readings = it.readings + (indicator.id to r), failedIndicators = it.failedIndicators - indicator.id) } }
            .onFailure { _uiState.update { it.copy(failedIndicators = it.failedIndicators + indicator.id) } }
    }

    private fun loadSheet(refresh: Boolean) {
        viewModelScope.launch { fetchSheet(refresh) }
    }

    private suspend fun fetchSheet(refresh: Boolean) {
        _uiState.update { it.copy(financeLoading = it.finance == null) }
        val watchBefore = _uiState.value.watchlist
        repository.personalFinance(refresh)
            .onSuccess { f -> _uiState.update { it.copy(finance = f, financeLoading = false, sheetIssue = null) } }
            .onFailure { e ->
                val issue = (e as? SheetUnavailableException)?.let { SheetIssue(it.problem, it.message.orEmpty(), it.serviceAccount, it.activationUrl) }
                    ?: SheetIssue(SheetProblem.OTHER, e.message ?: "Couldn't read the budget sheet", null, null)
                _uiState.update { it.copy(financeLoading = false, sheetIssue = issue) }
            }
        // The sheet may have named tickers the quotes haven't covered yet.
        if (_uiState.value.watchlist != watchBefore) fetchQuotes(maxAgeMillis = QUOTE_POLL_OPEN_MS)
    }

    override fun onCleared() {
        pollJob?.cancel()
    }

    private companion object {
        const val QUOTE_POLL_OPEN_MS = 15_000L
        const val QUOTE_POLL_CLOSED_MS = 60_000L
    }
}
