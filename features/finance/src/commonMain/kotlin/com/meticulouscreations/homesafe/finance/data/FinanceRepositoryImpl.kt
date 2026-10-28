package com.meticulouscreations.homesafe.finance.data

import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.finance.domain.BalanceAge
import com.meticulouscreations.homesafe.finance.domain.BalanceProblem
import com.meticulouscreations.homesafe.finance.domain.BalanceUpdateException
import com.meticulouscreations.homesafe.finance.domain.ChartHealth
import com.meticulouscreations.homesafe.finance.domain.ChartRange
import com.meticulouscreations.homesafe.finance.domain.FinanceAccess
import com.meticulouscreations.homesafe.finance.domain.FinanceRepository
import com.meticulouscreations.homesafe.finance.domain.Indicator
import com.meticulouscreations.homesafe.finance.domain.IndicatorReading
import com.meticulouscreations.homesafe.finance.domain.PersonalFinance
import com.meticulouscreations.homesafe.finance.domain.PriceHistory
import com.meticulouscreations.homesafe.finance.domain.Quote
import com.meticulouscreations.homesafe.finance.domain.Series
import com.meticulouscreations.homesafe.finance.domain.SheetProblem
import com.meticulouscreations.homesafe.finance.domain.SheetUnavailableException
import com.meticulouscreations.homesafe.finance.domain.SymbolMatch
import com.meticulouscreations.homesafe.finance.domain.Transform
import com.meticulouscreations.homesafe.finance.domain.TypedBalance
import com.meticulouscreations.homesafe.network.isTransportFailure
import com.meticulouscreations.homesafe.text.UiText
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.fin_data_error_not_connected
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class FinanceRepositoryImpl(
    private val yahoo: YahooFinanceApi,
    private val fred: FredApi,
    private val relay: FinanceRelayApi,
    private val connectionRepository: ConnectionRepository,
    private val saved: FinanceSaved,
    private val access: FinanceAccess,
) : FinanceRepository {

    private val quoteCache = TimedCache<String, Quote>()
    private val unknownSymbols = TimedCache<String, Unit>()

    /** The symbols whose quote was written to the device in the last while: they are polled far more often than a kept copy needs to be. */
    private val quotesKept = TimedCache<String, Unit>()
    private val historyCache = TimedCache<Pair<String, ChartRange>, PriceHistory>()
    private val fredCache = TimedCache<String, Series>()
    private val searchCache = TimedCache<String, List<SymbolMatch>>()
    private var sheet: Pair<TimeSource.Monotonic.ValueTimeMark, PersonalFinance>? = null

    /** The sheet as last parsed from what the device kept, so each look at it without the server doesn't parse it again. */
    private var keptSheet: PersonalFinance? = null
    private var keptSheetOwner: String? = null
    private val sheetLock = Mutex()

    // FRED serves a page's worth of series at once happily, but not forty; a handful in flight
    // keeps the Risk tab filling in quickly without tripping its rate limit.
    private val fredPermits = Semaphore(FRED_CONCURRENCY)

    override suspend fun quotes(symbols: List<String>, maxAgeMillis: Long): Result<List<Quote>> {
        // A symbol Yahoo answered nothing for (a typo in the sheet's watchlist) counts as fresh
        // for as long, so it doesn't send every call back to Yahoo for all of them.
        val pending = symbols.filter { quoteCache.get(it, maxAgeMillis) == null && unknownSymbols.get(it, UNKNOWN_SYMBOL_MS) == null }
        if (pending.isEmpty()) return Result.success(symbols.mapNotNull { quoteCache.get(it, maxAgeMillis) })
        // Only the stale ones go to Yahoo; the rest are answered from the cache.
        return yahoo.quotes(pending).onSuccess { list ->
            list.forEach {
                quoteCache.put(it.symbol, it)
                if (quotesKept.get(it.symbol, QUOTE_KEEP_EVERY_MS) == null) {
                    quotesKept.put(it.symbol, Unit)
                    saved.write(FinanceSaved.quote(it.symbol), Quote.serializer(), it)
                }
            }
            val answered = list.mapTo(HashSet()) { it.symbol }
            pending.filterNot { it in answered }.forEach { unknownSymbols.put(it, Unit) }
        }.map { list ->
            // A pending symbol Yahoo left out has no quote: an old one would pass for a live one.
            val fetched = list.associateBy { it.symbol }
            symbols.mapNotNull { s -> if (s in pending) fetched[s] else quoteCache.get(s, maxAgeMillis) }
        }
    }

    override suspend fun savedQuotes(symbols: List<String>): List<Quote> =
        symbols.mapNotNull { saved.read(FinanceSaved.quote(it), Quote.serializer()) }

    override suspend fun history(symbol: String, range: ChartRange): Result<PriceHistory> {
        val maxAge = if (range == ChartRange.DAY || range == ChartRange.WEEK) 30_000L else 10 * 60_000L
        historyCache.get(symbol to range, maxAge)?.let { return Result.success(it) }
        return historyCache.load(symbol to range) {
            yahoo.history(symbol, range).onSuccess { saved.write(FinanceSaved.history(symbol, range), PriceHistory.serializer(), it) }
        }
    }

    override suspend fun savedHistory(symbol: String, range: ChartRange): PriceHistory? =
        saved.read(FinanceSaved.history(symbol, range), PriceHistory.serializer())

    override suspend fun searchSymbols(query: String): Result<List<SymbolMatch>> {
        val key = query.trim().lowercase()
        if (key.isEmpty()) return Result.success(emptyList())
        searchCache.get(key, SEARCH_MAX_AGE_MS)?.let { return Result.success(it) }
        return searchCache.load(key) { yahoo.search(query.trim()) }
    }

    override suspend fun fredSeries(seriesId: String, startDate: String): Result<Series> {
        val key = "$seriesId@$startDate"
        fredCache.get(key, FRED_MAX_AGE_MS)?.let { return Result.success(it) }
        val savedKey = FinanceSaved.fred(seriesId, startDate)
        val fetched = fredCache.load(key) {
            fredPermits.withPermit { fred.series(seriesId, startDate) }.onSuccess { saved.write(savedKey, Series.serializer(), it) }
        }
        if (fetched.isSuccess) return fetched
        // These move monthly, and every reading is shown with its date: the series as it was last
        // fetched says the same thing a fresh one would on most days. Not put in the cache above,
        // so the next ask goes to FRED again.
        return saved.read(savedKey, Series.serializer())?.let { Result.success(it) } ?: fetched
    }

    override suspend fun indicator(indicator: Indicator): Result<IndicatorReading> = suspendRunCatching {
        val start = indicator.startDate
        val parts = coroutineScope {
            indicator.fredIds.map { id -> async { fredSeries(id, start).getOrThrow() } }.awaitAll()
        }
        var series = if (parts.size == 2) indicator.combine.apply(parts[0], parts[1]) else parts.first()
        if (indicator.transform == Transform.YEAR_OVER_YEAR) series = series.yearOverYearPercent()
        if (indicator.scale != 1.0) series = Series(series.times, DoubleArray(series.size) { series.values[it] * indicator.scale })
        IndicatorReading(indicator, series)
    }

    override suspend fun personalFinance(refresh: Boolean): Result<PersonalFinance> = sheetLock.withLock {
        // No server to vouch for whoever is holding the phone, and they haven't shown it's theirs:
        // not from memory either.
        if (access.locked.value) return@withLock Result.failure(notConnected())
        val cached = sheet
        if (!refresh && cached != null && keptSheetOwner == owner() && cached.first.elapsedNow().inWholeMilliseconds < SHEET_MAX_AGE_MS) {
            return@withLock Result.success(cached.second)
        }
        val serverUrl = connectionRepository.currentServerUrl.value
        // Known to be out of reach: what the device kept, rather than half a minute waiting on a
        // request nothing will answer.
        if (serverUrl == null || !access.online.value) kept()?.let { return@withLock Result.success(it) }
        if (serverUrl == null) return@withLock Result.failure(notConnected())
        val workbook = relay.workbook(serverUrl, refresh).getOrElse { e ->
            val problem = (e as? SheetUnavailableException)?.problem
            when {
                // The server answered, and it won't show this account the sheet: the device doesn't keep it for it.
                problem == SheetProblem.SIGNED_OUT || problem == SheetProblem.NOT_ALLOWED -> forget()

                e.isTransportFailure() -> {
                    // Nothing answered. If the server is gone, that locks the app before the kept sheet is reached for.
                    access.serverUnanswered()
                    if (access.locked.value) return@withLock Result.failure(notConnected())
                    kept()?.let { return@withLock Result.success(it) }
                }
            }
            return@withLock Result.failure(e)
        }
        // Not mapCatching: it would turn the Finance screen closing mid-parse (a cancellation)
        // into a failed read, which would then show as a sync error.
        suspendRunCatching { parse(workbook) }.onSuccess {
            sheet = TimeSource.Monotonic.markNow() to it
            keptSheet = it
            keptSheetOwner = owner()
            owner()?.let { owner -> saved.writePrivate(owner, FinanceSaved.SHEET, RelayWorkbook.serializer(), workbook) }
        }
    }

    override suspend fun updateBalance(balance: TypedBalance, amount: Double): Result<BalanceAge> {
        // A change needs the server: it is the one that writes the sheet.
        val serverUrl = connectionRepository.currentServerUrl.value
        if (serverUrl == null || !access.online.value) {
            return Result.failure(BalanceUpdateException(BalanceProblem.NOT_CONNECTED, UiText.of(Res.string.fin_data_error_not_connected), technical = "Not connected"))
        }
        return relay.setValue(serverUrl, balance.cell, amount).onSuccess {
            // The sheet in memory has the old number: the next read goes to the relay for the new one.
            sheetLock.withLock { sheet = null }
        }
    }

    // Dozens of passes over a thousand-row grid: off the main thread.
    private suspend fun parse(workbook: RelayWorkbook): PersonalFinance = withContext(Dispatchers.Default) {
        val grids = workbook.grids()
        val finance = PersonalFinanceParser.parse(workbook.title, workbook.fetchedAt.toLong(), grids, workbook.url)
        val charts = SheetChartReader.read(workbook.charts, grids, workbook.url)
        finance.copy(
            charts = charts,
            health = finance.health.copy(charts = charts.map { ChartHealth(it.title, it.tab, it.issue) }),
            balanceAges = workbook.balanceAges(),
        )
    }

    /** The sheet as the device kept it, parsed afresh the first time it is wanted. Its own `fetchedAt` says how old it is. */
    private suspend fun kept(): PersonalFinance? {
        val owner = owner()
        // Parsed for one server, it isn't another's: a sign-in elsewhere since starts again from the device.
        if (owner != keptSheetOwner) keptSheet = null
        keptSheet?.let { return it }
        val workbook = saved.readPrivate(owner, FinanceSaved.SHEET, RelayWorkbook.serializer()) ?: return null
        return suspendRunCatching { parse(workbook) }.getOrNull()?.also {
            keptSheet = it
            keptSheetOwner = owner
        }
    }

    /** The server signed in to, by the address that names it whichever route is in use; null with none. */
    private fun owner(): String? = connectionRepository.activeConnection.value?.serverUrl

    private suspend fun forget() {
        sheet = null
        keptSheet = null
        saved.forgetPrivate()
    }

    private fun notConnected() = SheetUnavailableException(SheetProblem.SIGNED_OUT, UiText.of(Res.string.fin_data_error_not_connected), technical = "Not connected")

    private companion object {
        const val FRED_CONCURRENCY = 6
        const val FRED_MAX_AGE_MS = 3 * 60 * 60_000L
        const val SHEET_MAX_AGE_MS = 2 * 60_000L
        const val UNKNOWN_SYMBOL_MS = 10 * 60_000L
        const val SEARCH_MAX_AGE_MS = 10 * 60_000L
        const val QUOTE_KEEP_EVERY_MS = 2 * 60_000L
    }
}

