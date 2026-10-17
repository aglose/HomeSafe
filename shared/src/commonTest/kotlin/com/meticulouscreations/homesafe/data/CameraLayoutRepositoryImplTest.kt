package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.ActiveConnection
import com.meticulouscreations.homesafe.domain.model.CameraLayout
import com.meticulouscreations.homesafe.domain.model.CameraOrientation
import com.meticulouscreations.homesafe.domain.model.ConnectionRecord
import com.meticulouscreations.homesafe.domain.model.ConnectionRoute
import com.meticulouscreations.homesafe.domain.model.SavedCredentials
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.biometric_name_generic
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class CameraLayoutRepositoryImplTest {

    private class FakeConnection(serverUrl: String?) : ConnectionRepository {
        override val activeConnection = MutableStateFlow(serverUrl?.let { ActiveConnection(it, localUrl = "https://192.168.1.10:8971", route = ConnectionRoute.LOCAL_NETWORK) })
        override val currentServerUrl = MutableStateFlow(activeConnection.value?.activeUrl)
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

    private val server = "https://box.tailnet.ts.net:8971"

    @Test
    fun anOrderKeepsEachCamerasTurnAndATurnKeepsItsPlace() = runTest {
        val dao = InMemoryCameraListDao()
        val repository = CameraLayoutRepositoryImpl(dao, FakeConnection(server))

        repository.setOrientation("kids_room", CameraOrientation(quarterTurns = 1, tiltDegrees = -8f))
        repository.saveOrder(listOf("kitchen", "kids_room"))
        repository.setOrientation("kitchen", CameraOrientation(quarterTurns = 2))

        assertEquals(
            mapOf(
                "kitchen" to CameraLayout(position = 0, orientation = CameraOrientation(quarterTurns = 2)),
                "kids_room" to CameraLayout(position = 1, orientation = CameraOrientation(quarterTurns = 1, tiltDegrees = -8f)),
            ),
            repository.observeLayouts().first(),
        )
    }

    @Test
    fun layoutsAreKeptByTheServersIdentityNotTheRouteInUse() = runTest {
        val dao = InMemoryCameraListDao()
        CameraLayoutRepositoryImpl(dao, FakeConnection(server)).saveOrder(listOf("kitchen"))
        assertEquals(listOf("kitchen"), dao.observeLayouts(server).first().map { it.name }, "filed under the Tailscale identity although the LAN is the route")
    }

    @Test
    fun signedOutThereAreNoneAndNothingIsSaved() = runTest {
        val dao = InMemoryCameraListDao()
        val repository = CameraLayoutRepositoryImpl(dao, FakeConnection(null))
        repository.saveOrder(listOf("kitchen"))
        assertEquals(emptyMap(), repository.observeLayouts().first())
        assertEquals(emptyList(), dao.observeLayouts(server).first())
    }
}
