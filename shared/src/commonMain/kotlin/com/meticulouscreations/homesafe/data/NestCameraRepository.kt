package com.meticulouscreations.homesafe.data

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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
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
                                // Right after sign-in the route may still be being chosen.
                                fetch(identity, connectionRepository.currentServerUrl.filterNotNull().first())
                            }
                        }
                }
            }

    override suspend fun refresh() {
        val identity = connectionRepository.activeConnection.value?.serverUrl ?: return
        val serverUrl = connectionRepository.currentServerUrl.value ?: return
        fetch(identity, serverUrl)
    }

    private suspend fun fetch(identity: String, serverUrl: String) {
        apiClient.getNestStreamNames(serverUrl)
            .onSuccess { names -> known.update { it + (identity to names.map(::indoorCamera)) } }
            // A first answer that failed is still an answer: the page stops waiting for it.
            .onFailure { error ->
                if (error is CancellationException) throw error
                if (identity !in known.value) known.update { it + (identity to emptyList()) }
            }
    }

    private fun indoorCamera(streamName: String) = Camera(name = streamName, enabled = true, placement = CameraPlacement.Indoor)
}
