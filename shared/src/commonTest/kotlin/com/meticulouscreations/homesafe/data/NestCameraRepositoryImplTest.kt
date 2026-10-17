package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.ActiveConnection
import com.meticulouscreations.homesafe.domain.model.CameraPlacement
import com.meticulouscreations.homesafe.domain.model.CameraSource
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

class NestCameraRepositoryImplTest {

    private val nestStreams = """{
      "living_room":{"producers":[{"url":"nest:?client_id=id&client_secret=s&refresh_token=t&project_id=p&device_id=d1"}]},
      "outdoor_doorbell":{"producers":[{"url":"nest:?client_id=id&client_secret=s&refresh_token=t&project_id=p&device_id=d2"}]},
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

    private fun repository(
        connection: FakeConnection,
        status: HttpStatusCode = HttpStatusCode.OK,
        requests: MutableList<String> = mutableListOf(),
        dao: CameraListDao = InMemoryCameraListDao(),
    ) = NestCameraRepositoryImpl(
        FrigateApiClient(
            HttpClient(
                MockEngine { request ->
                    requests += request.url.toString()
                    respond(nestStreams, status, headersOf(HttpHeaders.ContentType, "application/json"))
                },
            ),
        ),
        connection,
        dao,
    )

    private val onTheLan = ActiveConnection(serverUrl = "https://box.tailnet.ts.net:8971", localUrl = "https://192.168.1.10:8971", route = ConnectionRoute.LOCAL_NETWORK)

    @Test
    fun nestStreamsAreIndoorsUnlessNamedOutdoor() = runTest {
        val cameras = repository(FakeConnection(onTheLan, onTheLan.activeUrl)).observeNestCameras().first { !it.isNullOrEmpty() }.orEmpty()
        assertEquals(
            listOf("living_room" to CameraPlacement.Indoor, "outdoor_doorbell" to CameraPlacement.Outdoor),
            cameras.map { it.name to it.placement },
        )
        assertEquals(setOf(CameraSource.Nest), cameras.map { it.source }.toSet())
    }

    @Test
    fun theCamerasKeptFromLastTimeComeFirstWithoutWaitingForTheServer() = runTest {
        val dao = InMemoryCameraListDao()
        dao.insertNestCameras(listOf(NestCameraEntity(onTheLan.serverUrl, "kitchen")))
        // The server doesn't answer at all: what was kept is there regardless.
        val cameras = repository(FakeConnection(onTheLan, onTheLan.activeUrl), status = HttpStatusCode.BadGateway, dao = dao)
            .observeNestCameras().first()
        assertEquals(listOf("kitchen"), cameras?.map { it.name })
    }

    @Test
    fun anAnswerReplacesWhatWasKept() = runTest {
        val dao = InMemoryCameraListDao()
        dao.insertNestCameras(listOf(NestCameraEntity(onTheLan.serverUrl, "kitchen")))
        repository(FakeConnection(onTheLan, onTheLan.activeUrl), dao = dao).refresh(onTheLan)
        assertEquals(listOf("living_room", "outdoor_doorbell"), dao.observeNestCameras(onTheLan.serverUrl).first().map { it.name }.sorted())
    }

    @Test
    fun aFailedAnswerKeepsTheList() = runTest {
        val dao = InMemoryCameraListDao()
        dao.insertNestCameras(listOf(NestCameraEntity(onTheLan.serverUrl, "kitchen")))
        repository(FakeConnection(onTheLan, onTheLan.activeUrl), status = HttpStatusCode.BadGateway, dao = dao).refresh(onTheLan)
        assertEquals(listOf("kitchen"), dao.observeNestCameras(onTheLan.serverUrl).first().map { it.name })
    }

    @Test
    fun theServerIsAskedOverTheConnectionsOwnRoute() = runTest {
        // currentServerUrl still names the route just left; the connection itself is on the LAN.
        val requests = mutableListOf<String>()
        val repo = repository(FakeConnection(onTheLan, current = "https://box.tailnet.ts.net:8971"), requests = requests)
        repo.refresh()
        assertEquals(listOf("http://192.168.1.10:1984/api/streams"), requests)
    }

    @Test
    fun signedOutThereAreNone() = runTest {
        assertEquals(emptyList(), repository(FakeConnection(null, null)).observeNestCameras().first())
    }
}
