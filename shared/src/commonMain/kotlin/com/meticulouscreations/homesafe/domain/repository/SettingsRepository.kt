package com.meticulouscreations.homesafe.domain.repository

import com.meticulouscreations.homesafe.domain.model.AlertSettings
import kotlinx.coroutines.flow.Flow

/** Persists the user's alert preferences. */
interface SettingsRepository {
    fun observeSettings(): Flow<AlertSettings>
    suspend fun updateSettings(settings: AlertSettings)
}
