package com.meticulouscreations.homesafe.domain.usecase

import com.meticulouscreations.homesafe.domain.model.PlaybackPreferences
import com.meticulouscreations.homesafe.domain.repository.PlaybackPreferencesRepository
import dev.zacsweers.metro.Inject

@Inject
class UpdatePlaybackPreferencesUseCase(private val repository: PlaybackPreferencesRepository) {
    suspend operator fun invoke(preferences: PlaybackPreferences) = repository.updatePlaybackPreferences(preferences)
}
