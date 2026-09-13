package com.meticulouscreations.homesafe.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** The web target's stand-in: the same contract, held in memory for the life of the tab. */
class InMemoryPropertyLayoutDao : PropertyLayoutDao {
    private val placements = MutableStateFlow<List<CameraPlacementEntity>>(emptyList())
    private val homeLayout = MutableStateFlow<HomeLayoutEntity?>(null)

    override fun observePlacements(): Flow<List<CameraPlacementEntity>> = placements

    override suspend fun upsertPlacement(placement: CameraPlacementEntity) {
        placements.update { current -> current.filterNot { it.cameraName == placement.cameraName } + placement }
    }

    override suspend fun deletePlacement(cameraName: String) {
        placements.update { current -> current.filterNot { it.cameraName == cameraName } }
    }

    override fun observeHomeLayout(): Flow<HomeLayoutEntity?> = homeLayout

    override suspend fun upsertHomeLayout(entity: HomeLayoutEntity) {
        homeLayout.value = entity
    }
}
