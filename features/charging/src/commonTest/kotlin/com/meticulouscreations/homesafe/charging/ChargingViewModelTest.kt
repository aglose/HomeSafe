package com.meticulouscreations.homesafe.charging

import com.meticulouscreations.homesafe.charging.data.ChargingRelayApi
import com.meticulouscreations.homesafe.domain.model.ActiveConnection
import com.meticulouscreations.homesafe.domain.model.ConnectionRecord
import com.meticulouscreations.homesafe.domain.model.SavedCredentials
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.biometric_name_generic
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ChargingViewModelTest {
    private class FakeConnection(url: String?) : ConnectionRepository {
        override val activeConnection = MutableStateFlow<ActiveConnection?>(null)
        override val serverUnreachable = MutableStateFlow(false)
        override val currentServerUrl = MutableStateFlow(url)
        override val mostRecentConnection = MutableStateFlow<ConnectionRecord?>(null)
        override val biometricLoginAvailable = false
        override val biometricDisplayName = Res.string.biometric_name_generic
        override fun hasSavedBiometricCredentials() = false
        override suspend fun connect(serverUrl: String, localUrl: String?, username: String, password: String) = fail("unused")
        override suspend fun signInWithBiometrics(onCredentialsUnlocked: () -> Unit) = fail("unused")
        override suspend fun saveBiometricCredentials(credentials: SavedCredentials) = fail("unused")
        override fun forgetBiometricCredentials() = Unit
        override fun onAppVisibilityChanged(visible: Boolean) = Unit
    }

    private val dispatcher = StandardTestDispatcher()
    private val calls = mutableListOf<String>()
    private var charging = false
    private var refuse = false
    private lateinit var connection: FakeConnection
    private var months = "[]"
    private var newest = """{"id": 2, "car": "c", "started": 200}"""
    private var detailEnded = false
    private val detailDelayMillis = mutableMapOf<String, Long>()
    private var olderRepeats = false
    private val clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(dispatcher.scheduler.currentTime + 1_000_000_000L)
    }

    private fun answer(path: String, method: HttpMethod): String = when {
        path == "/charging" || (path == "/charging/plan" && method == HttpMethod.Post) ->
            """{"plan": "a", "sessions": [$newest], "more_sessions": true, "months": $months,
                "now": [{"car": "c", "session": ${if (charging) """{"id": 2, "car": "c", "started": 200}""" else "null"}}]}"""

        path.startsWith("/charging/sessions/") -> """{"id": ${path.substringAfterLast('/')}, "car": "c", "started": 200, ${if (detailEnded) """"ended": 300, """ else ""}"kwh": 1, "cost": 3.5, "cost_is": "typed", "note": "kept"}"""

        path == "/charging/sessions" && olderRepeats ->
            """{"sessions": [{"id": 2, "car": "c", "started": 200}, {"id": 1, "car": "c", "started": 100}, {"id": 1, "car": "c", "started": 100}], "more_sessions": false}"""

        path == "/charging/sessions" -> """{"sessions": [{"id": 1, "car": "c", "started": 100}], "more_sessions": false}"""

        else -> "{}"
    }

    private fun viewModel(signedIn: Boolean = true): ChargingViewModel {
        val client = HttpClient(MockEngine) {
            engine {
                // On the test's clock: a real hop would let runTest skip its virtual time past the polling.
                dispatcher = this@ChargingViewModelTest.dispatcher
                addHandler { request ->
                    val path = request.url.encodedPath
                    calls += "${request.method.value} $path"
                    detailDelayMillis[path]?.let { delay(it) }
                    if (refuse) respond("{}", HttpStatusCode.InternalServerError) else respond(answer(path, request.method), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
                }
            }
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        connection = FakeConnection(if (signedIn) "http://box" else null)
        return ChargingViewModel(ChargingRelayApi(client), connection, clock)
    }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.settle() = runCurrent()

    @Test
    fun asksOnceOpenedAndAgainOnlyNowAndThen() = runTest(dispatcher) {
        val model = viewModel()
        model.setActive(true)
        eventually { model.uiState.value.loaded }
        assertEquals(1, calls.size)
        advanceTimeBy(60_000)
        settle()
        assertEquals(1, calls.size)
        advanceTimeBy(241_000)
        settle()
        eventually { calls.size == 2 }
        model.setActive(false)
    }

    @Test
    fun asksOftenWhileACarIsCharging() = runTest(dispatcher) {
        charging = true
        val model = viewModel()
        model.setActive(true)
        eventually { model.uiState.value.loaded }
        advanceTimeBy(31_000)
        settle()
        eventually { calls.size == 2 }
        model.setActive(false)
    }

    @Test
    fun theAppOpeningWhileTheCardAsksLetsTheAskFinish() = runTest(dispatcher) {
        val model = viewModel()
        model.setActive(true)
        model.setActive(true)
        eventually { model.uiState.value.loaded }
        assertEquals(1, calls.size)
        model.setActive(false)
    }

    @Test
    fun withNoAnswerYetOpeningAgainAsksStraightAway() = runTest(dispatcher) {
        refuse = true
        val model = viewModel()
        model.setActive(true)
        settle()
        model.setActive(false)
        refuse = false
        model.setActive(true)
        settle()
        assertTrue(model.uiState.value.loaded)
        model.setActive(false)
    }

    @Test
    fun aChargesOpenPageKeepsUpUntilItEnds() = runTest(dispatcher) {
        charging = true
        val model = viewModel()
        model.setActive(true)
        eventually { model.uiState.value.loaded }
        model.openSession(2)
        eventually { model.uiState.value.detail != null }
        assertTrue(model.uiState.value.detail?.session?.isOpen == true)
        detailEnded = true
        advanceTimeBy(31_000)
        settle()
        eventually { model.uiState.value.detail?.session?.isOpen == false }
        val asked = calls.count { it.endsWith("/charging/sessions/2") }
        advanceTimeBy(31_000)
        settle()
        assertEquals(asked, calls.count { it.endsWith("/charging/sessions/2") })
        model.setActive(false)
    }

    @Test
    fun aRefreshWhoseNewestPageEndsElsewhereStartsTheListAgain() = runTest(dispatcher) {
        val model = viewModel()
        model.refresh()
        eventually { model.uiState.value.loaded }
        model.loadMore()
        eventually { model.uiState.value.older.isNotEmpty() }
        newest = """{"id": 3, "car": "c", "started": 300}"""
        model.refresh()
        eventually { model.uiState.value.summary.sessions.single().id == 3L }
        assertEquals(listOf(3L), model.uiState.value.sessions.map { it.id })
        assertTrue(model.uiState.value.moreSessions)
    }

    @Test
    fun aSlowAnswerForTheLastChargeDoesntReplaceTheOneOpenNow() = runTest(dispatcher) {
        detailDelayMillis["/charging/sessions/1"] = 5_000
        val model = viewModel()
        model.openSession(1)
        settle()
        model.openSession(2)
        eventually { model.uiState.value.detail?.session?.id == 2L }
        advanceTimeBy(6_000)
        settle()
        assertEquals(2L, model.uiState.value.detail?.session?.id)
    }

    @Test
    fun aSlowSaveForTheLastChargeDoesntReplaceTheOneOpenNow() = runTest(dispatcher) {
        val model = viewModel()
        model.openSession(1)
        eventually { model.uiState.value.detail?.session?.id == 1L }
        detailDelayMillis["/charging/sessions/1"] = 5_000
        model.saveSession(1, 3.5, null)
        settle()
        model.openSession(2)
        eventually { model.uiState.value.detail?.session?.id == 2L }
        advanceTimeBy(6_000)
        settle()
        assertEquals(2L, model.uiState.value.detail?.session?.id)
        assertEquals(false, model.uiState.value.saving)
    }

    @Test
    fun aRefreshDoesntEndASaveStillOnItsWay() = runTest(dispatcher) {
        detailDelayMillis["/charging/plan"] = 5_000
        val model = viewModel()
        model.refresh()
        eventually { model.uiState.value.loaded }
        model.choosePlan("b")
        settle()
        assertTrue(model.uiState.value.saving)
        model.refresh()
        settle()
        assertTrue(model.uiState.value.saving)
        model.choosePlan("c")
        settle()
        assertEquals(1, calls.count { it.endsWith("/charging/plan") })
        advanceTimeBy(6_000)
        settle()
        assertEquals(false, model.uiState.value.saving)
    }

    @Test
    fun whatTheBanksSaidGoesWhenTheServerCantSayWhosAsking() = runTest(dispatcher) {
        months = """[{"month": "2026-10", "card_fast": 20, "unmatched": 20, "electric_paid": 90}]"""
        val model = viewModel()
        model.refresh()
        eventually { model.uiState.value.loaded }
        assertEquals(20.0, model.uiState.value.summary.months.single().cost)
        connection.serverUnreachable.value = true
        settle()
        val month = model.uiState.value.summary.months.single()
        assertEquals(0.0, month.cost)
        assertNull(month.electricPaid)
        assertNull(month.cardFast)
        connection.serverUnreachable.value = false
        model.refresh()
        eventually { model.uiState.value.summary.months.single().electricPaid == 90.0 }
    }

    @Test
    fun aFailedAskTakesAwayWhatTheBanksSaid() = runTest(dispatcher) {
        months = """[{"month": "2026-10", "card_fast": 20, "unmatched": 20, "electric_paid": 90}]"""
        val model = viewModel()
        model.refresh()
        eventually { model.uiState.value.loaded }
        refuse = true
        model.refresh()
        eventually { model.uiState.value.error != null }
        assertNull(model.uiState.value.summary.months.single().electricPaid)
        assertEquals(1, model.uiState.value.summary.months.size)
    }

    @Test
    fun stopsAskingWhenPutAway() = runTest(dispatcher) {
        val model = viewModel()
        model.setActive(true)
        eventually { model.uiState.value.loaded }
        model.setActive(false)
        val before = calls.size
        advanceTimeBy(3_600_000)
        settle()
        assertEquals(before, calls.size)
    }

    @Test
    fun signedOutSaysSoAndAsksNothing() = runTest(dispatcher) {
        val model = viewModel(signedIn = false)
        model.refresh()
        settle()
        assertTrue(calls.isEmpty())
        assertNotNull(model.uiState.value.error)
        assertEquals(false, model.uiState.value.loaded)
    }

    @Test
    fun aFailedAskKeepsWhatWasShown() = runTest(dispatcher) {
        val model = viewModel()
        model.refresh()
        eventually { model.uiState.value.loaded }
        refuse = true
        model.refresh()
        eventually { model.uiState.value.error != null }
        assertEquals(1, model.uiState.value.summary.sessions.size)
    }

    @Test
    fun aPageRepeatingChargesDoesntListThemTwice() = runTest(dispatcher) {
        olderRepeats = true
        val model = viewModel()
        model.refresh()
        eventually { model.uiState.value.loaded }
        model.loadMore()
        eventually { model.uiState.value.older.isNotEmpty() }
        assertEquals(listOf(2L, 1L), model.uiState.value.sessions.map { it.id })
    }

    @Test
    fun olderChargesJoinTheEndOfTheList() = runTest(dispatcher) {
        val model = viewModel()
        model.refresh()
        eventually { model.uiState.value.loaded }
        assertTrue(model.uiState.value.moreSessions)
        model.loadMore()
        eventually { model.uiState.value.older.isNotEmpty() }
        assertEquals(listOf(2L, 1L), model.uiState.value.sessions.map { it.id })
        assertEquals(false, model.uiState.value.moreSessions)
    }

    @Test
    fun savingAChargeShowsWhatTheRelayKept() = runTest(dispatcher) {
        val model = viewModel()
        model.openSession(2)
        settle()
        model.saveSession(2, 3.5, "kept")
        eventually { model.uiState.value.detail != null }
        assertEquals("kept", model.uiState.value.detail?.session?.note)
        assertTrue("POST /charging/sessions/2" in calls)
    }

    @Test
    fun choosingAPlanTakesTheNewPricesAndDropsOlderCharges() = runTest(dispatcher) {
        val model = viewModel()
        model.refresh()
        eventually { model.uiState.value.loaded }
        model.loadMore()
        eventually { model.uiState.value.older.isNotEmpty() }
        model.choosePlan("a")
        eventually { "POST /charging/plan" in calls && !model.uiState.value.saving }
        assertTrue(model.uiState.value.older.isEmpty())
        assertNull(model.uiState.value.error)
    }

    private fun TestScope.eventually(condition: () -> Boolean) {
        runCurrent()
        assertTrue(condition(), "never came true; calls were $calls")
    }
}
