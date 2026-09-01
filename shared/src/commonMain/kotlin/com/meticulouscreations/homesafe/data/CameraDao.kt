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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(cameras: List<CameraEntity>)
}
