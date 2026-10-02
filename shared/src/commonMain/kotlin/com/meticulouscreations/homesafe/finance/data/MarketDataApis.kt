package com.meticulouscreations.homesafe.finance.data

import com.meticulouscreations.homesafe.finance.domain.ChartRange
import com.meticulouscreations.homesafe.finance.domain.InstrumentKind
import com.meticulouscreations.homesafe.finance.domain.PriceHistory
import com.meticulouscreations.homesafe.finance.domain.Quote
import com.meticulouscreations.homesafe.finance.domain.Series
import com.meticulouscreations.homesafe.finance.domain.SymbolMatch
import com.meticulouscreations.homesafe.network.FrigateResponseException
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Named
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The client for public market data (Yahoo Finance, FRED). Its own instance rather than the
 * app's shared one, so Frigate's session cookies never ride along to a third party and these
 * hosts' cookies never land in the jar the camera API uses.
 */
@ContributesTo(AppScope::class)
interface FinanceDataProviders {
    @Named(PUBLIC_DATA_CLIENT)
    @SingleIn(AppScope::class)
    @Provides
    fun providePublicDataHttpClient(): HttpClient = HttpClient {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 10_000
        }
        defaultRequest {
            // Yahoo turns away requests that don't look like a browser. (Browsers set their own
            // and ignore this one, which is fine: there it's already a browser.)
            header(HttpHeaders.UserAgent, "Mozilla/5.0 (HomeSafe Finance)")
        }
    }
}

const val PUBLIC_DATA_CLIENT = "publicData"

/** Yahoo Finance's unofficial but long-stable chart endpoints: quotes in bulk, and a symbol's history. */
@Inject
class YahooFinanceApi(@Named(PUBLIC_DATA_CLIENT) private val httpClient: HttpClient) {

    /** The latest quote and today's 5-minute path for each of [symbols] (at most 20 a call). */
    suspend fun quotes(symbols: List<String>): Result<List<Quote>> = suspendRunCatching {
        symbols.chunked(SPARK_MAX).flatMap { chunk ->
            val response = httpClient.get("$BASE/v7/finance/spark") {
                parameter("symbols", chunk.joinToString(","))
                parameter("range", "1d")
                parameter("interval", "5m")
            }
            if (!response.status.isSuccess()) throw FrigateResponseException("Yahoo answered ${response.status}")
            response.body<SparkEnvelope>().spark.result.mapNotNull { r ->
                r.response.firstOrNull()?.toQuote(r.symbol)
            }
        }
    }

    suspend fun history(symbol: String, range: ChartRange): Result<PriceHistory> = suspendRunCatching {
        val response = httpClient.get("$BASE/v8/finance/chart/${symbol.encodeURLPathPart()}") {
            if (range == ChartRange.MAX) {
                // By dates: `range=max` comes back thinned to monthly or quarterly bars (see ChartRange).
                parameter("period1", EARLIEST)
                parameter("period2", LATEST)
            } else {
                parameter("range", range.yahooRange)
            }
            parameter("interval", range.yahooInterval)
            parameter("includePrePost", "false")
        }
        if (!response.status.isSuccess()) throw FrigateResponseException("Yahoo answered ${response.status}")
        val result = response.body<ChartEnvelope>().chart.result?.firstOrNull()
            ?: throw FrigateResponseException("Yahoo had no chart for $symbol")
        // A whole history's first bar has nothing before it, so there's no close to measure from.
        val baseline = when (range) {
            ChartRange.DAY -> result.meta.previousClose ?: result.meta.chartPreviousClose
            ChartRange.MAX -> null
            else -> result.meta.chartPreviousClose
        }
        val (high, highAt) = result.highest()
        PriceHistory(symbol, range, result.series(), baseline, result.meta.gmtoffset ?: 0, high, highAt)
    }

    /**
     * Tickers matching [query] — a ticker, a company or a fund's name — that the app can quote:
     * stocks, ETFs, mutual funds, crypto, indices, futures and currencies, best match first.
     */
    suspend fun search(query: String): Result<List<SymbolMatch>> = suspendRunCatching {
        val response = httpClient.get("$BASE/v1/finance/search") {
            parameter("q", query)
            parameter("quotesCount", SEARCH_MAX)
            parameter("newsCount", 0)
            parameter("listsCount", 0)
        }
        if (!response.status.isSuccess()) throw FrigateResponseException("Yahoo answered ${response.status}")
        response.body<SearchEnvelope>().quotes.mapNotNull { it.toMatch() }
    }

    private companion object {
        const val BASE = "https://query1.finance.yahoo.com"
        const val SPARK_MAX = 20
        const val SEARCH_MAX = 12

        // 1900 to 2286: before any history Yahoo has, and after now for long enough.
        const val EARLIEST = -2_208_988_800L
        const val LATEST = 9_999_999_999L
    }
}

/** FRED's keyless CSV download (the one behind every chart on fred.stlouisfed.org). */
@Inject
class FredApi(@Named(PUBLIC_DATA_CLIENT) private val httpClient: HttpClient) {

    /** [seriesId]'s observations from [startDate] (yyyy-MM-dd) on. Missing observations are skipped. */
    suspend fun series(seriesId: String, startDate: String): Result<Series> = suspendRunCatching {
        val response = httpClient.get("https://fred.stlouisfed.org/graph/fredgraph.csv") {
            parameter("id", seriesId)
            parameter("cosd", startDate)
        }
        if (!response.status.isSuccess()) throw FrigateResponseException("FRED answered ${response.status} for $seriesId")
        parseCsv(response.bodyAsText())
    }

