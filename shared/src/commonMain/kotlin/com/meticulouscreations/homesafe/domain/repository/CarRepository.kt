package com.meticulouscreations.homesafe.domain.repository

import com.meticulouscreations.homesafe.domain.model.HouseholdCar
import com.meticulouscreations.homesafe.domain.model.ParkingStreet
import kotlinx.coroutines.flow.StateFlow

/** The household's Teslas and where they are, as the relay knows them (see docs/parking.md). */
interface CarRepository {
    /** The cars; null until the relay has answered. Re-read every so often while something collects it. */
    val cars: StateFlow<List<HouseholdCar>?>

    /** The watched streets and the signs along their curbs, read with [cars]; empty until then, or without any. */
    val streets: StateFlow<List<ParkingStreet>>

    /** Re-reads the cars now rather than waiting for the next poll. */
    suspend fun refresh(): Result<Unit>
}
