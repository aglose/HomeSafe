package com.meticulouscreations.homesafe.finance.data

import com.meticulouscreations.homesafe.finance.domain.ChartRange
import com.meticulouscreations.homesafe.finance.domain.InstrumentKind
import com.meticulouscreations.homesafe.finance.domain.Series
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
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

    // ---- Names, whole histories and search ----------------------------------------------------

    @Test
    fun quotesCarryTheNameAndKindYahooSends() = runTest {
        val body = """
            {"spark":{"result":[{"symbol":"VTSAX","response":[{
                "meta":{"regularMarketPrice":150.0,"previousClose":149.0,"longName":"Vanguard Total Stock Market Index Admiral","shortName":"Vanguard Total","instrumentType":"MUTUALFUND"},
                "timestamp":[100],"indicators":{"quote":[{"close":[150.0]}]}
            }]}]}}
        """.trimIndent()
        val quote = YahooFinanceApi(client(sparkEngine(body))).quotes(listOf("VTSAX")).getOrThrow().single()
        assertEquals("Vanguard Total Stock Market Index Admiral", quote.name)
        assertEquals(InstrumentKind.EQUITY, InstrumentKind.fromYahoo(quote.instrumentType), "a fund is priced like a stock")
    }

    @Test
    fun theWholeHistoryIsAskedForByDatesWeeklyWithItsHighestHighAndNoBaseline() = runTest {
        var asked: Url? = null
        val engine = MockEngine { request ->
            asked = request.url
            respond(
                """
                {"chart":{"result":[{
                    "meta":{"chartPreviousClose":17.66,"gmtoffset":-14400},
                    "timestamp":[100,200,300],
                    "indicators":{"quote":[{"close":[10.0,30.0,25.0],"high":[11.0,null,34.5]}]}
                }]}}
                """.trimIndent(),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val history = YahooFinanceApi(client(engine)).history("^GSPC", ChartRange.MAX).getOrThrow()
        val url = asked!!
        assertEquals(null, url.parameters["range"], "range=max comes back thinned to monthly bars")
        assertTrue(url.parameters["period1"]!!.toLong() < 0, "from before 1970: the S&P goes back to 1927")
        assertEquals("1wk", url.parameters["interval"])
        assertEquals(34.5, history.highest)
        assertEquals(300L, history.highestEpochSeconds)
        assertEquals(null, history.baseline, "a whole history is measured from its own first price")
    }

    @Test
    fun searchKeepsWhatCanBeQuotedAndNamesItsKind() = runTest {
        val body = """
            {"quotes":[
                {"symbol":"VTI","shortname":"Vanguard Total","longname":"Vanguard Total Stock Market Index Fund ETF Shares","quoteType":"ETF","typeDisp":"ETF","exchDisp":"NYSEArca","isYahooFinance":true},
                {"symbol":"BTC-USD","shortname":"Bitcoin USD","quoteType":"CRYPTOCURRENCY","typeDisp":"Cryptocurrency","exchDisp":"CCC","isYahooFinance":true},
                {"symbol":"OPTION1","quoteType":"OPTION","isYahooFinance":true},
                {"symbol":"PRIVATECO","shortname":"Private Co","quoteType":"EQUITY","isYahooFinance":false},
                {"index":"news"}
            ],"news":[]}
        """.trimIndent()
        val matches = YahooFinanceApi(client(sparkEngine(body))).search("vti").getOrThrow()
        assertEquals(listOf("VTI", "BTC-USD"), matches.map { it.symbol })
        assertEquals("Vanguard Total Stock Market Index Fund ETF Shares", matches[0].name, "the long name where there is one")
        assertEquals(InstrumentKind.EQUITY, matches[0].kind)
        assertEquals("NYSEArca", matches[0].exchange)
        assertEquals(InstrumentKind.CRYPTO, matches[1].kind)
        assertEquals("Bitcoin USD", matches[1].name)
    }
}
