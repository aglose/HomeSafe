package com.meticulouscreations.homesafe.domain.usecase

import com.meticulouscreations.homesafe.domain.model.DetectionSettings
import com.meticulouscreations.homesafe.domain.repository.SettingsRepository
import dev.zacsweers.metro.Inject

@Inject
class UpdateSettingsUseCase(private val settingsRepository: SettingsRepository) {
    suspend operator fun invoke(settings: DetectionSettings) = settingsRepository.updateSettings(settings)
}
