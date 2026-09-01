package com.meticulouscreations.homesafe.domain.repository

import com.meticulouscreations.homesafe.domain.model.DetectionSettings
import kotlinx.coroutines.flow.Flow

/** Persists user-configurable detection/alert toggles. */
interface SettingsRepository {
    fun observeSettings(): Flow<DetectionSettings>
    suspend fun updateSettings(settings: DetectionSettings)
}
