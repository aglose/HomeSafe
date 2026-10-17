package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.ActiveConnection
import com.meticulouscreations.homesafe.domain.model.Camera
import com.meticulouscreations.homesafe.domain.model.CameraPlacement
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.domain.repository.IndoorCameraRepository
import com.meticulouscreations.homesafe.network.FrigateApiClient
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update

/**
 * The indoor cameras, found in the server's go2rtc: every stream there with a `nest:` source is
 * one (see `nestStreamNames`). Nothing is stored on the device. The list is short, the server is
 * asked the first time it is wanted and on every pull to refresh, and until it answers the Home
 * page is showing the outdoor cameras anyway.
 *
 * Kept by the server's identity ([com.meticulouscreations.homesafe.domain.model.ActiveConnection.serverUrl]),
 * like the Frigate camera cache, so a local ↔ Tailscale route switch doesn't empty the list; it
 * is fetched from whichever address is in use.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class NestCameraRepository(
    private val apiClient: FrigateApiClient,
    private val connectionRepository: ConnectionRepository,
) : IndoorCameraRepository {

    /** Server identity -> its indoor cameras, as last answered. Absent until the first answer. */
    private val known = MutableStateFlow<Map<String, List<Camera>>>(emptyMap())

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeIndoorCameras(): Flow<List<Camera>?> =
        connectionRepository.activeConnection
            .map { it?.serverUrl }
            .distinctUntilChanged()
            .flatMapLatest { identity ->
                if (identity == null) {
                    flowOf(emptyList())
                } else {
                    known
                        .map { it[identity] }
                        .distinctUntilChanged()
                        .onStart {
                            if (identity !in known.value) {
                                // The connection as it is now, so the identity and the address asked are one server's.
                                connectionRepository.activeConnection.value?.takeIf { it.serverUrl == identity }?.let { fetch(it) }
                            }
                        }
                }
            }

    override suspend fun refresh() {
        connectionRepository.activeConnection.value?.let { fetch(it) }
    }

    /**
     * Asks [connection]'s server over its active route. One [ActiveConnection] for both the cache
     * key and the address: `currentServerUrl` follows the connection a beat behind, so reading
     * the two apart could file one server's cameras under another, or ask a route just left.
     */
    private suspend fun fetch(connection: ActiveConnection) {
        val identity = connection.serverUrl
        apiClient.getNestStreamNames(connection.activeUrl)
            .onSuccess { names -> known.update { it + (identity to names.map(::indoorCamera)) } }
            // A first answer that failed is still an answer: the page stops waiting for it.
            .onFailure { error ->
                if (error is CancellationException) throw error
                if (identity !in known.value) known.update { it + (identity to emptyList()) }
            }
    }

    private fun indoorCamera(streamName: String) = Camera(name = streamName, enabled = true, placement = CameraPlacement.Indoor)
}
