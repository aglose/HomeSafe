package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.ActiveConnection
import com.meticulouscreations.homesafe.domain.model.CameraPlacement
import com.meticulouscreations.homesafe.domain.model.ConnectionRecord
import com.meticulouscreations.homesafe.domain.model.ConnectionRoute
import com.meticulouscreations.homesafe.domain.model.SavedCredentials
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.network.FrigateApiClient
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.biometric_name_generic
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class NestCameraRepositoryTest {

    private val nestStreams = """{
      "living_room":{"producers":[{"url":"nest:?client_id=id&client_secret=s&refresh_token=t&project_id=p&device_id=d1"}]},
      "front_door":{"producers":[{"url":"rtsp://cam/1"}]}
    }"""

    /** The connection as the repository's two flows can briefly disagree on it: [currentServerUrl] a step behind [activeConnection]. */
    private class FakeConnection(connection: ActiveConnection?, current: String?) : ConnectionRepository {
        override val activeConnection = MutableStateFlow(connection)
        override val currentServerUrl = MutableStateFlow(current)
        override val mostRecentConnection: Flow<ConnectionRecord?> = flowOf(null)
        override val biometricLoginAvailable = false
        override val biometricDisplayName = Res.string.biometric_name_generic
        override fun hasSavedBiometricCredentials() = false
        override suspend fun connect(serverUrl: String, localUrl: String?, username: String, password: String) = fail("unused")
        override suspend fun signInWithBiometrics(onCredentialsUnlocked: () -> Unit) = fail("unused")
        override suspend fun saveBiometricCredentials(credentials: SavedCredentials) = fail("unused")
        override fun forgetBiometricCredentials() = Unit
        override fun onAppVisibilityChanged(visible: Boolean) = Unit
    }

    private fun repository(connection: FakeConnection, status: HttpStatusCode = HttpStatusCode.OK, requests: MutableList<String> = mutableListOf()) =
        NestCameraRepository(
            FrigateApiClient(
                HttpClient(
                    MockEngine { request ->
                        requests += request.url.toString()
                        respond(nestStreams, status, headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                ),
            ),
            connection,
        )

    private val onTheLan = ActiveConnection(serverUrl = "https://box.tailnet.ts.net:8971", localUrl = "https://192.168.1.10:8971", route = ConnectionRoute.LOCAL_NETWORK)

    @Test
    fun nestStreamsBecomeIndoorCameras() = runTest {
        val cameras = repository(FakeConnection(onTheLan, onTheLan.activeUrl)).observeIndoorCameras().first()
        assertEquals(listOf("living_room"), cameras?.map { it.name })
        assertEquals(CameraPlacement.Indoor, cameras?.single()?.placement)
    }

    @Test
    fun theServerIsAskedOverTheConnectionsOwnRoute() = runTest {
        // currentServerUrl still names the route just left; the connection itself is on the LAN.
        val requests = mutableListOf<String>()
        val repo = repository(FakeConnection(onTheLan, current = "https://box.tailnet.ts.net:8971"), requests = requests)
        repo.observeIndoorCameras().first()
        repo.refresh()
        assertEquals(listOf("http://192.168.1.10:1984/api/streams", "http://192.168.1.10:1984/api/streams"), requests)
    }

    @Test
    fun aFailedFirstAnswerIsAnEmptyListNotAnEndlessWait() = runTest {
        val cameras = repository(FakeConnection(onTheLan, onTheLan.activeUrl), status = HttpStatusCode.BadGateway).observeIndoorCameras().first()
        assertEquals(emptyList(), cameras)
    }

    @Test
    fun signedOutThereAreNone() = runTest {
        assertEquals(emptyList(), repository(FakeConnection(null, null)).observeIndoorCameras().first())
    }
}
