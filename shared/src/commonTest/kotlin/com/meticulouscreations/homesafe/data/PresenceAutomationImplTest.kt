package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.ActiveConnection
import com.meticulouscreations.homesafe.domain.model.AlertSettings
import com.meticulouscreations.homesafe.domain.model.ConnectionRecord
import com.meticulouscreations.homesafe.domain.model.ConnectionRoute
import com.meticulouscreations.homesafe.domain.model.HomeLocation
import com.meticulouscreations.homesafe.domain.model.HouseholdPresence
import com.meticulouscreations.homesafe.domain.model.PresenceDevice
import com.meticulouscreations.homesafe.domain.model.PresenceSource
import com.meticulouscreations.homesafe.domain.model.SavedCredentials
import com.meticulouscreations.homesafe.domain.platform.GeoPoint
import com.meticulouscreations.homesafe.domain.platform.GeofenceMonitor
import com.meticulouscreations.homesafe.domain.platform.LocationAccess
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.domain.repository.PresenceRepository
import com.meticulouscreations.homesafe.domain.repository.SettingsRepository
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

@OptIn(ExperimentalCoroutinesApi::class)
class PresenceAutomationImplTest {

    private val home = HomeLocation(40.0, -80.0, 150.0)
    private val elsewhere = HomeLocation(41.0, -81.0, 150.0)

    private class FakePresence : PresenceRepository {
        override val presence = MutableStateFlow(HouseholdPresence.EMPTY)
        val calls = mutableListOf<Triple<Boolean, PresenceSource, Int>>()
        var homeSet = mutableListOf<HomeLocation?>()
        var refreshes = 0

        fun me(away: Boolean, pending: Boolean = false, home: HomeLocation? = null) {
            presence.value = HouseholdPresence(
                devices = listOf(PresenceDevice("Pixel", "android", away, 1.0, isThisDevice = true, pendingAway = pending)),
                everyoneAway = away,
                home = home,
            )
        }

        override suspend fun refresh(): Result<Unit> {
            refreshes++
            return Result.success(Unit)
        }

        override suspend fun setThisDeviceAway(away: Boolean, source: PresenceSource, dwellSeconds: Int): Result<Unit> {
            calls += Triple(away, source, dwellSeconds)
            return Result.success(Unit)
        }

        override suspend fun setHome(home: HomeLocation?): Result<Unit> {
            homeSet += home
            return Result.success(Unit)
        }
    }

    private class FakeSettings(automatic: Boolean) : SettingsRepository {
        val settings = MutableStateFlow(AlertSettings.DEFAULT.copy(automaticPresence = automatic))
        override fun observeSettings(): Flow<AlertSettings> = settings
        override suspend fun updateSettings(settings: AlertSettings) {
            this.settings.value = settings
        }
    }

    private class FakeConnection : ConnectionRepository {
        override val activeConnection = MutableStateFlow<ActiveConnection?>(null)
        override val currentServerUrl = MutableStateFlow<String?>(null)
        override val mostRecentConnection: Flow<ConnectionRecord?> = flowOf(null)
        override val biometricLoginAvailable = false
        override val biometricDisplayName = "biometrics"
        override fun hasSavedBiometricCredentials() = false
        override suspend fun connect(serverUrl: String, localUrl: String?, username: String, password: String) = fail("unused")
        override suspend fun signInWithBiometrics(onCredentialsUnlocked: () -> Unit) = fail("unused")
        override suspend fun saveBiometricCredentials(credentials: SavedCredentials) = fail("unused")
        override fun forgetBiometricCredentials() = Unit

        fun route(route: ConnectionRoute?) {
            activeConnection.value = route?.let { ActiveConnection("http://100.99.163.71:8971", "http://192.168.68.55:8971", it) }
        }
    }

    private class FakeFence(access: LocationAccess) : GeofenceMonitor {
        override val isSupported = true
        override val access = MutableStateFlow(access)
        val watched = mutableListOf<HomeLocation?>()
        var here: GeoPoint? = GeoPoint(40.0, -80.0)
        override suspend fun requestAccess() = Unit
        override suspend fun currentLocation() = here
        override fun watch(home: HomeLocation?) {
            watched += home
        }
    }

    private class Harness(scope: TestScope, automatic: Boolean = true, access: LocationAccess = LocationAccess.ALWAYS, cachedHome: HomeLocation? = null) {
        val presence = FakePresence()
        val settings = FakeSettings(automatic)
        val connection = FakeConnection()
        val fence = FakeFence(access)
        val dao = InMemorySettingsDao()
        val identity = DeviceIdentityStore(dao)
        val automation = PresenceAutomationImpl(presence, settings, connection, fence, identity, scope.backgroundScope, dwellSeconds = 600)
        val cached = cachedHome
    }

