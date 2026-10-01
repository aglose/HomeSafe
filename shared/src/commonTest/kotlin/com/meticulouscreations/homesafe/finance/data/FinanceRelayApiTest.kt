package com.meticulouscreations.homesafe.finance.data

import com.meticulouscreations.homesafe.finance.domain.SheetProblem
import com.meticulouscreations.homesafe.finance.domain.SheetUnavailableException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [FinanceRelayApi] reads the household budget workbook through the relay. `problemFrom` turns
 * whatever the relay's `{"detail": {...}}` says into a typed [SheetProblem] the setup card can
 * act on; `workbook` wires that into a real (mocked) HTTP round trip.
 */
class FinanceRelayApiTest {

    // ---- problemFrom -------------------------------------------------------------------------

    @Test
    fun problemFromMapsEachKnownErrorCode() {
        fun problem(error: String) = FinanceRelayApi.problemFrom(
            HttpStatusCode.Forbidden,
            """{"detail":{"error":"$error","message":"m"}}""",
        ).problem
        assertEquals(SheetProblem.NOT_CONFIGURED, problem("not_configured"))
        assertEquals(SheetProblem.NOT_SHARED, problem("not_shared"))
        assertEquals(SheetProblem.API_DISABLED, problem("api_disabled"))
        assertEquals(SheetProblem.NO_KEY, problem("no_key"))
        assertEquals(SheetProblem.NOT_FOUND, problem("not_found"))
    }

    @Test
    fun problemFromCarriesTheServiceAccountAndActivationUrl() {
        val problem = FinanceRelayApi.problemFrom(
            HttpStatusCode.Forbidden,
            """{"detail":{"error":"not_shared","message":"Share it","service_account":"relay@project.iam.gserviceaccount.com"}}""",
        )
        assertEquals("Share it", problem.message)
        assertEquals("relay@project.iam.gserviceaccount.com", problem.serviceAccount)
        assertNull(problem.activationUrl)

        val disabled = FinanceRelayApi.problemFrom(
            HttpStatusCode.Forbidden,
            """{"detail":{"error":"api_disabled","message":"Turn it on","activation_url":"https://console.cloud.google.com/apis"}}""",
        )
        assertEquals("https://console.cloud.google.com/apis", disabled.activationUrl)
    }

    @Test
    fun problemFromWithNoDetailFallsBackToStatus() {
        val unauthorized = FinanceRelayApi.problemFrom(HttpStatusCode.Unauthorized, "")
        assertEquals(SheetProblem.SIGNED_OUT, unauthorized.problem)
        assertEquals("The relay answered ${HttpStatusCode.Unauthorized}", unauthorized.message)

        val forbidden = FinanceRelayApi.problemFrom(HttpStatusCode.Forbidden, "")
        assertEquals(SheetProblem.SIGNED_OUT, forbidden.problem)
    }

    @Test
    fun problemFromWithGarbageBodyIsOther() {
        val problem = FinanceRelayApi.problemFrom(HttpStatusCode.InternalServerError, "not json at all")
        assertEquals(SheetProblem.OTHER, problem.problem)
        assertEquals("The relay answered ${HttpStatusCode.InternalServerError}", problem.message)
    }

    // ---- workbook ------------------------------------------------------------------------------

    private fun api(engine: MockEngine): FinanceRelayApi {
        val client = HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        return FinanceRelayApi(client)
    }

    @Test
    fun workbookParsesSheetsMergesAndMixedCellTypes() = runTest {
        val body = """
            {"title":"Budget","fetched_at":1790800000.0,"url":"https://docs.google.com/spreadsheets/d/abc123",
             "sheets":[{"title":"Home","values":[["A1",2.0,true,null],["B1","text",false]],
                        "merges":[{"start_row":0,"end_row":1,"start_column":0,"end_column":2}]}]}
        """.trimIndent()
        val engine = MockEngine { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        val workbook = api(engine).workbook("http://frigate.local", refresh = false).getOrThrow()

        assertEquals("Budget", workbook.title)
        assertEquals("https://docs.google.com/spreadsheets/d/abc123", workbook.url)
        val grid = workbook.grids().single()
        assertEquals("Home", grid.title)
        assertEquals("A1", grid.text(0, 0))
        assertEquals("A1", grid.text(0, 1), "the merge's second cell reads the top-left value")
        assertEquals("text", grid.text(1, 1))
        assertEquals(CellValue.Bool(false), grid.value(1, 2))
        assertEquals(CellValue.Empty, grid.value(0, 3), "a JSON null cell is empty, not a stray value")
    }

    @Test
    fun workbookSendsTheRefreshParameterAndHitsTheRelayPort() = runTest {
        var seenQuery: String? = null
        var seenPort = -1
        var seenPath = ""
        val engine = MockEngine { request ->
            seenQuery = request.url.parameters["refresh"]
            seenPort = request.url.port
            seenPath = request.url.encodedPath
            respond("""{"title":"t","sheets":[]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        api(engine).workbook("http://frigate.local", refresh = true).getOrThrow()
        assertEquals("true", seenQuery)
        assertEquals(8787, seenPort)
        assertEquals("/finance/sheet", seenPath)
    }

    @Test
    fun workbookOmitsTheRefreshParameterWhenNotRefreshing() = runTest {
        var seenQuery: String? = "unset"
        val engine = MockEngine { request ->
            seenQuery = request.url.parameters["refresh"]
            respond("""{"title":"t","sheets":[]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        api(engine).workbook("http://frigate.local", refresh = false).getOrThrow()
        assertNull(seenQuery)
    }

    @Test
    fun workbook404MeansTheRelayPredatesTheFeature() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.NotFound) }
        val failure = api(engine).workbook("http://frigate.local", refresh = false).exceptionOrNull()
        val sheetException = assertIs<SheetUnavailableException>(failure)
        assertEquals(SheetProblem.RELAY_OUTDATED, sheetException.problem)
    }

    @Test
    fun workbookOtherErrorStatusesGoThroughProblemFrom() = runTest {
        val body = """{"detail":{"error":"not_shared","message":"Share it","service_account":"relay@x"}}"""
        val engine = MockEngine { respond(body, HttpStatusCode.Forbidden, headersOf(HttpHeaders.ContentType, "application/json")) }
        val failure = api(engine).workbook("http://frigate.local", refresh = false).exceptionOrNull()
        val sheetException = assertIs<SheetUnavailableException>(failure)
        assertEquals(SheetProblem.NOT_SHARED, sheetException.problem)
        assertEquals("relay@x", sheetException.serviceAccount)
        assertFalse(sheetException.message!!.isEmpty())
    }
}
