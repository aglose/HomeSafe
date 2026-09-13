package com.meticulouscreations.homesafe.data

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CameraDao {
    @Query("SELECT * FROM CameraEntity WHERE serverUrl = :serverUrl")
    fun observeByServer(serverUrl: String): Flow<List<CameraEntity>>

    @Query("DELETE FROM CameraEntity WHERE serverUrl = :serverUrl")
    suspend fun deleteByServer(serverUrl: String)

    /** Drops [serverUrl]'s cameras that are not in [names] — the second half of a refresh that never empties the list. */
    @Query("DELETE FROM CameraEntity WHERE serverUrl = :serverUrl AND name NOT IN (:names)")
    suspend fun deleteOthers(serverUrl: String, names: List<String>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(cameras: List<CameraEntity>)
}
