package com.meticulouscreations.homesafe.data

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ConnectionHistoryDao {
    @Insert
    suspend fun insert(entry: ConnectionHistoryEntity)

    @Query("SELECT * FROM ConnectionHistoryEntity ORDER BY connectedAtEpochMillis DESC LIMIT 1")
    fun mostRecentAsFlow(): Flow<ConnectionHistoryEntity?>
}
