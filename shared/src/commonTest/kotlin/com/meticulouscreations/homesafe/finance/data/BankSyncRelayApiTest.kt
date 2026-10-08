package com.meticulouscreations.homesafe.finance.data

import com.meticulouscreations.homesafe.finance.domain.BankAccountType
import com.meticulouscreations.homesafe.finance.domain.BankLinkStatus
import com.meticulouscreations.homesafe.finance.domain.BankProblem
import com.meticulouscreations.homesafe.finance.domain.BankSyncException
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.asUiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.fin_bank_error_plaid
import homesafe.shared.generated.resources.fin_bank_error_relay_outdated
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [BankSyncRelayApi] is the app's side of the relay's bank sync: what it sends to start a link,
 * how it reads the institutions back, and what each of the relay's refusals becomes.
 */
class BankSyncRelayApiTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun api(engine: MockEngine): BankSyncRelayApi {
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(HttpTimeout)
        }
        return BankSyncRelayApi(client)
    }

    private val status = """
        {"configured":true,"environment":"production","synced_at":1790800000.0,"syncing":false,"next_sync_at":1790870400.0,
         "institutions":[{"id":"item1","name":"Chase","linked_at":1790000000.0,"synced_at":1790800000.0,"error":"ITEM_LOGIN_REQUIRED",
           "error_message":"sign in again","needs_relink":true,"consent_expires":null,
           "accounts":[
             {"id":"chk","key":"Chase Total Checking 0123","name":"Total Checking","official_name":null,"mask":"0123","type":"depository","subtype":"checking",
              "balance":4200.5,"available":4100.0,"limit":null,"currency":"USD","apr":null,"min_payment":null,"due":null,"updated":1790800000.0,"holdings":0},
             {"id":"ira","key":"Chase Roth IRA","name":"Roth IRA","mask":null,"type":"investment","subtype":"roth","balance":51000.0,"currency":"USD","holdings":3},
             {"id":"card","key":"Chase Sapphire 9911","name":"Sapphire","mask":"9911","type":"credit","subtype":"credit card","balance":812.4,"currency":"USD","apr":21.49,"holdings":0}]}],
         "feed":{"configured":true,"url":"https://docs.google.com/spreadsheets/d/feed/edit","written_at":null,"error":"not_shared",
                 "message":"The caller does not have permission","activation_url":null,"service_account":"relay@example.iam.gserviceaccount.com"}}
    """.trimIndent()

    @Test
    fun theStatusReadsInstitutionsAccountsAndTheFeed() = runTest {
        var path = ""
        var port = -1
        val engine = MockEngine { request ->
            path = request.url.encodedPath
            port = request.url.port
            respond(status, HttpStatusCode.OK, jsonHeaders)
        }
        val bank = api(engine).status("http://frigate.local:8971").getOrThrow()
        assertEquals("/finance/bank" to 8787, path to port)

        assertTrue(bank.configured)
        assertFalse(bank.sandbox)
        assertEquals(1_790_800_000L, bank.syncedAtEpochSeconds)
        assertEquals(1_790_870_400L, bank.nextSyncAtEpochSeconds)
        val chase = bank.institutions.single()
        assertEquals("Chase", chase.name)
        assertTrue(chase.needsRelink)
        assertEquals("sign in again", chase.errorMessage)
        val (checking, ira, card) = chase.accounts
        assertEquals(BankAccountType.CASH, checking.type)
        assertEquals("0123", checking.mask)
        assertEquals(4200.5, checking.balance)
        assertEquals("Chase Total Checking 0123", checking.key)
        assertEquals(BankAccountType.INVESTMENT, ira.type)
        assertNull(ira.mask)
        assertEquals(3, ira.holdings)
        assertEquals(BankAccountType.CREDIT, card.type)
        assertTrue(card.type.isDebt)
        assertEquals(21.49, card.apr)
        assertEquals("not_shared", bank.feed.error)
        assertEquals("relay@example.iam.gserviceaccount.com", bank.feed.serviceAccount)
        assertNull(bank.feed.writtenAtEpochSeconds)
    }

    @Test
    fun aRelayWithoutPlaidStillAnswersAndSaysSo() = runTest {
        val engine = MockEngine { respond("""{"configured":false,"institutions":[],"feed":{"configured":false}}""", HttpStatusCode.OK, jsonHeaders) }
        val bank = api(engine).status("http://frigate.local").getOrThrow()
        assertFalse(bank.configured)
        assertFalse(bank.feed.configured)
        assertTrue(bank.institutions.isEmpty())
    }

    @Test
    fun sandboxIsToldApart() = runTest {
        val engine = MockEngine { respond("""{"configured":true,"environment":"sandbox"}""", HttpStatusCode.OK, jsonHeaders) }
        assertTrue(api(engine).status("http://frigate.local").getOrThrow().sandbox)
    }

    @Test
    fun startingALinkPostsTheKindAndReadsThePageToOpen() = runTest {
        var method: HttpMethod? = null
        var path = ""
        var sent = ""
        val engine = MockEngine { request ->
            method = request.method
            path = request.url.encodedPath
            sent = request.body.toByteArray().decodeToString()
            respond("""{"token":"link-1","url":"https://secure.plaid.com/hl/abc","expires_at":1790801800.0}""", HttpStatusCode.OK, jsonHeaders)
        }
        val link = api(engine).startLink("http://frigate.local", "investments").getOrThrow()
        assertEquals(HttpMethod.Post to "/finance/bank/link", method to path)
        assertEquals("investments", Json.parseToJsonElement(sent).jsonObject["kind"]?.jsonPrimitive?.content)
        assertEquals("link-1", link.token)
        assertEquals("https://secure.plaid.com/hl/abc", link.url)
        assertEquals(1_790_801_800L, link.expiresAtEpochSeconds)
    }

    @Test
    fun signingInAgainNamesTheInstitution() = runTest {
        var sent = ""
        val engine = MockEngine { request ->
            sent = request.body.toByteArray().decodeToString()
            respond("""{"token":"link-2","url":"https://secure.plaid.com/hl/def","expires_at":1.0}""", HttpStatusCode.OK, jsonHeaders)
        }
        api(engine).startLink("http://frigate.local", "bank", institutionId = "item1").getOrThrow()
        assertTrue("\"institution\":\"item1\"" in sent.replace(" ", ""), sent)
    }

    @Test
    fun aLinksProgressIsReadByItsToken() = runTest {
        var path = ""
        val answers = ArrayDeque(
            listOf(
                """{"status":"pending"}""",
                """{"status":"exited"}""",
                """{"status":"expired"}""",
                """{"status":"linked","institutions":["Chase",""],"bank":{"configured":true,"syncing":true,"institutions":[{"id":"item1","name":"Chase"}]}}""",
            ),
        )
        val engine = MockEngine { request ->
            path = request.url.encodedPath
            respond(answers.removeFirst(), HttpStatusCode.OK, jsonHeaders)
        }
        val api = api(engine)
        assertEquals(BankLinkStatus.PENDING, api.linkProgress("http://frigate.local", "link-1").getOrThrow().status)
        assertEquals("/finance/bank/link/link-1", path)
        assertEquals(BankLinkStatus.EXITED, api.linkProgress("http://frigate.local", "link-1").getOrThrow().status)
        assertEquals(BankLinkStatus.EXPIRED, api.linkProgress("http://frigate.local", "link-1").getOrThrow().status)
        val linked = api.linkProgress("http://frigate.local", "link-1").getOrThrow()
        assertEquals(BankLinkStatus.LINKED, linked.status)
        assertEquals(listOf("Chase"), linked.institutions, "an institution Plaid gave no name for isn't an empty name")
        val bank = assertNotNull(linked.bank)
        assertTrue(bank.syncing)
        assertEquals("Chase", bank.institutions.single().name)
    }

    @Test
    fun syncAndUnlinkUseTheirOwnRoutes() = runTest {
        val seen = mutableListOf<Pair<HttpMethod, String>>()
        val engine = MockEngine { request ->
            seen += request.method to request.url.encodedPath
            respond("""{"configured":true,"syncing":true}""", HttpStatusCode.OK, jsonHeaders)
        }
        val api = api(engine)
        assertTrue(api.syncNow("http://frigate.local").getOrThrow().syncing)
        api.unlink("http://frigate.local", "item1").getOrThrow()
        assertEquals(listOf(HttpMethod.Post to "/finance/bank/sync", HttpMethod.Delete to "/finance/bank/institutions/item1"), seen)
    }

    // ---- problemFrom -------------------------------------------------------------------------

    @Test
    fun a404WithNoDetailIsARelayFromBeforeBankSync() = runTest {
        val engine = MockEngine { respond("""{"detail":"Not Found"}""", HttpStatusCode.NotFound, jsonHeaders) }
        val failure = assertIs<BankSyncException>(api(engine).status("http://frigate.local").exceptionOrNull())
        assertEquals(BankProblem.RELAY_OUTDATED, failure.problem)
        assertEquals(UiText.of(Res.string.fin_bank_error_relay_outdated), failure.text)
    }

    @Test
    fun anInstitutionTheRelayDoesNotKnowIsNotAnOutdatedRelay() {
        val problem = BankSyncRelayApi.problemFrom(HttpStatusCode.NotFound, """{"detail":{"error":"not_found","message":"That institution isn't linked"}}""")
        assertEquals(BankProblem.OTHER, problem.problem)
        assertEquals("That institution isn't linked".asUiText(), problem.text)
    }

    @Test
    fun plaidsRefusalKeepsItsWordsAndItsCode() {
        val problem = BankSyncRelayApi.problemFrom(
            HttpStatusCode.BadGateway,
            """{"detail":{"error":"plaid_error","code":"INVALID_API_KEYS","message":"invalid client_id or secret provided"}}""",
        )
        assertEquals(BankProblem.PLAID, problem.problem)
        assertEquals(UiText.of(Res.string.fin_bank_error_plaid, "invalid client_id or secret provided"), problem.text)
        assertEquals("INVALID_API_KEYS", problem.message, "the code stays out of the translated text, where callers can match it")
    }

    @Test
    fun theRelaysOwnRefusalsAreToldApart() {
        fun problem(status: HttpStatusCode, error: String) = BankSyncRelayApi.problemFrom(status, """{"detail":{"error":"$error","message":"m"}}""").problem
        assertEquals(BankProblem.NOT_CONFIGURED, problem(HttpStatusCode.ServiceUnavailable, "not_configured"))
        assertEquals(BankProblem.NOT_ALLOWED, problem(HttpStatusCode.Forbidden, "not_allowed"))
        assertEquals(BankProblem.OTHER, problem(HttpStatusCode.BadRequest, "bad_kind"))
        assertEquals(BankProblem.SIGNED_OUT, BankSyncRelayApi.problemFrom(HttpStatusCode.Unauthorized, """{"detail":"Frigate rejected the session"}""").problem)
        assertEquals(BankProblem.OTHER, BankSyncRelayApi.problemFrom(HttpStatusCode.InternalServerError, "not json").problem)
    }
}
