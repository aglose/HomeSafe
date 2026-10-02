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

    @Query("SELECT * FROM PlaybackPreferencesEntity WHERE id = 0")
    fun observePlaybackPreferences(): Flow<PlaybackPreferencesEntity?>

    @Upsert
    suspend fun upsertPlaybackPreferences(entity: PlaybackPreferencesEntity)

    @Query("SELECT * FROM ChartPreferencesEntity WHERE id = 0")
    fun observeChartPreferences(): Flow<ChartPreferencesEntity?>

    @Upsert
    suspend fun upsertChartPreferences(entity: ChartPreferencesEntity)

    @Query("SELECT * FROM DeviceIdentityEntity WHERE id = 0")
    suspend fun getDeviceIdentity(): DeviceIdentityEntity?

    @Upsert
    suspend fun upsertDeviceIdentity(entity: DeviceIdentityEntity)

    @Query("SELECT * FROM WatchedSymbolEntity ORDER BY addedAtEpochSeconds, symbol")
    fun observeWatchedSymbols(): Flow<List<WatchedSymbolEntity>>

    @Upsert
    suspend fun upsertWatchedSymbol(entity: WatchedSymbolEntity)

    @Query("DELETE FROM WatchedSymbolEntity WHERE symbol = :symbol")
    suspend fun deleteWatchedSymbol(symbol: String)
}
