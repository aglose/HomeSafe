package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.ActiveConnection
import com.meticulouscreations.homesafe.domain.model.ConnectionRecord
import com.meticulouscreations.homesafe.domain.model.ConnectionRoute
import com.meticulouscreations.homesafe.domain.model.SavedCredentials
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.ui.components.LivePlayerPrefetch
import com.meticulouscreations.homesafe.ui.components.LivePrefetch
import com.meticulouscreations.homesafe.ui.components.VideoSource
import com.meticulouscreations.homesafe.ui.components.WebRtcEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class LiveStreamPrefetcherTest {

    private val serverUrl = "http://100.64.0.1:8971"
    private val localUrl = "http://192.168.68.55:8971"
    private val mediaUrls = FrigateMediaUrlRepository()

    private class FakeConnection : ConnectionRepository {
        override val activeConnection = MutableStateFlow<ActiveConnection?>(null)
        override val expectedConnection = MutableStateFlow<ActiveConnection?>(null)
        override val currentServerUrl = MutableStateFlow<String?>(null)
        override val mostRecentConnection: Flow<ConnectionRecord?> = flowOf(null)
        override val biometricLoginAvailable = false
        override val biometricDisplayName = "biometrics"
        override fun hasSavedBiometricCredentials() = false
        override suspend fun connect(serverUrl: String, localUrl: String?, username: String, password: String) = fail("unused")
        override suspend fun signInWithBiometrics(onCredentialsUnlocked: () -> Unit) = fail("unused")
        override suspend fun saveBiometricCredentials(credentials: SavedCredentials) = fail("unused")
        override fun forgetBiometricCredentials() = Unit
        override fun onAppVisibilityChanged(visible: Boolean) = Unit
    }

    private class RecordingPrefetch : LivePlayerPrefetch {
        val prefetched = mutableListOf<List<LivePrefetch>>()
        var cancels = 0
        override fun prefetch(streams: List<LivePrefetch>) {
            prefetched += streams
        }
        override fun cancel() {
            cancels++
        }
    }

    private inner class Harness(scope: TestScope) {
        val connection = FakeConnection()
        val cameraDao = InMemoryCameraDao()
        val players = RecordingPrefetch()
        val prefetcher = LiveStreamPrefetcher(connection, cameraDao, mediaUrls, players, scope.backgroundScope).also { it.start() }

        suspend fun cache(server: String, vararg cameras: CameraEntity) =
            cameraDao.insertAll(cameras.map { it.copy(serverUrl = server) })
    }

    /** backgroundScope isn't driven by advanceUntilIdle() in this coroutines-test version; yield and hop instead. */
    private suspend fun settle() {
        repeat(5) {
            repeat(20) { yield() }
            withContext(Dispatchers.Default) { delay(10) }
        }
    }

    private fun camera(name: String, enabled: Boolean = true) =
        CameraEntity(serverUrl = "", name = name, enabled = enabled, liveStreamName = name, gridStreamName = "${name}_sub")

    /** What a Home card builds for [name] on [activeUrl] — the grid stream, silent WebRTC — under the key it binds with. */
    private fun gridCard(activeUrl: String, name: String) = LivePrefetch(
        playerKey = name,
        source = VideoSource.Live(
            url = mediaUrls.liveStreamUrl(activeUrl, "${name}_sub"),
            webRtc = WebRtcEndpoint(mediaUrls.liveWebRtcSignalingUrl(activeUrl, "${name}_sub"), audio = false),
        ),
    )

    @Test
    fun startsTheGridStreamsOnTheExpectedAddress_forEnabledCamerasOnly() = runTest {
        val h = Harness(this)
        h.cache(serverUrl, camera("driveway"), camera("porch"), camera("attic", enabled = false))

        h.connection.expectedConnection.value = ActiveConnection(serverUrl, localUrl, ConnectionRoute.LOCAL_NETWORK)
        settle()

        assertEquals(listOf(listOf(gridCard(localUrl, "driveway"), gridCard(localUrl, "porch"))), h.players.prefetched)
    }

    @Test
    fun findsCamerasFiledUnderTheOtherScheme() = runTest {
        val h = Harness(this)
        // The saved login says https; the server answered on http, and filed its cameras there.
        h.cache(serverUrl, camera("driveway"))

        h.connection.expectedConnection.value = ActiveConnection("https://100.64.0.1:8971", localUrl, ConnectionRoute.LOCAL_NETWORK)
        settle()

        assertEquals(listOf(listOf(gridCard(localUrl, "driveway"))), h.players.prefetched)
    }

    @Test
    fun startsAtMostAScreenfulOfCameras() = runTest {
        val h = Harness(this)
        h.cache(serverUrl, *Array(6) { camera("cam$it") })

        h.connection.expectedConnection.value = ActiveConnection(serverUrl, null, ConnectionRoute.TAILSCALE)
        settle()

        assertEquals(4, h.players.prefetched.single().size)
    }

    @Test
    fun startsNothingForAServerWithNoCachedCameras() = runTest {
        val h = Harness(this)

        h.connection.expectedConnection.value = ActiveConnection(serverUrl, localUrl, ConnectionRoute.LOCAL_NETWORK)
        settle()

        assertTrue(h.players.prefetched.isEmpty())
    }

    @Test
    fun letsGoWhenTheSignInFails_butNotWhenItLands() = runTest {
        val h = Harness(this)
        h.cache(serverUrl, camera("driveway"))
        val expected = ActiveConnection(serverUrl, localUrl, ConnectionRoute.LOCAL_NETWORK)

        // Lands: connected first, then nothing expected any more. The cards take the players over.
        h.connection.expectedConnection.value = expected
        settle()
        h.connection.activeConnection.value = expected
        h.connection.expectedConnection.value = null
        settle()
        assertEquals(0, h.players.cancels)

        // A later sign-in that fails: nothing connected when the expectation goes.
        h.connection.activeConnection.value = null
        h.connection.expectedConnection.value = expected
        settle()
        h.connection.expectedConnection.value = null
        settle()
        assertEquals(1, h.players.cancels)
    }
}
