package com.meticulouscreations.homesafe.domain.usecase

import com.meticulouscreations.homesafe.domain.model.ServerOverview
import com.meticulouscreations.homesafe.domain.repository.ServerStatusRepository
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow

@Inject
class ObserveServerOverviewUseCase(private val serverStatusRepository: ServerStatusRepository) {
    operator fun invoke(): Flow<ServerOverview?> = serverStatusRepository.observeOverview()
}
