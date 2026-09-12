package com.meticulouscreations.homesafe.domain.repository

import com.meticulouscreations.homesafe.domain.model.CameraPlacement
import com.meticulouscreations.homesafe.domain.model.HomeLayout
import kotlinx.coroutines.flow.Flow

/** Persists the property plan: where each camera has been placed, and which Home layout is up. */
interface PropertyLayoutRepository {
    fun observePlacements(): Flow<List<CameraPlacement>>
    suspend fun place(placement: CameraPlacement)
    suspend fun removePlacement(cameraName: String)

    fun observeHomeLayout(): Flow<HomeLayout>
    suspend fun setHomeLayout(layout: HomeLayout)
}
