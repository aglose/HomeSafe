package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.ActiveConnection
import com.meticulouscreations.homesafe.domain.model.ConnectionRecord
import com.meticulouscreations.homesafe.domain.model.ConnectionRoute
import com.meticulouscreations.homesafe.domain.model.HomeLocation
import com.meticulouscreations.homesafe.domain.model.HouseholdPresence
import com.meticulouscreations.homesafe.domain.model.PresenceDevice
import com.meticulouscreations.homesafe.domain.model.PresenceSource
import com.meticulouscreations.homesafe.domain.model.SavedCredentials
import com.meticulouscreations.homesafe.domain.platform.DeviceInfo
import com.meticulouscreations.homesafe.domain.platform.GeoPoint
import com.meticulouscreations.homesafe.domain.platform.GeofenceMonitor
import com.meticulouscreations.homesafe.domain.platform.LocationAccess
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.domain.repository.PresenceRepository
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.biometric_name_generic
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
import kotlin.time.TestTimeSource

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

        override suspend fun removeDevice(deviceId: String): Result<Unit> = Result.success(Unit)
    }

    private class FakeDeviceInfo(override val build: String, override val emulator: Boolean = false) : DeviceInfo {
        override val platform = "android"
        override val name = "Google Pixel 10 Pro XL"
        override val hardwareId = "a1b2c3"
        override val appVersion = "1.0 (1)"
    }

    private class FakeConnection : ConnectionRepository {
        override val activeConnection = MutableStateFlow<ActiveConnection?>(null)
        override val currentServerUrl = MutableStateFlow<String?>(null)
        override val mostRecentConnection: Flow<ConnectionRecord?> = flowOf(null)
        override val biometricLoginAvailable = false
        override val biometricDisplayName = Res.string.biometric_name_generic
        override fun hasSavedBiometricCredentials() = false
        override suspend fun connect(serverUrl: String, localUrl: String?, username: String, password: String) = fail("unused")
        override suspend fun signInWithBiometrics(onCredentialsUnlocked: () -> Unit) = fail("unused")
        override suspend fun saveBiometricCredentials(credentials: SavedCredentials) = fail("unused")
        override fun forgetBiometricCredentials() = Unit
        override fun onAppVisibilityChanged(visible: Boolean) = Unit

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

    /** [build] and [emulator] say whether this install can be tracked: a release build on a real phone. */
    private class Harness(
        scope: TestScope,
        build: String = "release",
        emulator: Boolean = false,
        access: LocationAccess = LocationAccess.ALWAYS,
        cachedHome: HomeLocation? = null,
    ) {
        val presence = FakePresence()
        val deviceInfo = FakeDeviceInfo(build, emulator)
        val connection = FakeConnection()
        val fence = FakeFence(access)
        val dao = InMemorySettingsDao()
        val identity = DeviceIdentityStore(dao)
        val clock = TestTimeSource()
        val foreground = MutableStateFlow(true)
        val automation = PresenceAutomationImpl(
            presence,
            deviceInfo,
            connection,
            fence,
            identity,
            scope.backgroundScope,
            dwellSeconds = 600,
            timeSource = clock,
            inForeground = foreground,
        )
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
    fun theLanSaysNothingWhenAlreadyHomeOrOnABuildThatIsNotTracked() = runTest {
        val h = Harness(this).started()
        h.presence.me(away = false)
        h.connection.route(ConnectionRoute.LOCAL_NETWORK)
        settle()
        assertTrue(h.presence.calls.isEmpty(), "home already: nothing to report")

        val debug = Harness(this, build = "debug").started()
        debug.presence.me(away = true)
        debug.connection.route(ConnectionRoute.LOCAL_NETWORK)
        settle()
        assertTrue(debug.presence.calls.isEmpty(), "a debug build: the relay doesn't track it, so it says nothing")

        // Tailscale isn't proof of anything: it works from the road too.
        val remote = Harness(this).started()
        remote.presence.me(away = true)
        remote.connection.route(ConnectionRoute.TAILSCALE)
        settle()
        assertTrue(remote.presence.calls.isEmpty())
    }

    @Test
    fun theFenceFollowsTheAccessAndTheHomeWithNoSwitchToTurnItOn() = runTest {
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
    fun crossingsAreIgnoredOnADebugBuildOrAnEmulator() = runTest {
        val debug = Harness(this, build = "debug").started()
        debug.automation.onGeofenceTransition(exited = true)
        assertTrue(debug.presence.calls.isEmpty())

        val emulator = Harness(this, emulator = true).started()
        emulator.automation.onGeofenceTransition(exited = true)
        assertTrue(emulator.presence.calls.isEmpty(), "a release build on an emulator is still not a phone anyone carries")
    }

    @Test
    fun aBuildThatIsNotTrackedTakesDownAFenceItArmedBefore() = runTest {
        val h = Harness(this, build = "debug", cachedHome = home).started()
        h.presence.me(away = false, home = home)
        settle()
        assertEquals(listOf<HomeLocation?>(null), h.fence.watched)
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

        val debug = Harness(this, build = "debug")
        debug.identity.saveCachedHome(home)
        debug.automation.resyncGeofence()
        assertEquals(listOf<HomeLocation?>(null), debug.fence.watched)
    }

    // ---- Where the phone is, by a fix while the app runs ------------------------------------------

    /** About [meters] north of [home]'s middle: a degree of latitude is ~111 km. */
    private fun north(meters: Double) = GeoPoint(home.latitude + meters / 111_195.0, home.longitude)

    @Test
    fun aPhoneAlreadyOutWhenItBecameTheTrackedOneIsArmedAwayByAFix() = runTest {
        val h = Harness(this).started()
        h.fence.here = north(5_000.0)
        h.presence.me(away = false, home = home)
        settle()
        assertEquals(listOf(Triple(true, PresenceSource.LOCATION, 600)), h.presence.calls, "armed with the dwell, like a crossing")
    }

    @Test
    fun aPhoneMarkedAwayThatIsInsideComesHome() = runTest {
        val h = Harness(this).started()
        h.fence.here = north(40.0)
        h.presence.me(away = true, home = home)
        settle()
        assertEquals(listOf(Triple(false, PresenceSource.LOCATION, 0)), h.presence.calls)
    }

    @Test
    fun aFixJustPastTheCircleOrAnAlreadyArmedDepartureSaysNothing() = runTest {
        val h = Harness(this).started()
        h.fence.here = north(200.0)
        h.presence.me(away = false, home = home)
        settle()
        assertTrue(h.presence.calls.isEmpty(), "within the margin past a 150 m circle: GPS wobble, not a departure")

        val leaving = Harness(this).started()
        leaving.fence.here = north(5_000.0)
        leaving.presence.me(away = false, pending = true, home = home)
        settle()
        assertTrue(leaving.presence.calls.isEmpty(), "already leaving: arming again would only restart nothing")
    }

    @Test
    fun theFixIsTakenAtMostEveryFiveMinutes() = runTest {
        val h = Harness(this).started()
        h.fence.here = north(40.0)
        h.presence.me(away = true, home = home)
        settle()
        h.presence.me(away = true, home = home.copy(radiusMeters = 151.0)) // a fresh snapshot, a minute later
        settle()
        assertEquals(1, h.presence.calls.size)

        h.clock += PresenceAutomationImpl.LOCATION_CHECK_EVERY
        h.presence.me(away = true, home = home)
        settle()
        assertEquals(2, h.presence.calls.size)
    }

    @Test
    fun inTheBackgroundNothingKeepsThePresencePollAliveOrTakesAFix() = runTest {
        val h = Harness(this, cachedHome = home).started()
        settle()
        assertTrue(h.presence.presence.subscriptionCount.value > 0, "on screen: the relay is watched")

        h.foreground.value = false
        settle()
        assertEquals(0, h.presence.presence.subscriptionCount.value, "in a pocket: nothing keeps the minute poll going")
        h.fence.here = north(5_000.0)
        h.presence.me(away = false, home = home)
        settle()
        assertTrue(h.presence.calls.isEmpty(), "no fix in the background — the fence covers it")
        assertEquals(listOf<HomeLocation?>(home), h.fence.watched, "and the fence armed from the cache stays up")

        h.foreground.value = true
        settle()
        assertEquals(listOf(Triple(true, PresenceSource.LOCATION, 600)), h.presence.calls, "back on screen, the snapshot it missed is checked at once")
    }

    @Test
    fun noFixWithoutLocationAccessAHomeOrATrackedBuild() = runTest {
        val notAsked = Harness(this, access = LocationAccess.NOT_ASKED).started()
        notAsked.fence.here = north(5_000.0)
        notAsked.presence.me(away = false, home = home)
        settle()
        assertTrue(notAsked.presence.calls.isEmpty())

        val whileInUse = Harness(this, access = LocationAccess.WHILE_IN_USE).started()
        whileInUse.fence.here = north(5_000.0)
        whileInUse.presence.me(away = false, home = home)
        settle()
        assertEquals(1, whileInUse.presence.calls.size, "the app is open, so a fix needs only \"while in use\"")

        val noHome = Harness(this).started()
        noHome.fence.here = north(5_000.0)
        noHome.presence.me(away = false, home = null)
        settle()
        assertTrue(noHome.presence.calls.isEmpty())

        val debug = Harness(this, build = "debug").started()
        debug.fence.here = north(5_000.0)
        debug.presence.me(away = false, home = home)
        settle()
        assertTrue(debug.presence.calls.isEmpty())
    }
}
