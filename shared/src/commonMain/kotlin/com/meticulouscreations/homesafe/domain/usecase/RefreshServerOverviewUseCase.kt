package com.meticulouscreations.homesafe.domain.usecase

import com.meticulouscreations.homesafe.domain.repository.ServerStatusRepository
import dev.zacsweers.metro.Inject

/** Re-reads the server's stats and config now, rather than waiting for the next poll. */
@Inject
class RefreshServerOverviewUseCase(private val serverStatusRepository: ServerStatusRepository) {
    suspend operator fun invoke() = serverStatusRepository.refresh()
}
