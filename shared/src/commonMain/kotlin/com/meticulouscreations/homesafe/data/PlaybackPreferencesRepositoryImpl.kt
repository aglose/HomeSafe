package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.PlaybackPreferences
import com.meticulouscreations.homesafe.domain.model.StreamQuality
import com.meticulouscreations.homesafe.domain.repository.PlaybackPreferencesRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class PlaybackPreferencesRepositoryImpl(private val settingsDao: SettingsDao) : PlaybackPreferencesRepository {

    override fun observePlaybackPreferences(): Flow<PlaybackPreferences> =
        settingsDao.observePlaybackPreferences().map { entity ->
            if (entity == null) {
                PlaybackPreferences.DEFAULT
            } else {
                PlaybackPreferences(
                    // A quality this build doesn't know (a downgrade after a newer one added one) falls back to Auto rather than crashing.
                    quality = StreamQuality.entries.firstOrNull { it.name == entity.quality } ?: PlaybackPreferences.DEFAULT.quality,
                    soundOn = entity.soundOn,
                )
            }
        }

    override suspend fun updatePlaybackPreferences(preferences: PlaybackPreferences) {
        settingsDao.upsertPlaybackPreferences(PlaybackPreferencesEntity(quality = preferences.quality.name, soundOn = preferences.soundOn))
    }
}
