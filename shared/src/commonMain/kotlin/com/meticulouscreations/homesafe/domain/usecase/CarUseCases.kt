package com.meticulouscreations.homesafe.domain.usecase

import com.meticulouscreations.homesafe.domain.model.HouseholdCar
import com.meticulouscreations.homesafe.domain.repository.CarRepository
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.StateFlow

/** The household's Teslas and where they are, from the relay; null until it has answered, polled while collected. */
@Inject
class ObserveCarsUseCase(private val carRepository: CarRepository) {
    operator fun invoke(): StateFlow<List<HouseholdCar>?> = carRepository.cars
}

/** Re-reads the cars now: Home's pull to refresh, and each visit to the cars' map. */
@Inject
class RefreshCarsUseCase(private val carRepository: CarRepository) {
    suspend operator fun invoke(): Result<Unit> = carRepository.refresh()
}
