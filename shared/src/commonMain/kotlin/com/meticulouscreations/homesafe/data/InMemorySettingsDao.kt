package com.meticulouscreations.homesafe.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** A non-persistent [SettingsDao] for platforms without a working SQLite driver yet. */
class InMemorySettingsDao : SettingsDao {
    private val entity = MutableStateFlow<SettingsEntity?>(null)

    override fun observe(): Flow<SettingsEntity?> = entity

    override suspend fun upsert(entity: SettingsEntity) {
        this.entity.value = entity
    }
}
