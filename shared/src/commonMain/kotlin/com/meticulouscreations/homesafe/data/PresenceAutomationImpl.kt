package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.ConnectionRoute
import com.meticulouscreations.homesafe.domain.model.HomeLocation
import com.meticulouscreations.homesafe.domain.model.HouseholdPresence
import com.meticulouscreations.homesafe.domain.model.PresenceSource
import com.meticulouscreations.homesafe.domain.platform.GeofenceMonitor
import com.meticulouscreations.homesafe.domain.platform.LocationAccess
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.domain.repository.PresenceAutomation
import com.meticulouscreations.homesafe.domain.repository.PresenceRepository
import com.meticulouscreations.homesafe.domain.repository.SettingsRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * See [PresenceAutomation]. Three collectors while the app runs:
 *
 * 1. **LAN → home.** The connection repository already probes the server's LAN address on every
 *    network change and records which route won. Landing on [ConnectionRoute.LOCAL_NETWORK]
 *    while this phone is away (or armed to be) is proof enough to say home — no permission, no
 *    fix, no fence.
 * 2. **The fence follows its inputs.** Automation on, location "always", and a home from the
 *    relay: watch it. Any of those gone: stop. Idempotent, so it also re-arms after the OS
 *    dropped the fence.
 * 3. **Cache home.** So [resyncGeofence] can re-arm after a reboot with no relay in reach.
 *
 * Crossings arrive through [onGeofenceTransition] from whatever the OS woke.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class PresenceAutomationImpl(
    private val presenceRepository: PresenceRepository,
    private val settingsRepository: SettingsRepository,
    private val connectionRepository: ConnectionRepository,
    private val geofenceMonitor: GeofenceMonitor,
    private val identity: DeviceIdentityStore,
    private val appScope: CoroutineScope,
    private val dwellSeconds: Int = DEFAULT_DWELL_SECONDS,
) : PresenceAutomation {

    override val geofenceSupported: Boolean get() = geofenceMonitor.isSupported
    override val locationAccess: StateFlow<LocationAccess> get() = geofenceMonitor.access

    private var job: Job? = null

    private fun enabled(): Flow<Boolean> = settingsRepository.observeSettings().map { it.automaticPresence }.distinctUntilChanged()

    override fun start() {
        if (job?.isActive == true) return
        job = appScope.launch {
            launch {
                combine(connectionRepository.activeConnection, enabled()) { connection, on ->
                    on && connection?.route == ConnectionRoute.LOCAL_NETWORK
                }
                    .distinctUntilChanged()
                    .filter { it }
                    .collect { comeHomeViaLan() }
            }
            launch {
                combine(enabled(), geofenceMonitor.access, home()) { on, access, home ->
                    home.takeIf { on && access == LocationAccess.ALWAYS }
                }
                    .distinctUntilChanged()
                    .collect { geofenceMonitor.watch(it) }
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
            ?: return Result.failure(IllegalStateException("Couldn't get this phone's location"))
        return presenceRepository.setHome(HomeLocation(here.latitude, here.longitude, DEFAULT_RADIUS_METERS))
    }

    override suspend fun clearHome(): Result<Unit> = presenceRepository.setHome(null)

    override suspend fun onGeofenceTransition(exited: Boolean) {
        if (!settingsRepository.observeSettings().first().automaticPresence) return
        if (exited) {
            presenceRepository.setThisDeviceAway(true, PresenceSource.GEOFENCE, dwellSeconds)
        } else {
            presenceRepository.setThisDeviceAway(false, PresenceSource.GEOFENCE)
        }
    }

    override suspend fun resyncGeofence() {
        val on = settingsRepository.observeSettings().first().automaticPresence
        val home = identity.cachedHome()
        geofenceMonitor.watch(home.takeIf { on && geofenceMonitor.access.value == LocationAccess.ALWAYS })
    }

    companion object {
        /** Long enough for the mailbox, the bins and a chat at the kerb; short enough that the driveway is still covered on the way out. */
        const val DEFAULT_DWELL_SECONDS = 10 * 60

        /** Wide enough that GPS wobble indoors doesn't cross it; Android won't fire reliably under ~100 m anyway. */
        const val DEFAULT_RADIUS_METERS = 150.0
    }
}
