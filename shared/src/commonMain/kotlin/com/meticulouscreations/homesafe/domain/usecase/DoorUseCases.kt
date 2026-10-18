package com.meticulouscreations.homesafe.domain.usecase

import com.meticulouscreations.homesafe.domain.model.DoorNotify
import com.meticulouscreations.homesafe.domain.model.DoorsSnapshot
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
