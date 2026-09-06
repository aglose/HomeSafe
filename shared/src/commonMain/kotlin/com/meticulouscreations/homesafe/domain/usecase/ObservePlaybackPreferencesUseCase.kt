package com.meticulouscreations.homesafe.domain.usecase

import com.meticulouscreations.homesafe.domain.model.PlaybackPreferences
import com.meticulouscreations.homesafe.domain.repository.PlaybackPreferencesRepository
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow

@Inject
class ObservePlaybackPreferencesUseCase(private val repository: PlaybackPreferencesRepository) {
    operator fun invoke(): Flow<PlaybackPreferences> = repository.observePlaybackPreferences()
}
