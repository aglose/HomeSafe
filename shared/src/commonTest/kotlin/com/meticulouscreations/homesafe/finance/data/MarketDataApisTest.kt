package com.meticulouscreations.homesafe.finance.data

import com.meticulouscreations.homesafe.finance.domain.ChartRange
import com.meticulouscreations.homesafe.finance.domain.Series
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [FredApi] reads FRED's keyless CSV export; [YahooFinanceApi] reads Yahoo's unofficial spark
 * (bulk quotes) and chart (history) endpoints. Both answer with whatever shape the public web
 * happens to send, so the tests lean on the shapes that actually show up: missing observations,
 * null closes mid-series, and a `previousClose` that sometimes just isn't there.
 */
class MarketDataApisTest {

    // ---- FredApi.parseCsv ------------------------------------------------------------------

    @Test
    fun parseCsvSkipsTheHeaderAndBlankOrDotValues() {
        val csv = """
            observation_date,FEDFUNDS
            2024-01-01,5.33
            2024-02-01,.
            2024-03-01,
            2024-04-01,5.26
        """.trimIndent()
        val series = FredApi.parseCsv(csv)
        assertEquals(listOf(5.33, 5.26), series.values.toList())
        val expectedTimes = listOf(LocalDate(2024, 1, 1), LocalDate(2024, 4, 1)).map { it.toEpochDays() * Series.DAY_SECONDS }
        assertEquals(expectedTimes, series.times.toList())
    }

    @Test
    fun parseCsvSkipsLinesItCannotMakeSenseOf() {
        val csv = """
            observation_date,FEDFUNDS
            not-a-date,5.33
            2024-04-01,not-a-number

        """.trimIndent()
        assertTrue(FredApi.parseCsv(csv).isEmpty)
    }

    // ---- YahooFinanceApi.quotes -------------------------------------------------------------

    private fun sparkEngine(body: String) = MockEngine { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }

    private fun client(engine: MockEngine) = HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

    @Test
    fun quotesDropsNullClosesFromTheIntradaySeries() = runTest {
        val body = """
            {"spark":{"result":[{"symbol":"^GSPC","response":[{
                "meta":{"regularMarketPrice":5700.0,"previousClose":5650.0},
                "timestamp":[100,200,300],
                "indicators":{"quote":[{"close":[5600.0,null,5700.0]}]}
            }]}]}}
        """.trimIndent()
        val quote = YahooFinanceApi(client(sparkEngine(body))).quotes(listOf("^GSPC")).getOrThrow().single()
        assertEquals(listOf(100L, 300L), quote.intraday.times.toList())
        assertEquals(listOf(5600.0, 5700.0), quote.intraday.values.toList())
    }

    @Test
    fun quotesFallBackToChartPreviousCloseWhenPreviousCloseIsMissing() = runTest {
        val body = """
            {"spark":{"result":[{"symbol":"TSLA","response":[{
                "meta":{"regularMarketPrice":250.0,"chartPreviousClose":240.0},
                "timestamp":[],"indicators":{"quote":[{"close":[]}]}
            }]}]}}
        """.trimIndent()
        val quote = YahooFinanceApi(client(sparkEngine(body))).quotes(listOf("TSLA")).getOrThrow().single()
        assertEquals(240.0, quote.previousClose)
    }

    @Test
    fun quotesUsePreviousCloseOverChartPreviousCloseWhenBothArePresent() = runTest {
        val body = """
            {"spark":{"result":[{"symbol":"TSLA","response":[{
                "meta":{"regularMarketPrice":250.0,"previousClose":245.0,"chartPreviousClose":240.0},
                "timestamp":[],"indicators":{"quote":[{"close":[]}]}
            }]}]}}
        """.trimIndent()
        val quote = YahooFinanceApi(client(sparkEngine(body))).quotes(listOf("TSLA")).getOrThrow().single()
        assertEquals(245.0, quote.previousClose)
    }

    @Test
    fun quotesDropsResultsWithNoRegularMarketPrice() = runTest {
        val body = """
            {"spark":{"result":[{"symbol":"DEAD","response":[{"meta":{},"timestamp":[],"indicators":{"quote":[]}}]}]}}
        """.trimIndent()
        assertTrue(YahooFinanceApi(client(sparkEngine(body))).quotes(listOf("DEAD")).getOrThrow().isEmpty())
    }

    @Test
    fun quotesFailWhenYahooAnswersWithAnErrorStatus() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.ServiceUnavailable) }
        val result = YahooFinanceApi(client(engine)).quotes(listOf("^GSPC"))
        assertTrue(result.isFailure)
    }

    // ---- YahooFinanceApi.history --------------------------------------------------------------

    private fun chartBody(previousClose: String = "5650.0", chartPreviousClose: String = "5600.0") = """
        {"chart":{"result":[{
            "meta":{"regularMarketPrice":5700.0,"previousClose":$previousClose,"chartPreviousClose":$chartPreviousClose},
            "timestamp":[100,200,300],
            "indicators":{"quote":[{"close":[5600.0,null,5700.0]}]}
        }]}}
    """.trimIndent()

    @Test
    fun historyDropsNullClosesTheSameWayQuotesDo() = runTest {
        val engine = MockEngine { respond(chartBody(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        val history = YahooFinanceApi(client(engine)).history("^GSPC", ChartRange.DAY).getOrThrow()
        assertEquals(listOf(100L, 300L), history.series.times.toList())
        assertEquals(listOf(5600.0, 5700.0), history.series.values.toList())
    }

    @Test
    fun dayRangeUsesPreviousCloseAsTheBaselineAndOtherRangesUseChartPreviousClose() = runTest {
        val day = MockEngine { respond(chartBody(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        val dayHistory = YahooFinanceApi(client(day)).history("^GSPC", ChartRange.DAY).getOrThrow()
        assertEquals(5650.0, dayHistory.baseline, "the 1D chart's dotted baseline is yesterday's close")

        val month = MockEngine { respond(chartBody(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        val monthHistory = YahooFinanceApi(client(month)).history("^GSPC", ChartRange.MONTH).getOrThrow()
        assertEquals(5600.0, monthHistory.baseline, "longer ranges baseline off the chart's own previous close, not the live quote's")
    }

    @Test
    fun historyEncodesACaretInTheSymbolForTheChartPath() = runTest {
        var seenPath = ""
        val engine = MockEngine { request ->
            seenPath = request.url.encodedPath
            respond(chartBody(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        YahooFinanceApi(client(engine)).history("^GSPC", ChartRange.DAY).getOrThrow()
        assertFalse("^" in seenPath, "a literal caret is not a valid URL path segment")
        assertTrue("GSPC" in seenPath, seenPath)
    }

    @Test
    fun historyFailsWhenYahooAnswersWithAnErrorStatus() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.NotFound) }
        val result = YahooFinanceApi(client(engine)).history("NOPE", ChartRange.DAY)
        assertTrue(result.isFailure)
    }
}
