package com.meticulouscreations.homesafe.data

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

/** Reads and writes the property plan's own state: where the cameras sit, and which layout is up. */
@Dao
interface PropertyLayoutDao {
    @Query("SELECT * FROM CameraPlacementEntity")
    fun observePlacements(): Flow<List<CameraPlacementEntity>>

    @Upsert
    suspend fun upsertPlacement(placement: CameraPlacementEntity)

    @Query("DELETE FROM CameraPlacementEntity WHERE cameraName = :cameraName")
    suspend fun deletePlacement(cameraName: String)

    @Query("SELECT * FROM HomeLayoutEntity WHERE id = 0")
    fun observeHomeLayout(): Flow<HomeLayoutEntity?>

    @Upsert
    suspend fun upsertHomeLayout(entity: HomeLayoutEntity)
}
