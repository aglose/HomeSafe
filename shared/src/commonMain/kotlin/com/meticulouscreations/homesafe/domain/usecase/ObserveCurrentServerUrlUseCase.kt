package com.meticulouscreations.homesafe.domain.usecase

import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.StateFlow

/** See [ConnectionRepository.currentServerUrl]: the address in use right now, or null when signed out. */
@Inject
class ObserveCurrentServerUrlUseCase(private val connectionRepository: ConnectionRepository) {
    operator fun invoke(): StateFlow<String?> = connectionRepository.currentServerUrl
}
