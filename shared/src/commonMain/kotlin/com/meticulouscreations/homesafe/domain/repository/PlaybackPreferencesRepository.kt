package com.meticulouscreations.homesafe.domain.repository

import com.meticulouscreations.homesafe.domain.model.PlaybackPreferences
import kotlinx.coroutines.flow.Flow

interface PlaybackPreferencesRepository {
    fun observePlaybackPreferences(): Flow<PlaybackPreferences>
    suspend fun updatePlaybackPreferences(preferences: PlaybackPreferences)
}
