package com.meticulouscreations.homesafe.domain.usecase

import com.meticulouscreations.homesafe.domain.model.DetectionSettings
import com.meticulouscreations.homesafe.domain.repository.SettingsRepository
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow

@Inject
class ObserveSettingsUseCase(private val settingsRepository: SettingsRepository) {
    operator fun invoke(): Flow<DetectionSettings> = settingsRepository.observeSettings()
}
