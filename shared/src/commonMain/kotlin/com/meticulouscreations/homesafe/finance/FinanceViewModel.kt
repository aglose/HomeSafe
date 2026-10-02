package com.meticulouscreations.homesafe.finance

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meticulouscreations.homesafe.finance.data.FinanceTipLedger
import com.meticulouscreations.homesafe.finance.domain.ChartRange
import com.meticulouscreations.homesafe.finance.domain.EconomyTone
import com.meticulouscreations.homesafe.finance.domain.FinancePreferencesRepository
import com.meticulouscreations.homesafe.finance.domain.FinanceRepository
import com.meticulouscreations.homesafe.finance.domain.Holdings
import com.meticulouscreations.homesafe.finance.domain.Indicator
import com.meticulouscreations.homesafe.finance.domain.IndicatorCatalog
import com.meticulouscreations.homesafe.finance.domain.IndicatorReading
import com.meticulouscreations.homesafe.finance.domain.MarketCatalog
import com.meticulouscreations.homesafe.finance.domain.MarketSymbol
import com.meticulouscreations.homesafe.finance.domain.PersonalFinance
import com.meticulouscreations.homesafe.finance.domain.Position
import com.meticulouscreations.homesafe.finance.domain.PriceHistory
import com.meticulouscreations.homesafe.finance.domain.Quote
import com.meticulouscreations.homesafe.finance.domain.Series
import com.meticulouscreations.homesafe.finance.domain.SheetProblem
import com.meticulouscreations.homesafe.finance.domain.SheetUnavailableException
import com.meticulouscreations.homesafe.finance.domain.StressScore
import com.meticulouscreations.homesafe.finance.domain.SymbolMatch
import com.meticulouscreations.homesafe.finance.domain.WatchEntry
import com.meticulouscreations.homesafe.finance.domain.WatchedSymbol
import com.meticulouscreations.homesafe.finance.domain.WatchlistRepository
import com.meticulouscreations.homesafe.finance.domain.YieldCurve
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.CancellationException
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

