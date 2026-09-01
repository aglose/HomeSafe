package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.DetectionSettings
import com.meticulouscreations.homesafe.domain.repository.SettingsRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val defaultSettings = DetectionSettings(
    autoPurgeOldMedia = true,
    globalMotionDetection = true,
    coralEdgeInference = true,
    faceRecognition = false,
    pushNotificationsEnabled = false,
)

@Inject
@SingleIn(AppScope::class)
class SettingsRepositoryImpl(private val settingsDao: SettingsDao) : SettingsRepository {

    override fun observeSettings(): Flow<DetectionSettings> =
        settingsDao.observe().map { entity -> entity?.toDomain() ?: defaultSettings }

    override suspend fun updateSettings(settings: DetectionSettings) {
        settingsDao.upsert(settings.toEntity())
    }
}

private fun SettingsEntity.toDomain() = DetectionSettings(
    autoPurgeOldMedia = autoPurgeOldMedia,
    globalMotionDetection = globalMotionDetection,
    coralEdgeInference = coralEdgeInference,
    faceRecognition = faceRecognition,
    pushNotificationsEnabled = pushNotificationsEnabled,
)

private fun DetectionSettings.toEntity() = SettingsEntity(
    autoPurgeOldMedia = autoPurgeOldMedia,
    globalMotionDetection = globalMotionDetection,
    coralEdgeInference = coralEdgeInference,
    faceRecognition = faceRecognition,
    pushNotificationsEnabled = pushNotificationsEnabled,
)
