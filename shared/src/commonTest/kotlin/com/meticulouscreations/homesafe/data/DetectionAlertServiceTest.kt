package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.ActiveConnection
import com.meticulouscreations.homesafe.domain.model.AlertSettings
import com.meticulouscreations.homesafe.domain.model.ConnectionRecord
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.domain.repository.SettingsRepository
import com.meticulouscreations.homesafe.network.FrigateApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

@OptIn(ExperimentalCoroutinesApi::class)
class DetectionAlertServiceTest {

    private class FakeConnection(url: String?) : ConnectionRepository {
        override val currentServerUrl = MutableStateFlow(url)
        override val activeConnection: StateFlow<ActiveConnection?> = MutableStateFlow(null)
        override val mostRecentConnection: Flow<ConnectionRecord?> = flowOf(null)
        override val biometricLoginAvailable = false
        override val biometricDisplayName = "biometrics"
        override fun hasSavedBiometricCredentials() = false
        override suspend fun connect(serverUrl: String, localUrl: String?, username: String, password: String) = fail("unused")
        override suspend fun signInWithBiometrics() = fail("unused")
        override suspend fun saveBiometricCredentials(credentials: SavedCredentials) = fail("unused")
        override fun forgetBiometricCredentials() = Unit
    }

    private class FakeSettings(initial: AlertSettings) : SettingsRepository {
        val state = MutableStateFlow(initial)
        override fun observeSettings(): Flow<AlertSettings> = state
        override suspend fun updateSettings(settings: AlertSettings) { state.value = settings }
    }

    private class FakeNotifier(override val isSupported: Boolean = true) : AlertNotifier {
        val posted = mutableListOf<AlertNotification>()
        override suspend fun permissionStatus() = NotificationPermission.GRANTED
        override suspend fun requestPermission() = true
        override fun openSystemSettings() = Unit
        override fun notify(notification: AlertNotification) { posted += notification }
    }

    /** A fake Frigate whose `/api/events` honours `after` the way the real one does (start_time strictly after). */
    private class Harness(scope: TestScope, settings: AlertSettings, now: Double = 1_000_000.0, supported: Boolean = true) {
        val events = mutableListOf<Triple<String, String, Double>>() // id, label, start
        val afters = mutableListOf<String>()
        private val engine = MockEngine { req ->
            when {
                req.url.encodedPath.endsWith("/api/events") -> {
                    val after = req.url.parameters["after"]!!.also { afters += it }.toDouble()
                    val body = events.filter { it.third > after }.sortedByDescending { it.third }.joinToString(",", "[", "]") { (id, label, start) ->
                        """{"id":"$id","label":"$label","camera":"amcrest_1","start_time":$start,"end_time":null,"has_clip":false,"has_snapshot":false}"""
                    }
                    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
                req.url.encodedPath.contains("/thumbnail.jpg") -> respond(byteArrayOf(1, 2, 3), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/jpeg"))
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        private val client = HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        val connection = FakeConnection("http://192.168.68.55:8971")
        val settingsRepo = FakeSettings(settings)
        val notifier = FakeNotifier(supported)
        var clockNow = now
        val service = DetectionAlertService(
            apiClient = FrigateApiClient(client),
            connectionRepository = connection,
            settingsRepository = settingsRepo,
            notifier = notifier,
            scope = scope.backgroundScope,
            clock = { clockNow },
            pollIntervalMs = 100,
        )
    }

    private suspend fun TestScope.eventually(what: String, cond: suspend () -> Boolean) {
        repeat(200) { advanceUntilIdle(); if (cond()) return; withContext(Dispatchers.Default) { delay(25) } }
        fail("Timed out waiting for $what")
    }

    private suspend fun TestScope.settle() {
        repeat(8) { advanceUntilIdle(); withContext(Dispatchers.Default) { delay(25) } }
    }

    private val on = AlertSettings(pushNotificationsEnabled = true, notifyPeople = true, notifyVehicles = true, notifyAnimals = false)

    @Test
    fun onlyDetectionsAfterSwitchingOnNotifyAndEachOnlyOnce() = runTest {
        val h = Harness(this, on)
        h.events += Triple("old", "person", 999_990.0)   // before the baseline: history, not news
        h.service.start()
        eventually("first poll") { h.afters.isNotEmpty() }
        assertEquals("1000000.000", h.afters.first(), "the baseline is the moment polling started")
        assertTrue(h.notifier.posted.isEmpty())

        h.events += Triple("fresh", "person", 1_000_005.0)
        eventually("the new detection") { h.notifier.posted.size == 1 }
        val posted = h.notifier.posted.single()
        assertEquals("fresh", posted.id)
        assertEquals("Person detected", posted.title)
        assertTrue(posted.body.startsWith("Front Door · "), posted.body)
        assertEquals(listOf<Byte>(1, 2, 3), posted.thumbnail!!.toList())

        settle()
        assertEquals(1, h.notifier.posted.size, "later polls must not repeat it")
    }

    @Test
    fun categoryTogglesFilterWithoutRestartingTheBaseline() = runTest {
        val h = Harness(this, on)
        h.service.start()
        eventually("first poll") { h.afters.isNotEmpty() }

        h.events += Triple("dog", "dog", 1_000_001.0)   // animals are off in `on`
        settle()
        assertTrue(h.notifier.posted.isEmpty(), "an unwanted category is silent")

        h.settingsRepo.state.value = on.copy(notifyAnimals = true)
        h.events += Triple("dog2", "dog", 1_000_002.0)
        eventually("the second dog") { h.notifier.posted.map { it.id } == listOf("dog2") }
        assertTrue(h.notifier.posted.none { it.id == "dog" }, "flipping a category doesn't replay what was skipped")
    }

    @Test
    fun switchingOffStopsPollingAndOnAgainStartsFresh() = runTest {
        val h = Harness(this, on)
        h.service.start()
        eventually("first poll") { h.afters.isNotEmpty() }

        h.settingsRepo.state.value = on.copy(pushNotificationsEnabled = false)
        settle()
        val pollsWhenOff = h.afters.size
        settle()
        assertEquals(pollsWhenOff, h.afters.size, "no requests while off")

        h.events += Triple("while_off", "person", 1_000_010.0)
        h.clockNow = 1_000_020.0
        h.settingsRepo.state.value = on
        eventually("polling resumes") { h.afters.size > pollsWhenOff }
        settle()
        assertTrue(h.notifier.posted.isEmpty(), "what happened while off is history, not a burst on re-enable")
        assertEquals("1000020.000", h.afters.last())
    }

    @Test
    fun unsupportedPlatformNeverPolls() = runTest {
        val h = Harness(this, on, supported = false)
        h.service.start()
        settle()
        assertTrue(h.afters.isEmpty())
    }

    @Test
    fun testNotificationGoesStraightToTheNotifier() = runTest {
        val h = Harness(this, on.copy(pushNotificationsEnabled = false))
        h.service.sendTestNotification()
        assertEquals("test", h.notifier.posted.single().id)
    }
}
