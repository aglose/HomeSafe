package com.meticulouscreations.homesafe.domain.usecase

import com.meticulouscreations.homesafe.domain.model.AlertVolume
import com.meticulouscreations.homesafe.domain.repository.AlertVolumeRepository
import dev.zacsweers.metro.Inject

/** How often each alert rule would have fired over the last week; see [AlertVolume.estimate]. */
@Inject
class EstimateAlertVolumeUseCase(private val repository: AlertVolumeRepository) {
    suspend operator fun invoke(): Result<AlertVolume> = repository.estimate()
}
