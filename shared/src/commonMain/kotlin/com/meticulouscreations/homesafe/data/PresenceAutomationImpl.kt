package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.AppVisibility
import com.meticulouscreations.homesafe.domain.model.ConnectionRoute
import com.meticulouscreations.homesafe.domain.model.HomeLocation
import com.meticulouscreations.homesafe.domain.model.HouseholdPresence
import com.meticulouscreations.homesafe.domain.model.PresenceDevice
import com.meticulouscreations.homesafe.domain.model.PresenceSource
import com.meticulouscreations.homesafe.domain.platform.DeviceInfo
import com.meticulouscreations.homesafe.domain.platform.GeofenceMonitor
import com.meticulouscreations.homesafe.domain.platform.LocationAccess
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.domain.repository.PresenceAutomation
import com.meticulouscreations.homesafe.domain.repository.PresenceRepository
import com.meticulouscreations.homesafe.text.LocalizedException
import com.meticulouscreations.homesafe.text.UiText
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.error_no_location
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * See [PresenceAutomation]. On an install that can be tracked ([DeviceInfo.canBeTracked]), three
 * collectors while the app runs; on any other, only the home cache, and the fence stays down:
 *
 * 1. **LAN → home.** The connection repository already probes the server's LAN address on every
 *    network change and records which route won. Landing on [ConnectionRoute.LOCAL_NETWORK]
 *    while this phone is away (or armed to be) is proof enough to say home — no permission, no
 *    fix, no fence.
 * 2. **The fence follows its inputs.** Location "always" and a home from the relay: watch it.
 *    Either gone: stop. Idempotent, so it also re-arms after the OS dropped the fence.
 * 3. **Where the phone is.** The fence only speaks when the phone crosses it, so a phone already
 *    out when it became the tracked one (or when home was set, or the fence armed) would stay
 *    "home" until it came back. While the app is on screen, each fresh presence takes a location
 *    fix — at most every [LOCATION_CHECK_EVERY] — and puts it against home: well outside arms away
 *    with the dwell, inside brings an away phone home. Needs only "while in use".
 * 4. **Cache home.** So [resyncGeofence] can re-arm after a reboot with no relay in reach.
 *
 * Everything that reads the relay's presence does so only while the app is on screen
 * ([inForeground]): collecting it is what keeps [PresenceRepository] polling every minute, and
 * each fresh snapshot may take a location fix. In the background the fence alone watches, and
 * crossings arrive through [onGeofenceTransition] from whatever the OS woke.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class PresenceAutomationImpl(
    private val presenceRepository: PresenceRepository,
    private val deviceInfo: DeviceInfo,
    private val connectionRepository: ConnectionRepository,
    private val geofenceMonitor: GeofenceMonitor,
    private val identity: DeviceIdentityStore,
    private val appScope: CoroutineScope,
    private val dwellSeconds: Int = DEFAULT_DWELL_SECONDS,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val inForeground: Flow<Boolean> = AppVisibility.inForeground,
) : PresenceAutomation {

    override val geofenceSupported: Boolean get() = geofenceMonitor.isSupported
    override val locationAccess: StateFlow<LocationAccess> get() = geofenceMonitor.access

    private var job: Job? = null

    /** A debug build, an emulator: the relay ignores what it says, so it never watches or reports. */
    private val tracked: Boolean get() = deviceInfo.canBeTracked

    override fun start() {
        if (job?.isActive == true) return
        job = appScope.launch {
            if (tracked) {
                launch {
                    connectionRepository.activeConnection
                        .map { it?.route == ConnectionRoute.LOCAL_NETWORK }
                        .distinctUntilChanged()
                        .filter { it }
                        .collect { comeHomeViaLan() }
                }
                launch {
                    combine(geofenceMonitor.access, home()) { access, home -> home.takeIf { access == LocationAccess.ALWAYS } }
                        .distinctUntilChanged()
                        .collect { geofenceMonitor.watch(it) }
                }
                launch {
                    var checkedAt: TimeMark? = null
                    combine(foregroundPresence(), geofenceMonitor.access) { presence, access -> presence.takeIf { access in FIX_ACCESS } }
                        .filterNotNull()
                        .collect { presence ->
                            if (checkedAt?.let { it.elapsedNow() < LOCATION_CHECK_EVERY } == true) return@collect
                            val me = presence.thisDevice ?: return@collect
                            val home = presence.home ?: return@collect
                            checkedAt = timeSource.markNow()
                            checkWhereThePhoneIs(me, home)
                        }
                }
            } else {
                geofenceMonitor.watch(null) // a fence armed back when this build could switch it on
            }
            launch {
                foregroundPresence()
                    .filter { it !== HouseholdPresence.EMPTY } // "not answered yet" isn't "no home"
                    .map { it.home }
                    .distinctUntilChanged()
                    .collect { identity.saveCachedHome(it) }
            }
        }
    }

    /**
     * Where home is: what this phone cached last time, at once, then whatever the relay says.
     * The cache-first step is what re-arms the fence on a launch with no server in reach — and
     * "the relay hasn't answered yet" must never read as "there is no home", or that launch
     * would tear the fence down.
     */
    private fun home(): Flow<HomeLocation?> = flow {
        emit(identity.cachedHome())
        emitAll(foregroundPresence().filter { it !== HouseholdPresence.EMPTY }.map { it.home })
    }.distinctUntilChanged()

    /**
     * The relay's presence while the app is on screen, nothing while it isn't. Collecting
     * [PresenceRepository.presence] is what keeps it polling, so a collector that ran forever
     * would poll — and, on a tracked phone, take a fix every [LOCATION_CHECK_EVERY] — in a
     * pocket all day. Back on screen, the current snapshot comes straight away.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun foregroundPresence(): Flow<HouseholdPresence> =
        inForeground.distinctUntilChanged().flatMapLatest { visible -> if (visible) presenceRepository.presence else emptyFlow() }

    /**
     * One fix against [home]. Out means past the circle by [LOCATION_MARGIN_METERS] too, so GPS
     * wobble near the edge doesn't arm a departure; an armed or finished departure isn't armed
     * again. In means inside the circle itself.
     */
    private suspend fun checkWhereThePhoneIs(me: PresenceDevice, home: HomeLocation) {
        val here = geofenceMonitor.currentLocation() ?: return
        val distance = home.distanceMeters(here.latitude, here.longitude)
        when {
            distance > home.radiusMeters + LOCATION_MARGIN_METERS && !me.away && !me.pendingAway ->
                presenceRepository.setThisDeviceAway(true, PresenceSource.LOCATION, dwellSeconds)

            distance <= home.radiusMeters && (me.away || me.pendingAway) ->
                presenceRepository.setThisDeviceAway(false, PresenceSource.LOCATION)
        }
    }

    /** Only if the relay thinks we're out: a phone that's home already has nothing to report. */
    private suspend fun comeHomeViaLan() {
        presenceRepository.refresh()
        val me = presenceRepository.presence.value.thisDevice ?: return
        if (me.away || me.pendingAway) presenceRepository.setThisDeviceAway(false, PresenceSource.LAN)
    }

    override suspend fun requestLocationAccess() = geofenceMonitor.requestAccess()

    override suspend fun setHomeHere(): Result<Unit> {
        val here = geofenceMonitor.currentLocation()
            ?: return Result.failure(LocalizedException(UiText.of(Res.string.error_no_location), technical = "No location for this phone"))
        return presenceRepository.setHome(HomeLocation(here.latitude, here.longitude, DEFAULT_RADIUS_METERS))
    }

    override suspend fun clearHome(): Result<Unit> = presenceRepository.setHome(null)

    override suspend fun onGeofenceTransition(exited: Boolean) {
        if (!tracked) return
        if (exited) {
            presenceRepository.setThisDeviceAway(true, PresenceSource.GEOFENCE, dwellSeconds)
        } else {
            presenceRepository.setThisDeviceAway(false, PresenceSource.GEOFENCE)
        }
    }

    override suspend fun resyncGeofence() {
        val home = identity.cachedHome()
        geofenceMonitor.watch(home.takeIf { tracked && geofenceMonitor.access.value == LocationAccess.ALWAYS })
    }

    companion object {
        /** Long enough for the mailbox, the bins and a chat at the kerb; short enough that the driveway is still covered on the way out. */
        const val DEFAULT_DWELL_SECONDS = 10 * 60

        /** Wide enough that GPS wobble indoors doesn't cross it; Android won't fire reliably under ~100 m anyway. */
        const val DEFAULT_RADIUS_METERS = 150.0

        /** How far past the circle a fix must be to count as out: a phone's fix indoors can be off by this much. */
        const val LOCATION_MARGIN_METERS = 100.0

        /** A fix costs battery, and the dwell is ten minutes: one every five while the app is on screen is plenty. */
        val LOCATION_CHECK_EVERY = 5.minutes

        /** What a one-off fix needs: the app is open, so "while in use" will do. */
        private val FIX_ACCESS = setOf(LocationAccess.WHILE_IN_USE, LocationAccess.ALWAYS)
    }
}
