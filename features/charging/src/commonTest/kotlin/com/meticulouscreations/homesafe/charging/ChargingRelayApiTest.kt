package com.meticulouscreations.homesafe.charging

import com.meticulouscreations.homesafe.charging.data.ChargingRelayApi
import com.meticulouscreations.homesafe.charging.domain.ChargeCostSource
import com.meticulouscreations.homesafe.charging.domain.ChargeMeasure
import com.meticulouscreations.homesafe.charging.domain.ChargePlace
import com.meticulouscreations.homesafe.charging.domain.RatePeriod
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChargingRelayApiTest {
    private fun api(status: HttpStatusCode = HttpStatusCode.OK, body: String = "{}", seen: MutableList<String> = mutableListOf()) = ChargingRelayApi(
        HttpClient(
            MockEngine { request ->
                seen += request.url.encodedPathAndQuery
                respond(body, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            },
        ) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        },
    )

    private val summary = """
        {"provider": {"name": "Example Power", "url": "https://example.com"}, "plan": "ev_tou",
         "plans": [{"id": "ev_tou", "name": "EV", "kind": "tou", "current": true, "effective": "2026-07-01", "peak_start": 17, "peak_end": 21,
                    "periods": [{"period": "peak", "rate": 0.5, "billed": 0.538}, {"period": "mystery", "rate": 1, "billed": 1}],
                    "weekday": [0.17, 0.54], "weekend": [0.17, 0.17]}],
         "tax_percent": 7.5, "gas": {"price": 4.8, "mpg": 30},
         "now": [{"car": "a", "name": "Alex", "battery": 57.4, "limit": 80, "kw": 7.7,
                  "session": {"id": 3, "car": "a", "name": "Alex", "place": "home", "source": "stream", "started": 1000.4, "ended": null, "kwh": 2, "cost": 0.5, "cost_is": "plan"}}],
         "months": [{"month": "2026-10", "card_fast": null, "electric_paid": null, "peak_kwh": 1.5, "plans": {"ev_tou": 2.5},
                     "cars": [{"car": "a", "name": "Alex", "home_kwh": 10, "home_cost": 2.5, "sessions": 1, "miles": null, "new_field": 9}]}],
         "by_hour": [1, 2], "more_sessions": true, "first_session": 1000,
         "sessions": [{"id": 2, "car": "b", "name": "", "place": "supercharger", "source": "tesla", "started": 500, "ended": 900, "soc_start": 20.2, "soc_end": 60,
                       "kwh": 41, "how": "receipt", "site": "Example Plaza", "cost": 16.4, "cost_is": "receipt"},
                      {"id": 1, "car": "b", "place": "weird", "source": "soc", "started": 100, "ended": 200, "kwh": 5, "how": "battery"}],
         "setup": {"rates": true, "power_stream": false, "receipts": true, "receipts_at": 12.5}, "unknown": []}
    """.trimIndent()

    @Test
    fun readsEverythingTheAppOpensOn() = runTest {
        val seen = mutableListOf<String>()
        val answer = api(body = summary, seen = seen).summary("http://box").getOrThrow()
        assertTrue(seen.single().endsWith("/charging"))
        assertEquals("Example Power", answer.providerName)
        assertEquals("ev_tou", answer.plan?.id)
        val plan = answer.plans.single()
        assertTrue(plan.isTimeOfUse)
        assertEquals(listOf(RatePeriod.PEAK), plan.prices.map { it.period })
        assertEquals(17, plan.peakStart)
        assertEquals(30.0, answer.gas?.milesPerGallon)

        val now = answer.charging.single()
        assertEquals(57, now.battery)
        assertTrue(assertNotNull(now.session).isOpen)

        val month = answer.months.single()
        assertEquals(2.5, month.homeCost)
        assertNull(month.cardFast)
        assertEquals(2.5, month.planCosts["ev_tou"])

        val (receipt, missed) = answer.sessions
        assertEquals("b", receipt.carName)
        assertEquals(ChargePlace.SUPERCHARGER, receipt.place)
        assertEquals(ChargeCostSource.RECEIPT, receipt.costSource)
        assertEquals(ChargeMeasure.RECEIPT, receipt.measure)
        assertEquals(20, receipt.socStart)
        assertEquals("Example Plaza", receipt.site)
        assertEquals(ChargePlace.OTHER, missed.place)
        assertTrue(missed.missed)
        assertNull(missed.cost)

        assertTrue(answer.moreSessions)
        assertEquals(false, answer.setup.powerStream)
    }

    @Test
    fun aNullFromTheRelayForGasMeansNoComparison() = runTest {
        assertNull(api(body = """{"gas": {"price": 4.8}}""").summary("http://box").getOrThrow().gas)
    }

    @Test
    fun aChargeComesHourByHourWithWhatEachPlanWouldCharge() = runTest {
        val detail = api(
            body = """{"id": 9, "car": "a", "name": "Alex", "place": "home", "started": 100, "ended": 200, "kwh": 2,
                "hours": [{"hour": 100, "kwh": 2, "price": 0.28, "period": "tier2"}], "plans": {"tiered": 0.56}}""",
        ).session("http://box", 9).getOrThrow()
        assertEquals(9, detail.session.id)
        assertEquals(RatePeriod.TIER2, detail.hours.single().period)
        assertEquals(0.56, detail.plans["tiered"])
    }

    @Test
    fun aChargeKeepsItsExactStartForAskingWhatCameBefore() = runTest {
        val (sessions, _) = api(body = """{"sessions": [{"id": 1, "car": "a", "started": 100.6}]}""").olderSessions("http://box", 1.0).getOrThrow()
        assertEquals(101, sessions.single().startedEpochSeconds)
        assertEquals(100.6, sessions.single().pageKey)
    }

    @Test
    fun olderChargesAskFromTheOldestShown() = runTest {
        val seen = mutableListOf<String>()
        val (sessions, more) = api(body = """{"sessions": [{"id": 1, "car": "a", "started": 5}], "more_sessions": false}""", seen = seen).olderSessions("http://box", 1234.5).getOrThrow()
        assertTrue(seen.single().endsWith("/charging/sessions?before=1234.5"))
        assertEquals(1, sessions.size)
        assertEquals(false, more)
    }

    @Test
    fun whatTheRelayRefusesIsAFailure() = runTest {
        assertTrue(api(HttpStatusCode.NotFound).summary("http://box").isFailure)
        assertTrue(api(HttpStatusCode.Unauthorized).summary("http://box").isFailure)
        assertTrue(api(HttpStatusCode.InternalServerError).choosePlan("http://box", "x").isFailure)
    }
}