    private suspend fun Harness.started(): Harness = apply {
        cached?.let { identity.saveCachedHome(it) }
        automation.start()
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
    fun reachingTheServerOverTheLanBringsAnAwayPhoneHome() = runTest {
        val h = Harness(this).started()
        h.presence.me(away = true)
        h.connection.route(ConnectionRoute.LOCAL_NETWORK)
        settle()
        assertEquals(1, h.presence.refreshes, "asks the relay first, in case the other phone already flipped us")
        assertEquals(listOf(Triple(false, PresenceSource.LAN, 0)), h.presence.calls)
    }

    @Test
    fun theLanAlsoCancelsAnArmedDeparture() = runTest {
        val h = Harness(this).started()
        h.presence.me(away = false, pending = true)
        h.connection.route(ConnectionRoute.LOCAL_NETWORK)
        settle()
        assertEquals(listOf(Triple(false, PresenceSource.LAN, 0)), h.presence.calls)
    }

    @Test
    fun theLanSaysNothingWhenAlreadyHomeOrWhenAutomationIsOff() = runTest {
        val h = Harness(this).started()
        h.presence.me(away = false)
        h.connection.route(ConnectionRoute.LOCAL_NETWORK)
        settle()
        assertTrue(h.presence.calls.isEmpty(), "home already: nothing to report")

        val off = Harness(this, automatic = false).started()
        off.presence.me(away = true)
        off.connection.route(ConnectionRoute.LOCAL_NETWORK)
        settle()
        assertTrue(off.presence.calls.isEmpty(), "switched off: the LAN is just a network")

        // Tailscale isn't proof of anything: it works from the road too.
        val remote = Harness(this).started()
        remote.presence.me(away = true)
        remote.connection.route(ConnectionRoute.TAILSCALE)
        settle()
        assertTrue(remote.presence.calls.isEmpty())
    }

    @Test
    fun theFenceFollowsTheSwitchTheAccessAndTheHome() = runTest {
        val h = Harness(this).started()
        settle()
        assertEquals(listOf<HomeLocation?>(null), h.fence.watched, "no home known yet: nothing to watch")

        h.presence.me(away = false, home = home)
        settle()
        assertEquals(home, h.fence.watched.last())

        h.fence.access.value = LocationAccess.WHILE_IN_USE
        settle()
        assertNull(h.fence.watched.last(), "without background location the fence can't fire, so don't pretend")

        h.fence.access.value = LocationAccess.ALWAYS
        settle()
        assertEquals(home, h.fence.watched.last())

        h.settings.settings.value = h.settings.settings.value.copy(automaticPresence = false)
        settle()
        assertNull(h.fence.watched.last())

        h.settings.settings.value = h.settings.settings.value.copy(automaticPresence = true)
        h.presence.me(away = false, home = elsewhere)
        settle()
        assertEquals(elsewhere, h.fence.watched.last(), "home moved: the fence moves with it")
    }

    @Test
    fun aCachedHomeArmsTheFenceBeforeTheRelayAnswersAndAnUnansweredRelayNeverTearsItDown() = runTest {
        val h = Harness(this, cachedHome = home).started()
        settle()
        assertEquals(listOf<HomeLocation?>(home), h.fence.watched, "armed from the cache alone — this is the reboot / background-launch path")
        // The relay answering with the same home changes nothing; it answering with none clears it.
        h.presence.me(away = false, home = home)
        settle()
        assertEquals(listOf<HomeLocation?>(home), h.fence.watched)
        h.presence.me(away = false, home = null)
        settle()
        assertEquals(listOf<HomeLocation?>(home, null), h.fence.watched)
        assertNull(h.identity.cachedHome(), "and the cache follows the relay")
    }

    @Test
    fun crossingsArmAwayWithTheDwellAndComeHomeAtOnce() = runTest {
        val h = Harness(this).started()
        h.automation.onGeofenceTransition(exited = true)
        h.automation.onGeofenceTransition(exited = false)
        assertEquals(
            listOf(Triple(true, PresenceSource.GEOFENCE, 600), Triple(false, PresenceSource.GEOFENCE, 0)),
            h.presence.calls,
        )
    }

    @Test
    fun crossingsAreIgnoredWhileAutomationIsOff() = runTest {
        val h = Harness(this, automatic = false).started()
        h.automation.onGeofenceTransition(exited = true)
        assertTrue(h.presence.calls.isEmpty())
    }

    @Test
    fun setHomeHereUsesAFixAndTheDefaultRadius() = runTest {
        val h = Harness(this).started()
        assertTrue(h.automation.setHomeHere().isSuccess)
        assertEquals(listOf<HomeLocation?>(HomeLocation(40.0, -80.0, 150.0)), h.presence.homeSet)

        h.fence.here = null
        assertTrue(h.automation.setHomeHere().isFailure, "no fix, no home — never guess")
        assertTrue(h.automation.clearHome().isSuccess)
        assertNull(h.presence.homeSet.last())
    }

    @Test
    fun resyncReArmsFromTheCacheOnlyWhenEverythingElseIsInPlace() = runTest {
        val armed = Harness(this, cachedHome = home)
        armed.identity.saveCachedHome(home)
        armed.automation.resyncGeofence()
        assertEquals(listOf<HomeLocation?>(home), armed.fence.watched)

        val noAccess = Harness(this, access = LocationAccess.WHILE_IN_USE)
        noAccess.identity.saveCachedHome(home)
        noAccess.automation.resyncGeofence()
        assertEquals(listOf<HomeLocation?>(null), noAccess.fence.watched)

        val off = Harness(this, automatic = false)
        off.identity.saveCachedHome(home)
        off.automation.resyncGeofence()
        assertEquals(listOf<HomeLocation?>(null), off.fence.watched)
    }
}
