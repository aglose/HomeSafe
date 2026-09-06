package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.SavedCredentials

import com.meticulouscreations.homesafe.domain.model.ConnectionRoute
import com.meticulouscreations.homesafe.network.FrigateApiClient
import com.meticulouscreations.homesafe.network.NetworkMonitor
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionRepositoryImplTest {

    private val tailscaleHost = "100.64.0.1"
    private val localHost = "192.168.68.55"
    private val serverUrl = "http://$tailscaleHost:8971"
    private val localUrl = "http://$localHost:8971"

    /** A fake Frigate reachable on two hosts; the LAN one can be switched off to simulate leaving home. */
    private class FakeFrigate {
        var localReachable = true
        /** When true, the server answers the login endpoint but refuses the credentials. */
        var rejectLogin = false
        val requests = mutableListOf<Pair<String, String>>()

        /** When true, only plain http is answered; https fails at the transport, as a plaintext port does. */
        var httpsRejected = false

        val engine = MockEngine { request ->
            val host = request.url.host
            val path = request.url.encodedPath
            requests += host to path
            if (host == "192.168.68.55" && !localReachable) error("No route to host")
            if (httpsRejected && request.url.protocol.name == "https") error("Unable to parse TLS packet header")
            when {
                path.endsWith("/api/version") -> respond("0.15.0", HttpStatusCode.OK)
                path.endsWith("/api/login") ->
                    if (rejectLogin) {
                        respond("", HttpStatusCode.Unauthorized)
                    } else {
                        respond("", HttpStatusCode.OK, headersOf(HttpHeaders.SetCookie, "frigate_token=token-for-$host; Path=/"))
                    }
                path.endsWith("/api/config") ->
                    respond(
                        """{"cameras":{"front_door":{"enabled":true},"backyard":{"enabled":false}}}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        val schemes = mutableListOf<String>()

        fun logins(host: String): Int = requests.count { it.first == host && it.second.endsWith("/api/login") }
        fun probes(host: String): Int = requests.count { it.first == host && it.second.endsWith("/api/version") }
    }

    private class FakeNetworkMonitor : NetworkMonitor {
        override val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    }

    private object NoBiometrics : BiometricCredentialStore {
        override fun isAvailable() = false
        override fun displayName() = "biometrics"
        override fun hasSavedCredentials() = false
        override suspend fun save(credentials: SavedCredentials) = Result.failure<Unit>(UnsupportedOperationException())
        override suspend fun authenticateAndRetrieve() = Result.failure<SavedCredentials>(UnsupportedOperationException())
        override fun clear() = Unit
    }

    /**
     * The mock HTTP engine completes on a real thread, so virtual-time `advanceUntilIdle()` alone
     * can return before a sign-in or route switch has landed. Alternate driving the test
     * scheduler with short real-time waits until [condition] holds.
     */
    private suspend fun TestScope.eventually(what: String, condition: () -> Boolean) {
        repeat(200) {
            advanceUntilIdle()
            if (condition()) return
            withContext(Dispatchers.Default) { delay(25) }
        }
        fail("Timed out waiting for $what")
    }

    private class Harness(scope: TestScope) {
        val frigate = FakeFrigate()
        val network = FakeNetworkMonitor()
        val cameraDao = InMemoryCameraDao()
        val historyDao = InMemoryConnectionHistoryDao()
        val apiClient = FrigateApiClient(
            HttpClient(frigate.engine) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                install(HttpCookies)
                install(HttpTimeout) { requestTimeoutMillis = 10_000 }
            },
        )
        val repository = ConnectionRepositoryImpl(
            apiClient = apiClient,
            connectionHistoryDao = historyDao,
            cameraDao = cameraDao,
            biometricCredentialStore = NoBiometrics,
            networkMonitor = network,
            appScope = scope.backgroundScope,
        )
    }

    @Test
    fun usesLocalNetworkWhenTheLanAddressAnswers() = runTest {
        val h = Harness(this)

        val result = h.repository.connect(serverUrl, localUrl, "andrew", "pw")
        eventually("currentServerUrl to follow the active connection") { h.repository.currentServerUrl.value == localUrl }

        assertTrue(result.isSuccess)
        val connection = assertNotNull(h.repository.activeConnection.value)
        assertEquals(ConnectionRoute.LOCAL_NETWORK, connection.route)
        assertEquals(localUrl, connection.activeUrl)
        assertEquals(1, h.frigate.logins(localHost))
        assertEquals(0, h.frigate.logins(tailscaleHost))
        // The session cookie lives on the host we actually signed in to, so native players can reuse it.
        assertEquals("frigate_token=token-for-$localHost", h.apiClient.sessionCookieHeader(localUrl))
        // Cameras are cached under the server's identity, not the address in use.
        assertEquals(listOf("backyard", "front_door"), h.cameraDao.observeByServer(serverUrl).first().map { it.name }.sorted())
        assertEquals(localUrl, h.repository.mostRecentConnection.first()?.localUrl)
        assertEquals(localUrl, result.getOrThrow().localUrl)
    }

    @Test
    fun fallsBackToTailscaleWhenTheLanAddressDoesNotAnswer() = runTest {
        val h = Harness(this)
        h.frigate.localReachable = false

        h.repository.connect(serverUrl, localUrl, "andrew", "pw").getOrThrow()
        advanceUntilIdle()

        val connection = assertNotNull(h.repository.activeConnection.value)
        assertEquals(ConnectionRoute.TAILSCALE, connection.route)
        assertEquals(serverUrl, connection.activeUrl)
        assertEquals(localUrl, connection.localUrl)
        assertEquals(1, h.frigate.logins(tailscaleHost))
        assertEquals(0, h.frigate.logins(localHost))
    }

    @Test
    fun withoutALanAddressNothingIsProbed() = runTest {
        val h = Harness(this)

        h.repository.connect(serverUrl, "  ", "andrew", "pw").getOrThrow()
        advanceUntilIdle()

        val connection = assertNotNull(h.repository.activeConnection.value)
        assertEquals(ConnectionRoute.TAILSCALE, connection.route)
        assertNull(connection.localUrl)
        assertEquals(0, h.frigate.probes(localHost))
        assertEquals(0, h.frigate.probes(tailscaleHost))
    }

    @Test
    fun switchesRouteSilentlyWhenTheNetworkChanges() = runTest {
        val h = Harness(this)
        h.repository.connect(serverUrl, localUrl, "andrew", "pw").getOrThrow()
        advanceUntilIdle()
        assertEquals(ConnectionRoute.LOCAL_NETWORK, h.repository.activeConnection.value?.route)

        // Leave home: the LAN address stops answering and the OS reports a network change.
        h.frigate.localReachable = false
        h.network.changes.tryEmit(Unit)
        eventually("switch to Tailscale") { h.repository.currentServerUrl.value == serverUrl }

        assertEquals(ConnectionRoute.TAILSCALE, h.repository.activeConnection.value?.route)
        assertEquals(1, h.frigate.logins(tailscaleHost))
        assertEquals(listOf("backyard", "front_door"), h.cameraDao.observeByServer(serverUrl).first().map { it.name }.sorted())

        // Come back: the LAN address answers again.
        h.frigate.localReachable = true
        h.network.changes.tryEmit(Unit)
        eventually("switch back to the local network") { h.repository.currentServerUrl.value == localUrl }

        assertEquals(ConnectionRoute.LOCAL_NETWORK, h.repository.activeConnection.value?.route)
        assertEquals(2, h.frigate.logins(localHost))
        // Route switches don't pile up history rows; only the user's own sign-in is recorded.
        assertEquals(1, h.historyDao.insertCount)
    }

    @Test
    fun aNetworkChangeThatMovesNothingCausesNoNewSignIn() = runTest {
        val h = Harness(this)
        h.repository.connect(serverUrl, localUrl, "andrew", "pw").getOrThrow()
        advanceUntilIdle()

        h.network.changes.tryEmit(Unit)
        eventually("the reachability probe after the network change") { h.frigate.probes(localHost) >= 2 }
        withContext(Dispatchers.Default) { delay(200) }
        advanceUntilIdle()

        assertEquals(ConnectionRoute.LOCAL_NETWORK, h.repository.activeConnection.value?.route)
        assertEquals(1, h.frigate.logins(localHost))
        assertEquals(0, h.frigate.logins(tailscaleHost))
    }

    @Test
    fun aNetworkChangeBeforeSigningInDoesNothing() = runTest {
        val h = Harness(this)

        h.network.changes.tryEmit(Unit)
        advanceUntilIdle()

        assertNull(h.repository.activeConnection.value)
        assertTrue(h.frigate.requests.isEmpty())
    }

    @Test
    fun healsASavedHttpsUrlWhenTheServerNoLongerSpeaksTls() = runTest {
        val h = Harness(this)
        h.frigate.localReachable = false   // away from home, so the Tailscale URL is the one used
        h.frigate.httpsRejected = true     // ...and the server has since turned TLS off

        val result = h.repository.connect("https://$tailscaleHost:8971", localUrl, "andrew", "pw")
        eventually("the https URL to heal to http") { h.repository.currentServerUrl.value == serverUrl }

        assertTrue(result.isSuccess)
        // The corrected URL is what gets remembered, so the repair sticks for next launch.
        assertEquals(serverUrl, result.getOrThrow().serverUrl)
        assertEquals(serverUrl, h.repository.mostRecentConnection.first()?.serverUrl)
        assertEquals(listOf("backyard", "front_door"), h.cameraDao.observeByServer(serverUrl).first().map { it.name }.sorted())
    }

    @Test
    fun wrongCredentialsAreNotMistakenForASchemeProblem() = runTest {
        val h = Harness(this)
        h.frigate.rejectLogin = true

        val result = h.repository.connect(serverUrl, localUrl, "andrew", "wrong")
        advanceUntilIdle()

        assertTrue(result.isFailure)
        // One attempt only: the server answered, so there is no point trying the other scheme.
        assertEquals(1, h.frigate.logins(localHost))
        assertNull(h.repository.activeConnection.value)
    }
}
