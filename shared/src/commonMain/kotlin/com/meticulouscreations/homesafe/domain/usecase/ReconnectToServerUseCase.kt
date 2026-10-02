package com.meticulouscreations.homesafe.domain.usecase

import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import dev.zacsweers.metro.Inject

/** See [ConnectionRepository.reconnect]: re-checks the route and session now, then re-reads the cameras. */
@Inject
class ReconnectToServerUseCase(private val connectionRepository: ConnectionRepository) {
    suspend operator fun invoke() = connectionRepository.reconnect()
}
