package com.meticulouscreations.homesafe.finance.data

import com.meticulouscreations.homesafe.finance.domain.BankProblem
import com.meticulouscreations.homesafe.finance.domain.BankSyncException
import com.meticulouscreations.homesafe.finance.domain.BucketId
import com.meticulouscreations.homesafe.finance.domain.BucketSource
import com.meticulouscreations.homesafe.finance.domain.BudgetAlertSwitches
import com.meticulouscreations.homesafe.finance.domain.BudgetConfigPatch
import com.meticulouscreations.homesafe.finance.domain.BudgetLimits
import com.meticulouscreations.homesafe.finance.domain.BudgetSheetFigures
import com.meticulouscreations.homesafe.finance.domain.CardRole
import com.meticulouscreations.homesafe.finance.domain.MerchantRule
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.asUiText
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.fin_budget_error_relay_outdated
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [BudgetRelayApi] is the app's side of the relay's budget: how it reads a month back, what it
 * sends to change the settings or tag a purchase, and what the relay's refusals become.
 */
class BudgetRelayApiTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun api(engine: MockEngine): BudgetRelayApi {
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(HttpTimeout)
        }
        return BudgetRelayApi(client)
    }

    private val month = """
        {"configured":true,"environment":"sandbox","month":"2026-10","today":"2026-10-07","day":7,"days_in_month":31,
         "synced_at":1791374000.0,"changed_at":1791360000.5,"syncing":false,"next_sync_at":1791377600.0,"ready":false,
         "cards":[{"key":"Summit Bank Voyager 4410","institution_id":"item-1","institution":"Summit Bank","name":"Voyager","mask":"4410","role":"split",
                   "balance":812.4,"limit":10000,"error":"ITEM_LOGIN_REQUIRED","needs_relink":true,"transactions":true},
                  {"key":"Northwind Platinum 2207","institution_id":"item-2","institution":"Northwind","name":"Platinum","mask":"","role":null,
                   "balance":null,"limit":null,"error":null,"needs_relink":false,"transactions":false}],
         "config":{"people":["Alex","Sam"],"limits":{"total":4500.0,"people":{"Alex":1200.0,"Sam":null},"family":2100.0},
                   "roles":{"Summit Bank Voyager 4410":"split"},"card_paid_lines":["Groceries"],"alerts":{"total":true,"savings":false,"buckets":true},
                   "sheet":{"take_home":14170.0,"bills_off_card":9000.0,"at":1791370000.0},"rules":[{"merchant":"warehouse club","bucket":"person:Sam"}]},
         "savings_line":5170.0,"spent":310.55,"pending":54.2,
         "buckets":[{"id":"person:Alex","kind":"person","name":"Alex","spent":40.1,"limit":1200.0,"count":1},
                    {"id":"family","kind":"family","name":null,"spent":100.0,"limit":2100.0,"count":1},
                    {"id":"unassigned","kind":"unassigned","name":null,"spent":170.45,"limit":null,"count":2}],
         "daily":[{"date":"2026-10-01","spent":120.5},{"date":"2026-10-02","spent":0.0},{"date":"2026-10-03","spent":190.05}],
         "categories":[{"id":"FOOD_AND_DRINK","spent":210.2,"count":3}],
         "merchants":[{"name":"Warehouse Club","spent":116.25,"count":2}],
         "transactions":[
           {"id":"t1","date":"2026-10-03","amount":54.2,"name":"Warehouse Club","merchant":"warehouse club","category":"GENERAL_MERCHANDISE","pending":true,
            "card":"Summit Bank Voyager 4410","bucket":"person:Sam","source":"rule","kind":"spend","remembered":true},
           {"id":"t2","date":"2026-10-01","amount":-12.0,"name":"Luna Pizzeria","merchant":"luna pizzeria","category":null,"pending":false,
            "card":"Summit Bank Voyager 4410","bucket":null,"source":"none","kind":"refund","remembered":false}],
         "history":[{"month":"2026-09","spent":4100.0,"days":30}],"alerts_sent":["total_80"]}
    """.trimIndent()

    @Test
    fun aMonthReadsItsCardsBucketsAndPurchases() = runTest {
        var asked = ""
        val engine = MockEngine { request ->
            asked = "${request.url.encodedPath}?${request.url.encodedQuery} on ${request.url.port}"
            respond(month, HttpStatusCode.OK, jsonHeaders)
        }
        val budget = api(engine).budget("http://frigate.local:8971", "2026-10").getOrThrow()
        assertEquals("/finance/budget?month=2026-10 on 8787", asked)

        assertTrue(budget.configured)
        assertTrue(budget.sandbox)
        assertTrue(budget.isCurrentMonth)
        assertEquals(Triple("2026-10", 7, 31), Triple(budget.month, budget.day, budget.daysInMonth))
        assertEquals(1_791_374_000L to 1_791_360_000L, budget.syncedAtEpochSeconds to budget.changedAtEpochSeconds)
        assertFalse(budget.ready)
        val (voyager, platinum) = budget.cards
        assertEquals(CardRole.Split, voyager.role)
        assertTrue(voyager.needsRelink)
        assertEquals("4410", voyager.mask)
        assertEquals(CardRole.Unset, platinum.role)
        assertNull(platinum.mask)
        assertFalse(platinum.readsPurchases)
        assertEquals(listOf(voyager), budget.budgetCards)

        assertEquals(BudgetLimits(total = 4_500.0, people = mapOf("Alex" to 1_200.0, "Sam" to null), family = 2_100.0), budget.config.limits)
        assertEquals(BudgetAlertSwitches(total = true, savings = false, buckets = true), budget.config.alerts)
        assertEquals(listOf(MerchantRule("warehouse club", BucketId.Person("Sam"))), budget.config.rules)
        assertEquals(BudgetSheetFigures(14_170.0, 9_000.0), budget.config.sheet)
        assertEquals(5_170.0, budget.savingsLine)
        assertEquals(listOf(BucketId.Person("Alex"), BucketId.Family, BucketId.Unassigned), budget.buckets.map { it.id })
        assertEquals(listOf(120.5, 0.0, 190.05), budget.daily)
        val (pending, refund) = budget.purchases
        assertEquals(BucketId.Person("Sam") to BucketSource.RULE, pending.bucket to pending.source)
        assertTrue(pending.pending && pending.remembered)
        assertTrue(refund.isRefund)
        assertEquals(BucketId.Unassigned, refund.bucket)
        assertNull(refund.category)
        assertEquals(listOf(refund), budget.unassigned)
        assertEquals("2026-09", budget.history.single().month)
    }

    @Test
    fun theMonthUnderWayIsAskedForWithoutNamingIt() = runTest {
        var query: String? = "unset"
        val engine = MockEngine { request ->
            query = request.url.encodedQuery
            respond("""{"configured":false,"month":"2026-10","today":"2026-11-02"}""", HttpStatusCode.OK, jsonHeaders)
        }
        val budget = api(engine).budget("http://frigate.local", null).getOrThrow()
        assertEquals("", query)
        assertFalse(budget.configured)
        assertFalse(budget.isCurrentMonth)
    }

    @Test
    fun aChangeToTheSettingsSendsOnlyWhatChangedAndANullTakesALimitAway() = runTest {
        var method: HttpMethod? = null
        var sent = ""
        val engine = MockEngine { request ->
            method = request.method
            sent = request.body.toByteArray().decodeToString()
            respond(month, HttpStatusCode.OK, jsonHeaders)
        }
        api(engine).save("http://frigate.local", BudgetConfigPatch(limits = BudgetLimits(total = 4_000.0, people = mapOf("Sam" to null)), roles = mapOf("Card A" to CardRole.Person("Sam"), "Card B" to CardRole.Unset))).getOrThrow()
        assertEquals(HttpMethod.Put, method)
        val body = Json.parseToJsonElement(sent).jsonObject
        assertEquals(setOf("limits", "roles"), body.keys)
        val limits = body.getValue("limits").jsonObject
        assertEquals(4_000.0, limits.getValue("total").jsonPrimitive.content.toDouble())
        assertEquals(JsonNull, limits.getValue("family"))
        assertEquals(JsonNull, limits.getValue("people").jsonObject.getValue("Sam"))
        assertEquals("person:Sam", body.getValue("roles").jsonObject.getValue("Card A").jsonPrimitive.content)
        assertEquals(JsonNull, body.getValue("roles").jsonObject.getValue("Card B"))
    }

    @Test
    fun theSheetsFiguresAndThePaidLinesGoByTheRelaysNames() {
        val body = BudgetConfigPatch(cardPaidLines = listOf("Groceries"), sheet = BudgetSheetFigures(14_170.0, 9_000.0), alerts = BudgetAlertSwitches(total = true), people = listOf("Alex")).toJson()
        assertEquals(setOf("people", "card_paid_lines", "alerts", "sheet"), body.keys)
        assertEquals("9000.0", body.getValue("sheet").jsonObject.getValue("bills_off_card").jsonPrimitive.content)
        assertEquals("true", body.getValue("alerts").jsonObject.getValue("total").jsonPrimitive.content)
    }

    @Test
    fun taggingAPurchaseSaysWhoseAndWhetherToRemember() = runTest {
        val calls = mutableListOf<String>()
        val engine = MockEngine { request ->
            calls += "${request.method.value} ${request.url.encodedPath} ${request.body.toByteArray().decodeToString()}"
            respond(month, HttpStatusCode.OK, jsonHeaders)
        }
        val api = api(engine)
        api.tag("http://frigate.local", "t1", BucketId.Person("Sam"), remember = true).getOrThrow()
        api.tag("http://frigate.local", "t1", BucketId.Unassigned, remember = false).getOrThrow()
        api.forgetRule("http://frigate.local", "warehouse club").getOrThrow()
        api.syncNow("http://frigate.local").getOrThrow()
        assertEquals(
            listOf(
                """PUT /finance/budget/transactions/t1 {"bucket":"person:Sam","remember":true}""",
                """PUT /finance/budget/transactions/t1 {"bucket":null,"remember":false}""",
                "DELETE /finance/budget/rules/warehouse%20club ",
                "POST /finance/budget/sync ",
            ),
            calls,
        )
    }

    @Test
    fun aRelayFromBeforeTheBudgetSaysSoInTheBudgetsWords() = runTest {
        val engine = MockEngine { respond("""{"detail":"Not Found"}""", HttpStatusCode.NotFound, jsonHeaders) }
        val failure = assertIs<BankSyncException>(api(engine).budget("http://frigate.local", null).exceptionOrNull())
        assertEquals(BankProblem.RELAY_OUTDATED, failure.problem)
        assertEquals(UiText.of(Res.string.fin_budget_error_relay_outdated), failure.text)
    }

    @Test
    fun theRelaysRefusalsKeepTheirReasonAndItsWords() = runTest {
        val refused = MockEngine { respond("""{"detail":{"error":"not_allowed","message":"This account can't see the household's finances"}}""", HttpStatusCode.Forbidden, jsonHeaders) }
        val notAllowed = assertIs<BankSyncException>(api(refused).budget("http://frigate.local", null).exceptionOrNull())
        assertEquals(BankProblem.NOT_ALLOWED, notAllowed.problem)

        val gone = MockEngine { respond("""{"detail":{"error":"not_found","message":"That purchase isn't there any more"}}""", HttpStatusCode.NotFound, jsonHeaders) }
        val missing = assertIs<BankSyncException>(api(gone).tag("http://frigate.local", "t9", BucketId.Family, remember = false).exceptionOrNull())
        assertEquals(BankProblem.OTHER, missing.problem)
        assertEquals("That purchase isn't there any more".asUiText(), missing.text)
    }
}