/**
 * A small in-memory cache that also shares in-flight loads: a second caller asking for a key
 * already on its way waits for that answer instead of fetching it again (the Markets and Risk
 * tabs ask for overlapping series as they open).
 */
internal class TimedCache<K, V> {
    private val entries = HashMap<K, Pair<TimeSource.Monotonic.ValueTimeMark, V>>()
    private val inFlight = HashMap<K, Deferred<Result<V>>>()
    private val lock = Mutex()

    fun get(key: K, maxAgeMillis: Long): V? {
        val (at, value) = entries[key] ?: return null
        return if (at.elapsedNow().inWholeMilliseconds <= maxAgeMillis) value else null
    }

    fun put(key: K, value: V) {
        entries[key] = TimeSource.Monotonic.markNow() to value
    }

    suspend fun load(key: K, fetch: suspend () -> Result<V>): Result<V> {
        val (deferred, owner) = lock.withLock {
            inFlight[key]?.let { it to false } ?: CompletableDeferred<Result<V>>().also { inFlight[key] = it }.let { it to true }
        }
        if (!owner) {
            return try {
                deferred.await()
            } catch (e: CancellationException) {
                // The fetch we were sharing was cancelled with its caller, not with us: go again.
                currentCoroutineContext().ensureActive()
                load(key, fetch)
            }
        }
        val own = deferred as CompletableDeferred<Result<V>>
        try {
            val result = suspendRunCatching { fetch().getOrThrow() }
            result.onSuccess { put(key, it) }
            own.complete(result)
            return result
        } catch (e: CancellationException) {
            // The fetching caller went away; whoever was waiting on it fetches for themselves.
            own.cancel(e)
            throw e
        } finally {
            withContext(NonCancellable) { lock.withLock { inFlight.remove(key) } }
        }
    }
}
