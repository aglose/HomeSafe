package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.Camera
import com.meticulouscreations.homesafe.domain.repository.CameraRepository
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Reads cameras from the local cache only. A refresh happens as a side effect of
 * [ConnectionRepositoryImpl.connect] succeeding — there's no pull-to-refresh affordance in the
 * UI today, so no second refresh trigger is introduced here.
 *
 * The cache is keyed by the server's identity ([com.meticulouscreations.homesafe.domain.model.ActiveConnection.serverUrl]),
 * not by whichever address is active, so a local ↔ Tailscale route switch doesn't empty the list.
 */
@Inject
@SingleIn(AppScope::class)
class CameraRepositoryImpl(
    private val cameraDao: CameraDao,
    private val connectionRepository: ConnectionRepository,
) : CameraRepository {

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun observeCameras(): Flow<List<Camera>> =
        connectionRepository.activeConnection
            .map { it?.serverUrl }
            .distinctUntilChanged()
            .flatMapLatest { serverUrl ->
                if (serverUrl == null) {
                    flowOf(emptyList())
                } else {
                    cameraDao.observeByServer(serverUrl).map { entities ->
                        entities.map {
                            Camera(
                                name = it.name,
                                enabled = it.enabled,
                                liveStreamName = it.liveStreamName,
                                gridStreamName = it.gridStreamName,
                            )
                        }
                    }
                }
            }
}