/** The add-a-symbol search: what was typed, and what Yahoo matched it with. */
@Immutable
data class SymbolSearch(val query: String = "", val results: List<SymbolMatch> = emptyList(), val loading: Boolean = false, val error: String? = null)

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
    /** The "New to this?" tip is up: it comes up on an install's first few openings of the app (see [FinanceViewModel.onAppOpened]). */
    val explainTipVisible: Boolean = false,
    /** Symbols followed from the app, oldest first (see [WatchedSymbol]). */
    val watched: List<WatchedSymbol> = emptyList(),
    val search: SymbolSearch = SymbolSearch(),
    /** How the Economy and Risk tabs talk about the readings; chosen in Settings or on the Economy tab. */
    val tone: EconomyTone = EconomyTone.DEFAULT,
) {
    /** The tickers the budget sheet names. */
    val sheetSymbols: List<String> get() = finance?.watchlist.orEmpty()

    /**
     * The watchlist: the sheet's tickers in its order, then those added in the app, each with any
     * position held; the suggestions when there are neither. A position entered against a sheet
     * ticker rides on the sheet's row.
     */
    val watchEntries: List<WatchEntry>
        get() {
            val sheet = sheetSymbols
            val byApp = watched.associateBy { it.symbol }
            val entries = sheet.map { WatchEntry(it, inSheet = true, addedInApp = false, position = byApp[it]?.position) } +
                watched.filter { it.symbol !in sheet }.map { WatchEntry(it.symbol, inSheet = false, addedInApp = true, position = it.position) }
            return entries.ifEmpty { MarketCatalog.defaultWatchlist.map { WatchEntry(it, inSheet = false, addedInApp = false, position = null) } }
        }

    val watchlist: List<String> get() = watchEntries.map { it.symbol }

    /** What's held across the watchlist at the latest prices, a total per currency; empty with no positions. */
    val holdings: List<Holdings> get() = Holdings.of(watchEntries, quotes)

    fun watchedSymbol(symbol: String): WatchedSymbol? = watched.firstOrNull { it.symbol == symbol }

    /** How to show [symbol], from its quote, else from what was saved when it was added (see [MarketCatalog.lookup]). */
    fun meta(symbol: String): MarketSymbol = watchedSymbol(symbol).let { w -> MarketCatalog.lookup(symbol, quotes[symbol], w?.name, w?.kind) }

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
    private val watchlistRepository: WatchlistRepository,
    private val clock: Clock,
    private val tips: FinanceTipLedger,
    private val preferences: FinancePreferencesRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FinanceUiState())
    val uiState: StateFlow<FinanceUiState> = _uiState.asStateFlow()

    init {
        // A preference on the device, not something polled: followed for as long as the app lives,
        // so a change made in Settings is in place when Finance next opens.
        viewModelScope.launch { preferences.observeEconomyTone().collect { tone -> _uiState.update { it.copy(tone = tone) } } }
    }

    fun setTone(tone: EconomyTone) {
        _uiState.update { it.copy(tone = tone) }
        viewModelScope.launch { preferences.setEconomyTone(tone) }
    }

    private var pollJob: Job? = null
    private var sheetPollJob: Job? = null
    private val chartTickets = HashMap<String, Int>()
    private var economyStarted = false
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            watchlistRepository.observe().collect { list ->
                val before = _uiState.value.watchlist
                _uiState.update { it.copy(watched = list) }
                // A symbol just added wants its quote now, not at the next poll; in its own job, so
                // a slow Yahoo never holds up the next change to the list.
                if (pollJob?.isActive == true && _uiState.value.watchlist.any { it !in before }) launch { fetchQuotes(maxAgeMillis = QUOTE_POLL_OPEN_MS) }
            }
        }
    }

    /** The finance app came up ([full]) or only the drawer's teaser did; false when both are gone. */
    fun setActive(active: Boolean, full: Boolean = true) {
        if (!active) {
            pollJob?.cancel()
            pollJob = null
            sheetPollJob?.cancel()
            sheetPollJob = null
            return
        }
        if (pollJob?.isActive != true) pollJob = viewModelScope.launch { pollQuotes() }
        if (sheetPollJob?.isActive != true) {
            // Each time the drawer or the app comes up, the sheet is asked for again (the
            // repository answers from its cache for a couple of minutes), so an edit made since
            // shows without a pull to refresh; then every couple of minutes while it stays up. The
            // sheet decides the watchlist, so even the teaser asks. Its own loop, so a slow read of
            // the sheet never holds up the quotes.
            sheetPollJob = viewModelScope.launch { pollSheet() }
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
     * The finance app came up. The "New to this?" tip shows on the first [EXPLAIN_TIP_SHOWINGS]
     * openings on an install and never after, put away or not: by then the ⓘ have been seen.
     * Called once per opening, not on a recomposition or a configuration change.
     */
    fun onAppOpened() {
        val show = tips.timesShown(EXPLAIN_TIP) < EXPLAIN_TIP_SHOWINGS
        if (show) tips.recordShown(EXPLAIN_TIP)
        _uiState.update { it.copy(explainTipVisible = show) }
    }

    /** The tip was put away for this opening; it still counts as one of its showings. */
    fun dismissExplainTip() = _uiState.update { it.copy(explainTipVisible = false) }

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

    /**
     * Looks [query] up as it's typed: each keystroke replaces the last one's search, and only goes
     * to Yahoo once typing pauses.
     */
    fun searchSymbols(query: String) {
        searchJob?.cancel()
        val q = query.trim()
        _uiState.update { s ->
            s.copy(search = SymbolSearch(query, results = if (q.isEmpty()) emptyList() else s.search.results, loading = q.isNotEmpty()))
        }
        if (q.isEmpty()) return
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            repository.searchSymbols(q)
                .onSuccess { found -> _uiState.update { s -> s.copy(search = s.search.copy(results = found, loading = false, error = null)) } }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    // The last query's matches go: left up, they'd pass for this one's.
                    _uiState.update { s -> s.copy(search = s.search.copy(results = emptyList(), loading = false, error = e.message ?: "Search is unavailable")) }
                }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _uiState.update { it.copy(search = SymbolSearch()) }
    }

    /** Follows [match] from the app. Already followed (or in the sheet), nothing changes. */
    fun addSymbol(match: SymbolMatch) {
        val s = _uiState.value
        if (s.watchedSymbol(match.symbol) != null || match.symbol in s.sheetSymbols) return
        viewModelScope.launch {
            watchlistRepository.save(WatchedSymbol(match.symbol, match.name, match.kind, clock.now().epochSeconds))
        }
    }

    /** Stops following [symbol] from the app, with any position entered for it. The sheet's tickers stay. */
    fun removeSymbol(symbol: String) {
        viewModelScope.launch { watchlistRepository.remove(symbol) }
    }

    /**
     * Records the shares held in [symbol] (null: none). Holding one of the sheet's tickers keeps a
     * row for it in the app, which goes again when the position is cleared; one added in the app
     * stays followed either way.
     */
    fun setPosition(symbol: String, position: Position?) {
        val s = _uiState.value
        val existing = s.watchedSymbol(symbol)
        viewModelScope.launch {
            when {
                position == null && existing == null -> Unit

                position == null && symbol in s.sheetSymbols -> watchlistRepository.remove(symbol)

                else -> {
                    val meta = s.meta(symbol)
                    val base = existing ?: WatchedSymbol(symbol, meta.name, meta.kind, clock.now().epochSeconds)
                    watchlistRepository.save(base.copy(position = position))
                }
            }
        }
    }

    /** Quotes every 15 s while a market is open, a minute when not, while on screen. */
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

    /** The sheet now and then every couple of minutes, each read finishing before the next wait starts. */
    private suspend fun pollSheet() {
        while (currentCoroutineContext().isActive) {
            fetchSheet(refresh = false)
            delay(SHEET_POLL_MS)
        }
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
                // A read cut short because Finance closed isn't a failed sync.
                if (e is CancellationException) throw e
                val issue = (e as? SheetUnavailableException)?.let { SheetIssue(it.problem, it.message.orEmpty(), it.serviceAccount, it.activationUrl) }
                    ?: SheetIssue(SheetProblem.OTHER, e.message ?: "Couldn't read the budget sheet", null, null)
                _uiState.update { it.copy(financeLoading = false, sheetIssue = issue) }
            }
        // The sheet may have named tickers the quotes haven't covered yet.
        if (_uiState.value.watchlist != watchBefore) fetchQuotes(maxAgeMillis = QUOTE_POLL_OPEN_MS)
    }

    override fun onCleared() {
        pollJob?.cancel()
        searchJob?.cancel()
    }

    internal companion object {
        const val EXPLAIN_TIP = "explain_tip"
        const val EXPLAIN_TIP_SHOWINGS = 2
        private const val QUOTE_POLL_OPEN_MS = 15_000L
        private const val QUOTE_POLL_CLOSED_MS = 60_000L
        private const val SHEET_POLL_MS = 120_000L
        private const val SEARCH_DEBOUNCE_MS = 300L
    }
}
