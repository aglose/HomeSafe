package com.meticulouscreations.homesafe.domain.usecase

import com.meticulouscreations.homesafe.domain.repository.ServerStatusRepository
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow

/** See [ServerStatusRepository.observeError]: why the last status poll failed, or null. */
@Inject
class ObserveServerOverviewErrorUseCase(private val serverStatusRepository: ServerStatusRepository) {
    operator fun invoke(): Flow<String?> = serverStatusRepository.observeError()
}
