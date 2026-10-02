package com.meticulouscreations.homesafe.finance.data

import com.meticulouscreations.homesafe.finance.domain.ChartRange
import com.meticulouscreations.homesafe.finance.domain.PriceHistory
import com.meticulouscreations.homesafe.finance.domain.Quote
import com.meticulouscreations.homesafe.finance.domain.Series
import com.meticulouscreations.homesafe.network.FrigateResponseException
import com.meticulouscreations.homesafe.text.UiText
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Named
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.fin_data_error_fred_answered
import homesafe.shared.generated.resources.fin_data_error_no_chart
import homesafe.shared.generated.resources.fin_data_error_yahoo_answered
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
import io.ktor.http.HttpStatusCode
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
            if (!response.status.isSuccess()) throw yahooFailed(response.status)
            response.body<SparkEnvelope>().spark.result.mapNotNull { r ->
                r.response.firstOrNull()?.toQuote(r.symbol)
            }
        }
    }

    suspend fun history(symbol: String, range: ChartRange): Result<PriceHistory> = suspendRunCatching {
        val response = httpClient.get("$BASE/v8/finance/chart/${symbol.encodeURLPathPart()}") {
            parameter("range", range.yahooRange)
            parameter("interval", range.yahooInterval)
            parameter("includePrePost", "false")
        }
        if (!response.status.isSuccess()) throw yahooFailed(response.status)
        val result = response.body<ChartEnvelope>().chart.result?.firstOrNull()
            ?: throw FrigateResponseException(UiText.of(Res.string.fin_data_error_no_chart, symbol), "Yahoo had no chart for $symbol")
        val baseline = if (range == ChartRange.DAY) result.meta.previousClose ?: result.meta.chartPreviousClose else result.meta.chartPreviousClose
        PriceHistory(symbol, range, result.series(), baseline, result.meta.gmtoffset ?: 0)
    }

    private companion object {
        fun yahooFailed(status: HttpStatusCode) =
            FrigateResponseException(UiText.of(Res.string.fin_data_error_yahoo_answered, status.toString()), "Yahoo answered $status")

        const val BASE = "https://query1.finance.yahoo.com"
        const val SPARK_MAX = 20
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
        if (!response.status.isSuccess()) {
            throw FrigateResponseException(UiText.of(Res.string.fin_data_error_fred_answered, response.status.toString(), seriesId), "FRED answered ${response.status} for $seriesId")
        }
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
        )
    }
}

@Serializable
private data class Indicators(val quote: List<QuoteBlock> = emptyList())

@Serializable
private data class QuoteBlock(val close: List<Double?> = emptyList())

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
)

@Serializable
private data class TradingPeriods(val regular: TradingPeriod? = null)

@Serializable
private data class TradingPeriod(val start: Long? = null, val end: Long? = null, @SerialName("gmtoffset") val gmtOffset: Int? = null)
