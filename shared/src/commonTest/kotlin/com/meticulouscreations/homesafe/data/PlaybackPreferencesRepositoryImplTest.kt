package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.PlaybackPreferences
import com.meticulouscreations.homesafe.domain.model.StreamQuality
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackPreferencesRepositoryImplTest {

    @Test
    fun defaultsUntilSomethingIsSaved() = runTest {
        val repository = PlaybackPreferencesRepositoryImpl(InMemorySettingsDao())

        assertEquals(PlaybackPreferences.DEFAULT, repository.observePlaybackPreferences().first())
    }

    @Test
    fun roundTripsQualityAndSound() = runTest {
        val repository = PlaybackPreferencesRepositoryImpl(InMemorySettingsDao())

        repository.updatePlaybackPreferences(PlaybackPreferences(quality = StreamQuality.LOW, soundOn = true))

        assertEquals(PlaybackPreferences(quality = StreamQuality.LOW, soundOn = true), repository.observePlaybackPreferences().first())
    }

    @Test
    fun anUnknownSavedQualityFallsBackToAuto() = runTest {
        val dao = InMemorySettingsDao()
        dao.upsertPlaybackPreferences(PlaybackPreferencesEntity(quality = "ULTRA", soundOn = true))

        assertEquals(PlaybackPreferences(quality = StreamQuality.AUTO, soundOn = true), PlaybackPreferencesRepositoryImpl(dao).observePlaybackPreferences().first())
    }
}
