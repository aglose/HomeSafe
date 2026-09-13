package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.ActiveConnection
import com.meticulouscreations.homesafe.domain.model.AlertSettings
import com.meticulouscreations.homesafe.domain.model.ConnectionRecord
import com.meticulouscreations.homesafe.domain.model.SavedCredentials
import com.meticulouscreations.homesafe.domain.platform.DeviceInfo
import com.meticulouscreations.homesafe.domain.platform.PushTokenProvider
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.domain.repository.SettingsRepository
import com.meticulouscreations.homesafe.network.PushRelayApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.content.TextContent
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
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceRegistrarTest {

    private class FakeConnection(url: String?, recent: ConnectionRecord? = null) : ConnectionRepository {
        override val currentServerUrl = MutableStateFlow(url)
        override val activeConnection: StateFlow<ActiveConnection?> = MutableStateFlow(null)
        override val mostRecentConnection: Flow<ConnectionRecord?> = flowOf(recent)
        override val biometricLoginAvailable = false
        override val biometricDisplayName = "biometrics"
        override fun hasSavedBiometricCredentials() = false
        override suspend fun connect(serverUrl: String, localUrl: String?, username: String, password: String) = fail("unused")
        override suspend fun signInWithBiometrics(onCredentialsUnlocked: () -> Unit) = fail("unused")
        override suspend fun saveBiometricCredentials(credentials: SavedCredentials) = fail("unused")
        override fun forgetBiometricCredentials() = Unit
        override fun onAppVisibilityChanged(visible: Boolean) = Unit
    }

    private class FakeSettings : SettingsRepository {
        val settings = MutableStateFlow(AlertSettings.DEFAULT)
        override fun observeSettings(): Flow<AlertSettings> = settings
        override suspend fun updateSettings(settings: AlertSettings) {
            this.settings.value = settings
        }
    }

    private class FakeTokens(private val value: String?) : PushTokenProvider {
        override val isSupported = value != null
        override suspend fun token() = value
    }

    private class FakeInfo(override val platform: String, override val name: String, override val build: String) : DeviceInfo

    private class Harness(scope: TestScope, connection: ConnectionRepository, token: String? = "fcm-1", info: DeviceInfo = FakeInfo("android", "Google Pixel", "release")) {
        val posts = mutableListOf<String>()
        val authorizations = mutableListOf<String?>()
        private val engine = MockEngine { req ->
            check(req.url.encodedPath == "/devices") { req.url.encodedPath }
            posts += (req.body as TextContent).text
            authorizations += req.headers[HttpHeaders.Authorization]
            respond("""{"ok":true,"device_id":"ignored","secret":"secret-${posts.size}"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        private val client = HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        val dao = InMemorySettingsDao()
        val identity = DeviceIdentityStore(dao)
        val settings = FakeSettings()
        val registrar = DeviceRegistrar(PushRelayApi(client), identity, FakeTokens(token), info, connection, settings, scope.backgroundScope)
    }

    /**
     * Lets what the harness launched on `backgroundScope` run: with this coroutines-test version
     * `advanceUntilIdle()` drives only the foreground, while yielding (and a real hop, for the
     * mock HTTP engine) hands the scheduler to background work too.
     */
    private suspend fun settle() {
        repeat(5) {
            repeat(20) { yield() }
            withContext(Dispatchers.Default) { delay(10) }
        }
        repeat(20) { yield() }
    }

    @Test
    fun registersOnceAServerIsActiveAndKeepsTheSecret() = runTest {
        val connection = FakeConnection(url = null)
        val h = Harness(this, connection)
        h.registrar.start()
        settle()
        assertTrue(h.posts.isEmpty(), "signed out: nothing to register with")

        connection.currentServerUrl.value = "http://192.168.68.55:8971"
        settle()
        val body = h.posts.single()
        val id = h.dao.getDeviceIdentity()!!.deviceId
        assertTrue(""""device_id":"$id"""" in body, body)
        assertTrue(""""token":"fcm-1"""" in body, body)
        assertTrue(""""platform":"android"""" in body && """"name":"Google Pixel"""" in body && """"build":"release"""" in body, body)
        assertEquals(null, h.authorizations.single(), "first time: the session cookie")
        assertEquals("secret-1", h.dao.getDeviceIdentity()!!.secret)
    }

    @Test
    fun reRegistersWhenThePreferenceOrTheRouteChangesBearingItsSecret() = runTest {
        val connection = FakeConnection(url = "http://192.168.68.55:8971")
        val h = Harness(this, connection)
        h.registrar.start()
        settle()
        assertEquals(1, h.posts.size)

        h.settings.settings.value = AlertSettings.DEFAULT.copy(quietFamiliarPeople = true)
        settle()
        assertEquals(2, h.posts.size)
        assertTrue(""""quiet_familiar":true""" in h.posts[1], h.posts[1])
        assertEquals("Bearer secret-1", h.authorizations[1], "from now on the secret proves who we are")

        connection.currentServerUrl.value = "http://100.99.163.71:8971"
        settle()
        assertEquals(3, h.posts.size, "LAN -> Tailscale flip: the relay is the same box, but say so on the new address")
    }

    @Test
    fun anIphoneRegistersWithoutAPushToken() = runTest {
        val h = Harness(this, FakeConnection("http://192.168.68.55:8971"), token = null, info = FakeInfo("ios", "Apple iPhone", "debug"))
        h.registrar.start()
        settle()
        val body = h.posts.single()
        assertTrue(""""token":null""" in body, body)
        assertTrue(""""platform":"ios"""" in body, body)
    }

    @Test
    fun aRotatedTokenIsPushedToTheLastKnownServerEvenWithNoSession() = runTest {
        val recent = ConnectionRecord("http://100.99.163.71:8971", "http://192.168.68.55:8971", 0)
        val h = Harness(this, FakeConnection(url = null, recent = recent))
        h.identity.saveSecret("old-secret")
        val result = h.registrar.onPushTokenChanged("fcm-2")
        assertTrue(result.isSuccess, result.toString())
        assertTrue(""""token":"fcm-2"""" in h.posts.single(), h.posts.single())
        assertEquals("Bearer old-secret", h.authorizations.single())
        assertEquals("secret-1", h.dao.getDeviceIdentity()!!.secret)
    }
}
