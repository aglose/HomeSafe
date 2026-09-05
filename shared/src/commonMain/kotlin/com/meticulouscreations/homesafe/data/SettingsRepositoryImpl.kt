package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.AlertSettings
import com.meticulouscreations.homesafe.domain.repository.SettingsRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Inject
@SingleIn(AppScope::class)
class SettingsRepositoryImpl(private val settingsDao: SettingsDao) : SettingsRepository {

    override fun observeSettings(): Flow<AlertSettings> =
        settingsDao.observe().map { entity -> entity?.toDomain() ?: AlertSettings.DEFAULT }

    override suspend fun updateSettings(settings: AlertSettings) {
        settingsDao.upsert(settings.toEntity())
    }
}

private fun SettingsEntity.toDomain() = AlertSettings(
    pushNotificationsEnabled = pushNotificationsEnabled,
    notifyPeople = notifyPeople,
    notifyVehicles = notifyVehicles,
    notifyAnimals = notifyAnimals,
)

private fun AlertSettings.toEntity() = SettingsEntity(
    pushNotificationsEnabled = pushNotificationsEnabled,
    notifyPeople = notifyPeople,
    notifyVehicles = notifyVehicles,
    notifyAnimals = notifyAnimals,
)