    companion object {
        /** "observation_date,ID\n2026-08-01,334.131\n…" → a series in epoch seconds at each date's UTC midnight. */
        fun parseCsv(csv: String): Series {
            val times = ArrayList<Long>()
            val values = ArrayList<Double>()
            csv.lineSequence().drop(1).forEach { line ->
                val comma = line.indexOf(',')
                if (comma <= 0) return@forEach
                val value = line.substring(comma + 1).trim().toDoubleOrNull() ?: return@forEach
                val date = runCatching { LocalDate.parse(line.substring(0, comma).trim()) }.getOrNull() ?: return@forEach
                times += date.toEpochDays() * Series.DAY_SECONDS
                values += value
            }
            return Series(times.toLongArray(), values.toDoubleArray())
        }
    }
}

@Serializable
private data class SearchEnvelope(val quotes: List<SearchQuote> = emptyList())

@Serializable
private data class SearchQuote(
    val symbol: String? = null,
    val shortname: String? = null,
    val longname: String? = null,
    val quoteType: String? = null,
    val typeDisp: String? = null,
    val exchDisp: String? = null,
    // Yahoo mixes in a few things it can't chart (private companies from its news partners).
    val isYahooFinance: Boolean = true,
) {
    fun toMatch(): SymbolMatch? {
        val s = symbol?.takeIf { it.isNotBlank() } ?: return null
        val kind = InstrumentKind.fromYahoo(quoteType) ?: return null
        if (!isYahooFinance) return null
        return SymbolMatch(
            symbol = s,
            name = longname ?: shortname ?: s,
            kind = kind,
            typeLabel = typeDisp ?: quoteType.orEmpty().lowercase().replaceFirstChar { it.uppercase() },
            exchange = exchDisp,
        )
    }
}

@Serializable
private data class SparkEnvelope(val spark: SparkBody)

@Serializable
private data class SparkBody(val result: List<SparkResult> = emptyList())

@Serializable
private data class SparkResult(val symbol: String, val response: List<ChartResult> = emptyList())

@Serializable
private data class ChartEnvelope(val chart: ChartBody)

@Serializable
private data class ChartBody(val result: List<ChartResult>? = null)

@Serializable
private data class ChartResult(
    val meta: ChartMeta,
    val timestamp: List<Long>? = null,
    val indicators: Indicators = Indicators(),
) {
    fun series(): Series {
        val ts = timestamp.orEmpty()
        val closes = indicators.quote.firstOrNull()?.close.orEmpty()
        val n = minOf(ts.size, closes.size)
        val t = ArrayList<Long>(n)
        val v = ArrayList<Double>(n)
        for (i in 0 until n) {
            val c = closes[i] ?: continue
            t += ts[i]
            v += c
        }
        return Series(t.toLongArray(), v.toDoubleArray())
    }

    /** The highest bar high in the chart and when, or nulls when Yahoo sent no highs. */
    fun highest(): Pair<Double?, Long?> {
        val ts = timestamp.orEmpty()
        val highs = indicators.quote.firstOrNull()?.high.orEmpty()
        var best: Double? = null
        var at: Long? = null
        for (i in 0 until minOf(ts.size, highs.size)) {
            val h = highs[i] ?: continue
            if (best == null || h > best) {
                best = h
                at = ts[i]
            }
        }
        return best to at
    }

    fun toQuote(symbol: String): Quote? {
        val price = meta.regularMarketPrice ?: return null
        return Quote(
            symbol = symbol,
            price = price,
            previousClose = meta.previousClose ?: meta.chartPreviousClose ?: price,
            dayHigh = meta.regularMarketDayHigh,
            dayLow = meta.regularMarketDayLow,
            fiftyTwoWeekHigh = meta.fiftyTwoWeekHigh,
            fiftyTwoWeekLow = meta.fiftyTwoWeekLow,
            volume = meta.regularMarketVolume,
            marketTimeEpochSeconds = meta.regularMarketTime ?: 0,
            gmtOffsetSeconds = meta.gmtoffset ?: 0,
            sessionStartEpochSeconds = meta.currentTradingPeriod?.regular?.start,
            sessionEndEpochSeconds = meta.currentTradingPeriod?.regular?.end,
            intraday = series(),
            name = meta.longName ?: meta.shortName,
            instrumentType = meta.instrumentType,
        )
    }
}

@Serializable
private data class Indicators(val quote: List<QuoteBlock> = emptyList())

@Serializable
private data class QuoteBlock(val close: List<Double?> = emptyList(), val high: List<Double?> = emptyList())

@Serializable
private data class ChartMeta(
    val regularMarketPrice: Double? = null,
    val previousClose: Double? = null,
    val chartPreviousClose: Double? = null,
    val regularMarketDayHigh: Double? = null,
    val regularMarketDayLow: Double? = null,
    val fiftyTwoWeekHigh: Double? = null,
    val fiftyTwoWeekLow: Double? = null,
    val regularMarketVolume: Double? = null,
    val regularMarketTime: Long? = null,
    val gmtoffset: Int? = null,
    val currentTradingPeriod: TradingPeriods? = null,
    val longName: String? = null,
    val shortName: String? = null,
    val instrumentType: String? = null,
)

@Serializable
private data class TradingPeriods(val regular: TradingPeriod? = null)

@Serializable
private data class TradingPeriod(val start: Long? = null, val end: Long? = null, @SerialName("gmtoffset") val gmtOffset: Int? = null)
