package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.CameraPlacement
import com.meticulouscreations.homesafe.domain.model.HomeLayout
import com.meticulouscreations.homesafe.domain.repository.PropertyLayoutRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class PropertyLayoutRepositoryImpl(private val dao: PropertyLayoutDao) : PropertyLayoutRepository {

    override fun observePlacements(): Flow<List<CameraPlacement>> =
        dao.observePlacements().map { rows ->
            // A row written by a newer build (or corrupted) could be outside the plan; clamp
            // rather than drop, so a marker turns up at the edge instead of vanishing silently.
            rows.map { CameraPlacement(it.cameraName, it.x.coerceIn(0f, 1f), it.y.coerceIn(0f, 1f)) }
        }

    override suspend fun place(placement: CameraPlacement) {
        dao.upsertPlacement(CameraPlacementEntity(placement.cameraName, placement.x, placement.y))
    }

    override suspend fun removePlacement(cameraName: String) {
        dao.deletePlacement(cameraName)
    }

    override fun observeHomeLayout(): Flow<HomeLayout> =
        dao.observeHomeLayout().map { HomeLayout.of(it?.layout) }

    override suspend fun setHomeLayout(layout: HomeLayout) {
        dao.upsertHomeLayout(HomeLayoutEntity(layout = layout.name))
    }
}
