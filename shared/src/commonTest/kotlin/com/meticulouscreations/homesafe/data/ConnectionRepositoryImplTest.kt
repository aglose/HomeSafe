package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.ConnectionRoute
import com.meticulouscreations.homesafe.domain.model.SavedCredentials
import com.meticulouscreations.homesafe.domain.model.StaleBiometricCredentialsException
import com.meticulouscreations.homesafe.network.FrigateApiClient
import com.meticulouscreations.homesafe.network.NetworkMonitor
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class ConnectionRepositoryImplTest {

    private val tailscaleHost = "100.64.0.1"
    private val localHost = "192.168.68.55"
    private val serverUrl = "http://$tailscaleHost:8971"
    private val localUrl = "http://$localHost:8971"

    /**
     * A fake Frigate reachable on two hosts. Sessions work the way Frigate's do: a login mints a
     * token, and the token is good on *either* host because both are the same server — unless
     * [rogueLocalHost], which makes the LAN address some other Frigate that never issued it.
     */
    private class FakeFrigate(private val tailscaleHost: String, private val localHost: String) {
        var localReachable = true
        var tailscaleReachable = true

        /** The LAN host answers, but it is a different server: it rejects every token this one issued. */
        var rogueLocalHost = false

        /** When true, the server answers the login endpoint but refuses the credentials. */
        var rejectLogin = false

        /** When true, the login endpoint falls over (500) — says nothing about the credentials. */
        var loginBroken = false

        /** When true, `/api/config` answers 500. */
        var configBroken = false

        /** When set, `/api/config` waits for it before answering, so a test can see what happens meanwhile. */
        var configGate: CompletableDeferred<Unit>? = null

        /** Cameras the server reports, name to enabled. */
        var cameras: Map<String, Boolean> = mapOf("front_door" to true, "backyard" to false)

        /** When true, only plain http is answered; https fails at the transport, as a plaintext port does. */
        var httpsRejected = false

        val requests = mutableListOf<Pair<String, String>>()
        private val issuedTokens = mutableSetOf<String>()
        private var nextToken = 0

        /** The server forgot every session it issued (restart, expiry): the next authenticated request gets a 401. */
        fun expireSessions() = issuedTokens.clear()

        val engine = MockEngine { request ->
            val host = request.url.host
            val path = request.url.encodedPath
            requests += host to path
            if (host == localHost && !localReachable) error("No route to host")
            if (host == tailscaleHost && !tailscaleReachable) error("Connection timed out")
            if (httpsRejected && request.url.protocol.name == "https") error("Unable to parse TLS packet header")
            val token = request.headers[HttpHeaders.Cookie]?.substringAfter("frigate_token=", "")?.substringBefore(';')?.takeIf { it.isNotEmpty() }
            val authenticated = token != null && token in issuedTokens && !(host == localHost && rogueLocalHost)
            when {
                path.endsWith("/api/version") -> respond("0.15.0", HttpStatusCode.OK)

                path.endsWith("/api/login") ->
                    if (loginBroken) {
                        respond("", HttpStatusCode.InternalServerError)
                    } else if (rejectLogin) {
                        respond("", HttpStatusCode.Unauthorized)
                    } else {
                        val issued = "t${++nextToken}@$host"
                        issuedTokens += issued
                        respond("", HttpStatusCode.OK, headersOf(HttpHeaders.SetCookie, "frigate_token=$issued; Path=/"))
                    }

                !authenticated -> respond("", HttpStatusCode.Unauthorized)

                path.endsWith("/api/profile") ->
                    respond("""{"username":"andrew","role":"admin"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

                path.endsWith("/api/config") -> {
                    configGate?.await()
                    if (configBroken) {
                        respond("", HttpStatusCode.InternalServerError)
                    } else {
                        val body = cameras.entries.joinToString(",") { (name, enabled) -> "\"$name\":{\"enabled\":$enabled}" }
                        respond("""{"cameras":{$body}}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                }

                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        fun logins(host: String): Int = requests.count { it.first == host && it.second.endsWith("/api/login") }
        fun probes(host: String): Int = requests.count { it.first == host && it.second.endsWith("/api/version") }
        fun sessionChecks(host: String): Int = requests.count { it.first == host && it.second.endsWith("/api/profile") }
        fun configFetches(host: String): Int = requests.count { it.first == host && it.second.endsWith("/api/config") }
    }

    private class FakeNetworkMonitor : NetworkMonitor {
        override val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    }

    private class FakeClock(var nowMillis: Long = 1_700_000_000_000L) : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(nowMillis)
    }

    /** A store that holds one saved login and hands it over without a prompt. */
    private class FakeBiometrics(private var saved: SavedCredentials?) : BiometricCredentialStore {
        override fun isAvailable() = true
        override fun displayName() = "fingerprint"
        override fun hasSavedCredentials() = saved != null
        override suspend fun save(credentials: SavedCredentials): Result<Unit> {
            saved = credentials
            return Result.success(Unit)
        }
        override suspend fun authenticateAndRetrieve(): Result<SavedCredentials> =
            saved?.let { Result.success(it) } ?: Result.failure(IllegalStateException("nothing saved"))
        override fun clear() {
            saved = null
        }
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
    private suspend fun TestScope.eventually(what: String, condition: suspend () -> Boolean) {
        // 15s of real time. A loaded CI runner is far slower than a dev machine at getting the
        // mock engine's thread scheduled, and 5s timed out there while always passing locally.
        repeat(600) {
            advanceUntilIdle()
            if (condition()) return
            withContext(Dispatchers.Default) { delay(25) }
        }
        fail("Timed out waiting for $what")
    }

    /** Lets any in-flight background work land, then asserts nothing about timing. */
    private suspend fun TestScope.settle() {
        withContext(Dispatchers.Default) { delay(200) }
        advanceUntilIdle()
    }

    /**
     * Emits a network change, but only once the repository is actually collecting.
     * [FakeNetworkMonitor.changes] has `replay = 0`, so anything emitted before
     * `ConnectionRepositoryImpl`'s `init` block starts collecting is dropped and the test then
     * waits forever for a probe that will never happen. A dev machine wins that race; a loaded
     * CI runner does not. Tests that deliberately emit before anyone subscribes still proceed.
     */
    private suspend fun TestScope.emitNetworkChange(h: Harness) {
        var waited = 0
        while (h.network.changes.subscriptionCount.value == 0 && waited++ < 200) {
            advanceUntilIdle()
            withContext(Dispatchers.Default) { delay(5) }
        }
        h.network.changes.tryEmit(Unit)
    }

    private inner class Harness(scope: TestScope, val biometrics: BiometricCredentialStore = NoBiometrics) {
        val frigate = FakeFrigate(tailscaleHost, localHost)
        val network = FakeNetworkMonitor()
        val cameraDao = InMemoryCameraDao()
        val historyDao = InMemoryConnectionHistoryDao()
        val clock = FakeClock()
        val cookieStorage = AcceptAllCookiesStorage()
        val apiClient = FrigateApiClient(
            HttpClient(frigate.engine) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                install(HttpCookies) { storage = cookieStorage }
                install(HttpTimeout) { requestTimeoutMillis = 10_000 }
            },
            cookieStorage,
        )
        val repository = ConnectionRepositoryImpl(
            apiClient = apiClient,
            connectionHistoryDao = historyDao,
            cameraDao = cameraDao,
            biometricCredentialStore = biometrics,
            networkMonitor = network,
            appScope = scope.backgroundScope,
            clock = clock,
        )

        suspend fun cameraNames(): List<String> = cameraDao.observeByServer(serverUrl).first().map { it.name }.sorted()
    }

    @Test
    fun usesLocalNetworkWhenTheLanAddressAnswers_withTheTailscaleIssuedSession() = runTest {
        val h = Harness(this)

        val result = h.repository.connect(serverUrl, localUrl, "andrew", "pw")
        eventually("currentServerUrl to follow the active connection") { h.repository.currentServerUrl.value == localUrl }

        assertTrue(result.isSuccess)
        val connection = assertNotNull(h.repository.activeConnection.value)
        assertEquals(ConnectionRoute.LOCAL_NETWORK, connection.route)
        assertEquals(localUrl, connection.activeUrl)
        // The password went to the trusted address only; the LAN host was handed the session it
        // minted and asked to confirm it, and only then used for everything.
        assertEquals(1, h.frigate.logins(tailscaleHost))
        assertEquals(0, h.frigate.logins(localHost))
        assertEquals(1, h.frigate.sessionChecks(localHost))
        assertEquals("frigate_token=t1@$tailscaleHost", h.apiClient.sessionCookieHeader(localUrl))
        // Cameras are cached under the server's identity, not the address in use.
        assertEquals(listOf("backyard", "front_door"), h.cameraNames())
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
        // The LAN was probed alongside the login, not before it.
        assertEquals(1, h.frigate.probes(localHost))
    }

    @Test
    fun aLanHostThatIsNotTheServerGetsNeitherThePasswordNorAnyTraffic() = runTest {
        val h = Harness(this)
        // Someone else's network with the same address range: something answers at the LAN
        // address, but it never issued our session and must not be trusted with anything.
        h.frigate.rogueLocalHost = true

        h.repository.connect(serverUrl, localUrl, "andrew", "pw").getOrThrow()
        advanceUntilIdle()

        val connection = assertNotNull(h.repository.activeConnection.value)
        assertEquals(ConnectionRoute.TAILSCALE, connection.route)
        assertEquals(serverUrl, connection.activeUrl)
        assertEquals(0, h.frigate.logins(localHost))
        assertEquals(1, h.frigate.sessionChecks(localHost))
        assertEquals(0, h.frigate.configFetches(localHost))
    }

    @Test
    fun withTailscaleOffAtHomeTheLanIsTheLastResort() = runTest {
        val h = Harness(this)
        h.frigate.tailscaleReachable = false

        h.repository.connect(serverUrl, localUrl, "andrew", "pw").getOrThrow()
        advanceUntilIdle()

        val connection = assertNotNull(h.repository.activeConnection.value)
        assertEquals(ConnectionRoute.LOCAL_NETWORK, connection.route)
        assertEquals(localUrl, connection.activeUrl)
        // Nothing else could work, so the password went over the LAN — once.
        assertEquals(1, h.frigate.logins(localHost))
        assertEquals(listOf("backyard", "front_door"), h.cameraNames())
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
    fun switchesRouteSilentlyWhenTheNetworkChanges_withoutSigningInAgain() = runTest {
        val h = Harness(this)
        h.repository.connect(serverUrl, localUrl, "andrew", "pw").getOrThrow()
        advanceUntilIdle()
        assertEquals(ConnectionRoute.LOCAL_NETWORK, h.repository.activeConnection.value?.route)

        // Leave home: the LAN address stops answering and the OS reports a network change.
        h.frigate.localReachable = false
        emitNetworkChange(h)
        eventually("switch to Tailscale") { h.repository.currentServerUrl.value == serverUrl }

        assertEquals(ConnectionRoute.TAILSCALE, h.repository.activeConnection.value?.route)
        assertEquals(listOf("backyard", "front_door"), h.cameraNames())

        // Come back: the LAN address answers again.
        h.frigate.localReachable = true
        emitNetworkChange(h)
        eventually("switch back to the local network") { h.repository.currentServerUrl.value == localUrl }

        assertEquals(ConnectionRoute.LOCAL_NETWORK, h.repository.activeConnection.value?.route)
        // The session travelled with the route both ways; the password was sent exactly once, ever.
        assertEquals(1, h.frigate.logins(tailscaleHost))
        assertEquals(0, h.frigate.logins(localHost))
        // Route switches don't pile up history rows; only the user's own sign-in is recorded.
        assertEquals(1, h.historyDao.insertCount)
        // Nor do they re-fetch the camera list.
        assertEquals(1, h.frigate.configFetches(localHost) + h.frigate.configFetches(tailscaleHost))
    }

    @Test
    fun aNetworkChangeThatMovesNothingCausesNoNewSignIn() = runTest {
        val h = Harness(this)
        h.repository.connect(serverUrl, localUrl, "andrew", "pw").getOrThrow()
        advanceUntilIdle()

        emitNetworkChange(h)
        eventually("the reachability probe after the network change") { h.frigate.probes(localHost) >= 2 }
        settle()

        assertEquals(ConnectionRoute.LOCAL_NETWORK, h.repository.activeConnection.value?.route)
        assertEquals(1, h.frigate.logins(tailscaleHost))
        assertEquals(0, h.frigate.logins(localHost))
        assertEquals(1, h.frigate.sessionChecks(localHost))
    }

    @Test
    fun aNetworkChangeBeforeSigningInDoesNothing() = runTest {
        val h = Harness(this)

        emitNetworkChange(h)
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
        assertEquals(listOf("backyard", "front_door"), h.cameraNames())
    }

    @Test
    fun wrongCredentialsAreNotMistakenForASchemeProblem_andNeverReachTheLan() = runTest {
        val h = Harness(this)
        h.frigate.rejectLogin = true

        val result = h.repository.connect(serverUrl, localUrl, "andrew", "wrong")
        advanceUntilIdle()

        assertTrue(result.isFailure)
        // One attempt only: the server answered, so there is no point trying the other scheme —
        // and no point (and some risk) in offering the same password to the LAN host.
        assertEquals(1, h.frigate.logins(tailscaleHost))
        assertEquals(0, h.frigate.logins(localHost))
        assertNull(h.repository.activeConnection.value)
    }

    @Test
    fun aCachedCameraListMakesTheConnectionLiveBeforeTheServerHasListedItsCameras() = runTest {
        val h = Harness(this)
        // A previous session left this server's cameras behind, one of which has since been renamed server-side.
        h.cameraDao.insertAll(
            listOf(
                CameraEntity(serverUrl = serverUrl, name = "front_door", enabled = true),
                CameraEntity(serverUrl = serverUrl, name = "porch", enabled = true),
            ),
        )
        h.frigate.cameras = mapOf("front_door" to true, "backyard" to false)
        val gate = CompletableDeferred<Unit>()
        h.frigate.configGate = gate
        val observed = mutableListOf<List<String>>()
        backgroundScope.launch { h.cameraDao.observeByServer(serverUrl).collect { observed += it.map { c -> c.name }.sorted() } }

        val result = h.repository.connect(serverUrl, localUrl, "andrew", "pw")
        advanceUntilIdle()

        // Connected — on the cached list — while /api/config is still being fetched.
        assertTrue(result.isSuccess)
        assertNotNull(h.repository.activeConnection.value)
        assertEquals(listOf("front_door", "porch"), h.cameraNames())

        gate.complete(Unit)
        eventually("the cached list to be refreshed from the server") { h.cameraNames() == listOf("backyard", "front_door") }

        // The refresh replaced the list in place: the grid never saw an empty list on the way.
        assertFalse(observed.any { it.isEmpty() }, "saw an empty camera list during refresh: $observed")
    }

    @Test
    fun aFirstSignInWaitsForTheCameraListAndFailsWithoutOne() = runTest {
        val h = Harness(this)
        h.frigate.configBroken = true

        val result = h.repository.connect(serverUrl, localUrl, "andrew", "pw")
        advanceUntilIdle()

        assertTrue(result.isFailure)
        assertNull(h.repository.activeConnection.value)
    }

    @Test
    fun comingBackAfterALongAbsenceRenewsAnExpiredSessionWithoutAPrompt() = runTest {
        val h = Harness(this)
        h.repository.connect(serverUrl, localUrl, "andrew", "pw").getOrThrow()
        advanceUntilIdle()
        assertEquals(ConnectionRoute.LOCAL_NETWORK, h.repository.activeConnection.value?.route)

        // Overnight: the server forgot the session, and the phone sat in a pocket.
        h.frigate.expireSessions()
        h.repository.onAppVisibilityChanged(visible = false)
        h.clock.nowMillis += 8 * 60 * 60 * 1000
        h.repository.onAppVisibilityChanged(visible = true)
        eventually("a fresh login") { h.frigate.logins(tailscaleHost) == 2 }
        eventually("the LAN host to hold the renewed session") { h.apiClient.sessionCookieHeader(localUrl) == "frigate_token=t2@$tailscaleHost" }

        // Renewed where the password is allowed to go, then carried to the LAN; the route stands.
        assertEquals(0, h.frigate.logins(localHost))
        assertEquals(ConnectionRoute.LOCAL_NETWORK, h.repository.activeConnection.value?.route)
    }

    @Test
    fun aShortTripToTheBackgroundChecksNothing() = runTest {
        val h = Harness(this)
        h.repository.connect(serverUrl, localUrl, "andrew", "pw").getOrThrow()
        advanceUntilIdle()
        val requestsAfterSignIn = h.frigate.requests.size

        h.repository.onAppVisibilityChanged(visible = false)
        h.clock.nowMillis += 3_000
        h.repository.onAppVisibilityChanged(visible = true)
        settle()

        assertEquals(requestsAfterSignIn, h.frigate.requests.size)
    }

    @Test
    fun comingBackOnADifferentNetworkMovesTheRoute() = runTest {
        val h = Harness(this)
        h.repository.connect(serverUrl, localUrl, "andrew", "pw").getOrThrow()
        advanceUntilIdle()

        // The phone left the house while the app was suspended and no path event arrived.
        h.frigate.localReachable = false
        h.repository.onAppVisibilityChanged(visible = false)
        h.clock.nowMillis += 60_000
        h.repository.onAppVisibilityChanged(visible = true)
        eventually("switch to Tailscale on return") { h.repository.currentServerUrl.value == serverUrl }

        assertEquals(ConnectionRoute.TAILSCALE, h.repository.activeConnection.value?.route)
        assertEquals(1, h.frigate.logins(tailscaleHost))
    }

    @Test
    fun aBiometricSignInTheServerRefusesForgetsTheSavedLogin() = runTest {
        val h = Harness(this, FakeBiometrics(SavedCredentials(serverUrl, "andrew", "old-password", localUrl)))
        h.frigate.rejectLogin = true   // the password was changed on the server since it was saved

        val result = h.repository.signInWithBiometrics()
        advanceUntilIdle()

        assertIs<StaleBiometricCredentialsException>(result.exceptionOrNull())
        // The dead end is cleared: the next launch goes straight to the password form, and a
        // successful sign-in there is offered for saving like the very first one was.
        assertFalse(h.biometrics.hasSavedCredentials())
        assertNull(h.repository.activeConnection.value)
    }

    @Test
    fun aServerErrorDuringBiometricSignInKeepsTheSavedLogin() = runTest {
        val h = Harness(this, FakeBiometrics(SavedCredentials(serverUrl, "andrew", "pw", localUrl)))
        h.frigate.loginBroken = true

        val result = h.repository.signInWithBiometrics()
        advanceUntilIdle()

        assertTrue(result.isFailure)
        assertFalse(result.exceptionOrNull() is StaleBiometricCredentialsException)
        // A 500 says nothing about the password; forgetting it would only add a retype.
        assertTrue(h.biometrics.hasSavedCredentials())
    }

    @Test
    fun aBiometricSignInTheServerAcceptsSignsInWithTheSavedLogin() = runTest {
        val h = Harness(this, FakeBiometrics(SavedCredentials(serverUrl, "andrew", "pw", localUrl)))
        var unlocked = false

        val result = h.repository.signInWithBiometrics(onCredentialsUnlocked = { unlocked = true })
        advanceUntilIdle()

        assertTrue(unlocked)
        assertEquals("andrew", result.getOrThrow().username)
        assertTrue(h.biometrics.hasSavedCredentials())
        assertNotNull(h.repository.activeConnection.value)
    }
}
