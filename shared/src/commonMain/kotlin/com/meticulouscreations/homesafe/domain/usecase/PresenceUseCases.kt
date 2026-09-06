package com.meticulouscreations.homesafe.domain.usecase

import com.meticulouscreations.homesafe.domain.model.HouseholdPresence
import com.meticulouscreations.homesafe.domain.platform.PushTokenProvider
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

/**
 * Marks this phone's owner away (or back). [isSupported] is false where the device has no push
 * identity the relay could attribute the change to — the Settings switch is disabled there.
 */
@Inject
class SetAwayUseCase(
    private val presenceRepository: PresenceRepository,
    private val pushTokenProvider: PushTokenProvider,
) {
    val isSupported: Boolean get() = pushTokenProvider.isSupported

    suspend operator fun invoke(away: Boolean): Result<Unit> = presenceRepository.setThisDeviceAway(away)
}
