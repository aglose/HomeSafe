package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.ActiveConnection
import com.meticulouscreations.homesafe.domain.model.Camera
import com.meticulouscreations.homesafe.domain.model.CameraPlacement
import com.meticulouscreations.homesafe.domain.model.CameraSource
import com.meticulouscreations.homesafe.domain.model.OUTDOOR_STREAM_PREFIX
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.domain.repository.NestCameraRepository
import com.meticulouscreations.homesafe.network.FrigateApiClient
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The Nest cameras, found in the server's go2rtc: every stream there with a `nest:` source is
 * one (see `nestStreams`), indoors unless its name starts with [OUTDOOR_STREAM_PREFIX]. A
 * `<name>_sub` stream beside it is what its card plays, as a Frigate camera's card plays its sub
 * stream.
 *
 * What the server last said is kept on the device ([CameraListDao]), so the Home page has the
 * cameras the moment it opens, Indoor switch and all, rather than once go2rtc has answered. The
 * server is asked again as early as the launch knows where it is going (see
 * `LiveStreamPrefetcher`), the first time the list is wanted in a session, and on every pull to
 * refresh. A failed answer changes nothing.
 *
 * Kept by the server's identity ([ActiveConnection.serverUrl]), like the Frigate camera cache, so
 * a local ↔ Tailscale route switch doesn't empty the list; it is fetched from whichever address
 * is in use.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class NestCameraRepositoryImpl(
    private val apiClient: FrigateApiClient,
    private val connectionRepository: ConnectionRepository,
    private val dao: CameraListDao,
) : NestCameraRepository {

    /**
     * The servers that have answered this session: the record is a head start, not a substitute
     * for asking. Only an answer counts, so a request cancelled (Home left before it came back) or
     * failed is made again the next time the list is wanted.
     */
    private val askedThisSession = MutableStateFlow<Set<String>>(emptySet())

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeNestCameras(): Flow<List<Camera>?> =
        connectionRepository.activeConnection
            .map { it?.serverUrl }
            .distinctUntilChanged()
            .flatMapLatest { identity ->
                if (identity == null) {
                    flowOf(emptyList())
                } else {
                    channelFlow {
                        // Beside the record, not before it: the cameras kept from last time are
                        // what the page opens on, and mustn't wait for the server.
                        if (identity !in askedThisSession.value) {
                            // The connection as it is now, so the identity and the address asked are one server's.
                            connectionRepository.activeConnection.value?.takeIf { it.serverUrl == identity }?.let { launch { refresh(it) } }
                        }
                        dao.observeNestCameras(identity)
                            .map { rows -> rows.map(::nestCamera).sortedBy { it.name } }
                            .distinctUntilChanged()
                            .collect { send(it) }
                    }
                }
            }

    override suspend fun refresh() {
        connectionRepository.activeConnection.value?.let { refresh(it) }
    }

    /**
     * Asks [connection]'s server over its active route. One [ActiveConnection] for both the cache
     * key and the address: `currentServerUrl` follows the connection a beat behind, so reading
     * the two apart could file one server's cameras under another, or ask a route just left.
     */
    override suspend fun refresh(connection: ActiveConnection) {
        val identity = connection.serverUrl
        apiClient.getNestStreams(connection.activeUrl)
            .onSuccess { streams ->
                dao.insertNestCameras(streams.map { NestCameraEntity(serverUrl = identity, name = it.name, gridStreamName = it.gridStreamName) })
                dao.deleteOtherNestCameras(identity, streams.map { it.name })
                askedThisSession.update { it + identity }
            }
            .onFailure { error -> if (error is CancellationException) throw error }
    }

    /** A Nest camera: its card plays the grid stream when the server has one, its page the camera's own. */
    private fun nestCamera(row: NestCameraEntity) = Camera(
        name = row.name,
        enabled = true,
        gridStreamName = row.gridStreamName ?: row.name,
        placement = if (row.name.startsWith(OUTDOOR_STREAM_PREFIX)) CameraPlacement.Outdoor else CameraPlacement.Indoor,
        source = CameraSource.Nest,
    )
}
