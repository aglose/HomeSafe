package com.meticulouscreations.homesafe.domain.usecase

import com.meticulouscreations.homesafe.domain.model.HouseholdPresence
import com.meticulouscreations.homesafe.domain.platform.LocationAccess
import com.meticulouscreations.homesafe.domain.repository.PresenceAutomation
import com.meticulouscreations.homesafe.domain.repository.PresenceRepository
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.StateFlow

/** Who's home, live from the relay; polled while collected. */
@Inject
class ObserveHouseholdPresenceUseCase(private val presenceRepository: PresenceRepository) {
    operator fun invoke(): StateFlow<HouseholdPresence> = presenceRepository.presence
}

/** Re-reads presence now — on every visit to Settings, since the other phone may have flipped it. */
@Inject
class RefreshHouseholdPresenceUseCase(private val presenceRepository: PresenceRepository) {
    suspend operator fun invoke(): Result<Unit> = presenceRepository.refresh()
}

/** The "I'm away" switch: marks this phone's owner away (or back), immediately. */
@Inject
class SetAwayUseCase(private val presenceRepository: PresenceRepository) {
    suspend operator fun invoke(away: Boolean): Result<Unit> = presenceRepository.setThisDeviceAway(away)
}

/** Automatic presence: what the phone may see of its location, and whether a geofence is even possible here. */
@Inject
class ObserveLocationAccessUseCase(private val automation: PresenceAutomation) {
    val geofenceSupported: Boolean get() = automation.geofenceSupported

    operator fun invoke(): StateFlow<LocationAccess> = automation.locationAccess
}

/** Asks the OS for the next level of location access; the Settings tab reads the answer from [ObserveLocationAccessUseCase]. */
@Inject
class RequestLocationAccessUseCase(private val automation: PresenceAutomation) {
    suspend operator fun invoke() = automation.requestLocationAccess()
}

/** "Set home here": the household's home becomes where this phone is standing. */
@Inject
class SetHomeHereUseCase(private val automation: PresenceAutomation) {
    suspend operator fun invoke(): Result<Unit> = automation.setHomeHere()
}

@Inject
class ClearHomeUseCase(private val automation: PresenceAutomation) {
    suspend operator fun invoke(): Result<Unit> = automation.clearHome()
}
