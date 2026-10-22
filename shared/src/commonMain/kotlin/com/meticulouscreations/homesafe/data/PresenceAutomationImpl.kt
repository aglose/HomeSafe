package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.ConnectionRoute
import com.meticulouscreations.homesafe.domain.model.HomeLocation
import com.meticulouscreations.homesafe.domain.model.HouseholdPresence
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

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
 * 3. **Cache home.** So [resyncGeofence] can re-arm after a reboot with no relay in reach.
 *
 * Crossings arrive through [onGeofenceTransition] from whatever the OS woke.
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
            } else {
                geofenceMonitor.watch(null) // a fence armed back when this build could switch it on
            }
            launch {
                presenceRepository.presence
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
        emitAll(presenceRepository.presence.filter { it !== HouseholdPresence.EMPTY }.map { it.home })
    }.distinctUntilChanged()

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
    }
}
