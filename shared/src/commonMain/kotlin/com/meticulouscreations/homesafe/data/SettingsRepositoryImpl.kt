package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.AlertSettings
import com.meticulouscreations.homesafe.domain.model.AlertZone
import com.meticulouscreations.homesafe.domain.model.MomentCategory
import com.meticulouscreations.homesafe.domain.repository.SettingsRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class SettingsRepositoryImpl(private val settingsDao: SettingsDao) : SettingsRepository {

    override fun observeSettings(): Flow<AlertSettings> =
        combine(settingsDao.observe(), settingsDao.observeZoneRules()) { entity, rules ->
            AlertSettings(
                pushNotificationsEnabled = entity?.pushNotificationsEnabled ?: AlertSettings.DEFAULT.pushNotificationsEnabled,
                zoneRules = rules.associate { it.toDomain() },
                quietFamiliarPeople = entity?.quietFamiliarPeople ?: AlertSettings.DEFAULT.quietFamiliarPeople,
                automaticPresence = entity?.automaticPresence ?: AlertSettings.DEFAULT.automaticPresence,
            )
        }

    override suspend fun updateSettings(settings: AlertSettings) {
        settingsDao.upsert(
            SettingsEntity(
                pushNotificationsEnabled = settings.pushNotificationsEnabled,
                quietFamiliarPeople = settings.quietFamiliarPeople,
                automaticPresence = settings.automaticPresence,
            ),
        )
        settingsDao.upsertZoneRules(settings.zoneRules.map { (place, categories) -> place.toEntity(categories) })
    }
}

private fun AlertZoneRuleEntity.toDomain(): Pair<AlertZone, Set<MomentCategory>> =
    AlertZone(camera, zone.takeIf { it != AlertZoneRuleEntity.NO_ZONE }) to buildSet {
        if (notifyPeople) add(MomentCategory.PEOPLE)
        if (notifyVehicles) add(MomentCategory.VEHICLES)
        if (notifyAnimals) add(MomentCategory.ANIMALS)
    }

private fun AlertZone.toEntity(categories: Set<MomentCategory>) = AlertZoneRuleEntity(
    camera = camera,
    zone = zone ?: AlertZoneRuleEntity.NO_ZONE,
    notifyPeople = MomentCategory.PEOPLE in categories,
    notifyVehicles = MomentCategory.VEHICLES in categories,
    notifyAnimals = MomentCategory.ANIMALS in categories,
)
