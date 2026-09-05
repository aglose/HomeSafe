package com.meticulouscreations.homesafe.domain.usecase

import com.meticulouscreations.homesafe.domain.model.ActiveConnection
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.StateFlow

/** See [ConnectionRepository.activeConnection]: the signed-in server and the route to it, or null when signed out. */
@Inject
class ObserveActiveConnectionUseCase(private val connectionRepository: ConnectionRepository) {
    operator fun invoke(): StateFlow<ActiveConnection?> = connectionRepository.activeConnection
}
