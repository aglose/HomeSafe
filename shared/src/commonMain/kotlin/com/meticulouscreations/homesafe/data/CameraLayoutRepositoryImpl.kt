package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.CameraCrop
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    /**
     * Held across each read-modify-write: a drop and a turn saved at the same moment would
     * otherwise both read the same row, and whichever wrote last would undo the other.
     */
    private val writing = Mutex()

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
                        rows.associate { row ->
                            row.name to CameraLayout(
                                position = row.position,
                                orientation = CameraOrientation(row.quarterTurns, row.tiltDegrees),
                                crop = CameraCrop(row.cropZoom, row.cropCenterX, row.cropCenterY),
                                hidden = row.hidden,
                            )
                        }
                    }
                }
            }

    override suspend fun saveOrder(cameraNames: List<String>) = writing.withLock {
        val identity = connectionRepository.activeConnection.value?.serverUrl ?: return@withLock
        val current = dao.observeLayouts(identity).first().associateBy { it.name }
        dao.upsertLayouts(
            cameraNames.mapIndexed { position, name ->
                (current[name] ?: CameraLayoutEntity(serverUrl = identity, name = name)).copy(position = position)
            },
        )
    }

    override suspend fun setOrientation(cameraName: String, orientation: CameraOrientation) = update(cameraName) { row ->
        row.copy(quarterTurns = orientation.quarterTurns, tiltDegrees = orientation.tiltDegrees)
    }

    override suspend fun setFraming(cameraName: String, orientation: CameraOrientation, crop: CameraCrop) = update(cameraName) { row ->
        row.copy(
            quarterTurns = orientation.quarterTurns,
            tiltDegrees = orientation.tiltDegrees,
            cropZoom = crop.zoom,
            cropCenterX = crop.centerX,
            cropCenterY = crop.centerY,
        )
    }

    override suspend fun setHidden(cameraName: String, hidden: Boolean) = update(cameraName) { row -> row.copy(hidden = hidden) }

    /** [cameraName]'s row, or a new one, changed by [change]: everything else in it is kept as it was. */
    private suspend fun update(cameraName: String, change: (CameraLayoutEntity) -> CameraLayoutEntity) = writing.withLock {
        val identity = connectionRepository.activeConnection.value?.serverUrl ?: return@withLock
        val current = dao.observeLayouts(identity).first().firstOrNull { it.name == cameraName }
        dao.upsertLayouts(listOf(change(current ?: CameraLayoutEntity(serverUrl = identity, name = cameraName))))
    }
}
