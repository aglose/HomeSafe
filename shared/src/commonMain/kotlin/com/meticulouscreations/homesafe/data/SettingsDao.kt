package com.meticulouscreations.homesafe.data

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SettingsDao {
    @Query("SELECT * FROM SettingsEntity WHERE id = 0")
    fun observe(): Flow<SettingsEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SettingsEntity)

    @Query("SELECT * FROM AlertZoneRuleEntity")
    fun observeZoneRules(): Flow<List<AlertZoneRuleEntity>>

    @Upsert
    suspend fun upsertZoneRules(rules: List<AlertZoneRuleEntity>)
}
