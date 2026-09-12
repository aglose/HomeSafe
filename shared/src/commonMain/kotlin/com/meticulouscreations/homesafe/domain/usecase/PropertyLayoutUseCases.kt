package com.meticulouscreations.homesafe.domain.usecase

import com.meticulouscreations.homesafe.domain.model.CameraPlacement
import com.meticulouscreations.homesafe.domain.model.HomeLayout
import com.meticulouscreations.homesafe.domain.repository.PropertyLayoutRepository
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow

@Inject
class ObserveCameraPlacementsUseCase(private val repository: PropertyLayoutRepository) {
    operator fun invoke(): Flow<List<CameraPlacement>> = repository.observePlacements()
}

@Inject
class PlaceCameraUseCase(private val repository: PropertyLayoutRepository) {
    suspend operator fun invoke(placement: CameraPlacement) = repository.place(placement)
}

@Inject
class RemoveCameraPlacementUseCase(private val repository: PropertyLayoutRepository) {
    suspend operator fun invoke(cameraName: String) = repository.removePlacement(cameraName)
}

@Inject
class ObserveHomeLayoutUseCase(private val repository: PropertyLayoutRepository) {
    operator fun invoke(): Flow<HomeLayout> = repository.observeHomeLayout()
}

@Inject
class SetHomeLayoutUseCase(private val repository: PropertyLayoutRepository) {
    suspend operator fun invoke(layout: HomeLayout) = repository.setHomeLayout(layout)
}
