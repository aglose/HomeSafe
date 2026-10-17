package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.CameraLayout
import com.meticulouscreations.homesafe.domain.model.CameraOrientation
import com.meticulouscreations.homesafe.domain.repository.CameraLayoutRepository
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Camera layouts in [CameraListDao], kept by the server's identity
 * ([com.meticulouscreations.homesafe.domain.model.ActiveConnection.serverUrl]) like the camera
 * caches, so a local ↔ Tailscale route switch keeps them.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class CameraLayoutRepositoryImpl(
    private val dao: CameraListDao,
    private val connectionRepository: ConnectionRepository,
) : CameraLayoutRepository {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeLayouts(): Flow<Map<String, CameraLayout>> =
        connectionRepository.activeConnection
            .map { it?.serverUrl }
            .distinctUntilChanged()
            .flatMapLatest { identity ->
                if (identity == null) {
                    flowOf(emptyMap())
                } else {
                    dao.observeLayouts(identity).map { rows ->
                        rows.associate { it.name to CameraLayout(it.position, CameraOrientation(it.quarterTurns, it.tiltDegrees)) }
                    }
                }
            }

    override suspend fun saveOrder(cameraNames: List<String>) {
        val identity = connectionRepository.activeConnection.value?.serverUrl ?: return
        val current = dao.observeLayouts(identity).first().associateBy { it.name }
        dao.upsertLayouts(
            cameraNames.mapIndexed { position, name ->
                (current[name] ?: CameraLayoutEntity(serverUrl = identity, name = name)).copy(position = position)
            },
        )
    }

    override suspend fun setOrientation(cameraName: String, orientation: CameraOrientation) {
        val identity = connectionRepository.activeConnection.value?.serverUrl ?: return
        val current = dao.observeLayouts(identity).first().firstOrNull { it.name == cameraName }
        val row = (current ?: CameraLayoutEntity(serverUrl = identity, name = cameraName))
            .copy(quarterTurns = orientation.quarterTurns, tiltDegrees = orientation.tiltDegrees)
        dao.upsertLayouts(listOf(row))
    }
}
