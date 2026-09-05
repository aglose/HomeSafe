package com.meticulouscreations.homesafe.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

class InMemorySettingsDao : SettingsDao {
    private val entity = MutableStateFlow<SettingsEntity?>(null)
    private val zoneRules = MutableStateFlow<List<AlertZoneRuleEntity>>(emptyList())

    override fun observe(): Flow<SettingsEntity?> = entity

    override suspend fun upsert(entity: SettingsEntity) {
        this.entity.value = entity
    }

    override fun observeZoneRules(): Flow<List<AlertZoneRuleEntity>> = zoneRules

    override suspend fun upsertZoneRules(rules: List<AlertZoneRuleEntity>) {
        zoneRules.update { current ->
            val replaced = rules.associateBy { it.camera to it.zone }
            current.filter { (it.camera to it.zone) !in replaced } + rules
        }
    }
}
