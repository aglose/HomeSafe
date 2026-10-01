package com.meticulouscreations.homesafe.finance.data

import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.finance.domain.ChartHealth
import com.meticulouscreations.homesafe.finance.domain.ChartRange
import com.meticulouscreations.homesafe.finance.domain.FinanceRepository
import com.meticulouscreations.homesafe.finance.domain.Indicator
import com.meticulouscreations.homesafe.finance.domain.IndicatorReading
import com.meticulouscreations.homesafe.finance.domain.PersonalFinance
import com.meticulouscreations.homesafe.finance.domain.PriceHistory
import com.meticulouscreations.homesafe.finance.domain.Quote
import com.meticulouscreations.homesafe.finance.domain.Series
import com.meticulouscreations.homesafe.finance.domain.SheetProblem
import com.meticulouscreations.homesafe.finance.domain.SheetUnavailableException
import com.meticulouscreations.homesafe.finance.domain.Transform
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
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
) : FinanceRepository {

    private val quoteCache = TimedCache<String, Quote>()
    private val unknownSymbols = TimedCache<String, Unit>()
    private val historyCache = TimedCache<Pair<String, ChartRange>, PriceHistory>()
    private val fredCache = TimedCache<String, Series>()
    private var sheet: Pair<TimeSource.Monotonic.ValueTimeMark, PersonalFinance>? = null
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
            list.forEach { quoteCache.put(it.symbol, it) }
            val answered = list.mapTo(HashSet()) { it.symbol }
            pending.filterNot { it in answered }.forEach { unknownSymbols.put(it, Unit) }
        }.map { list ->
            // A pending symbol Yahoo left out has no quote: an old one would pass for a live one.
            val fetched = list.associateBy { it.symbol }
            symbols.mapNotNull { s -> if (s in pending) fetched[s] else quoteCache.get(s, maxAgeMillis) }
        }
    }

    override suspend fun history(symbol: String, range: ChartRange): Result<PriceHistory> {
        val maxAge = if (range == ChartRange.DAY || range == ChartRange.WEEK) 30_000L else 10 * 60_000L
        historyCache.get(symbol to range, maxAge)?.let { return Result.success(it) }
        return historyCache.load(symbol to range) { yahoo.history(symbol, range) }
    }

    override suspend fun fredSeries(seriesId: String, startDate: String): Result<Series> {
        val key = "$seriesId@$startDate"
        fredCache.get(key, FRED_MAX_AGE_MS)?.let { return Result.success(it) }
        return fredCache.load(key) { fredPermits.withPermit { fred.series(seriesId, startDate) } }
    }

    override suspend fun indicator(indicator: Indicator): Result<IndicatorReading> = suspendRunCatching {
        val start = indicator.startDate
        val parts = coroutineScope {
            indicator.fredIds.map { id -> async { fredSeries(id, start).getOrThrow() } }.awaitAll()
        }
        var series = if (indicator.ratioPercent && parts.size == 2) {
            parts[0].combine(parts[1]) { a, b -> if (b == 0.0) null else a / b * 100.0 }
        } else {
            parts.first()
        }
        if (indicator.transform == Transform.YEAR_OVER_YEAR) series = series.yearOverYearPercent()
        if (indicator.scale != 1.0) series = Series(series.times, DoubleArray(series.size) { series.values[it] * indicator.scale })
        IndicatorReading(indicator, series)
    }

    override suspend fun personalFinance(refresh: Boolean): Result<PersonalFinance> = sheetLock.withLock {
        val cached = sheet
        if (!refresh && cached != null && cached.first.elapsedNow().inWholeMilliseconds < SHEET_MAX_AGE_MS) {
            return@withLock Result.success(cached.second)
        }
        val serverUrl = connectionRepository.currentServerUrl.value
            ?: return@withLock Result.failure(SheetUnavailableException(SheetProblem.SIGNED_OUT, "Not connected to the server"))
        val workbook = relay.workbook(serverUrl, refresh).getOrElse { return@withLock Result.failure(it) }
        // Not mapCatching: it would turn the Finance screen closing mid-parse (a cancellation)
        // into a failed read, which would then show as a sync error.
        suspendRunCatching {
            // Dozens of passes over a thousand-row grid: off the main thread.
            withContext(Dispatchers.Default) {
                val grids = workbook.grids()
                val finance = PersonalFinanceParser.parse(workbook.title, workbook.fetchedAt.toLong(), grids, workbook.url)
                val charts = SheetChartReader.read(workbook.charts, grids, workbook.url)
                finance.copy(charts = charts, health = finance.health.copy(charts = charts.map { ChartHealth(it.title, it.tab, it.issue) }))
            }
        }.onSuccess { sheet = TimeSource.Monotonic.markNow() to it }
    }

    private companion object {
        const val FRED_CONCURRENCY = 6
        const val FRED_MAX_AGE_MS = 3 * 60 * 60_000L
        const val SHEET_MAX_AGE_MS = 2 * 60_000L
        const val UNKNOWN_SYMBOL_MS = 10 * 60_000L
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
