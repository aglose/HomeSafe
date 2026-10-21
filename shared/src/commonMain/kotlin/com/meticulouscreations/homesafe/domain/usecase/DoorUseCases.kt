package com.meticulouscreations.homesafe.domain.usecase

import com.meticulouscreations.homesafe.domain.model.DoorNotify
import com.meticulouscreations.homesafe.domain.model.DoorSpot
import com.meticulouscreations.homesafe.domain.model.DoorsSnapshot
import com.meticulouscreations.homesafe.domain.model.FloorPlan
import com.meticulouscreations.homesafe.domain.repository.DoorRepository
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.StateFlow

/** The door sensors, live from the relay; polled while collected. */
@Inject
class ObserveDoorsUseCase(private val doorRepository: DoorRepository) {
    operator fun invoke(): StateFlow<DoorsSnapshot> = doorRepository.doors
}

/** Re-reads the doors now: pull to refresh, and each visit to the Doors page. */
@Inject
class RefreshDoorsUseCase(private val doorRepository: DoorRepository) {
    suspend operator fun invoke(): Result<Unit> = doorRepository.refresh()
}

/** Sets when one door's openings push. */
@Inject
class SetDoorNotifyUseCase(private val doorRepository: DoorRepository) {
    suspend operator fun invoke(doorId: String, notify: DoorNotify): Result<Unit> = doorRepository.setNotify(doorId, notify)
}

/** The house's floor plan, for the Doors page to draw the doors on; null while the box has none. */
@Inject
class ObserveFloorPlanUseCase(private val doorRepository: DoorRepository) {
    operator fun invoke(): StateFlow<FloorPlan?> = doorRepository.floorPlan
}

/** Places one door on the floor plan, or with null takes it off. */
@Inject
class SetDoorSpotUseCase(private val doorRepository: DoorRepository) {
    suspend operator fun invoke(doorId: String, spot: DoorSpot?): Result<Unit> = doorRepository.setSpot(doorId, spot)
}

/** Lets door sensors pair (or rejoin) for a while, or stops. */
@Inject
class SetDoorPairingUseCase(private val doorRepository: DoorRepository) {
    suspend operator fun invoke(seconds: Int): Result<Unit> = doorRepository.setPairing(seconds)
}

/** Names one door's sensor: a sensor just paired is called by its address until it's named. */
@Inject
class RenameDoorUseCase(private val doorRepository: DoorRepository) {
    suspend operator fun invoke(doorId: String, name: String): Result<Unit> = doorRepository.rename(doorId, name)
}
