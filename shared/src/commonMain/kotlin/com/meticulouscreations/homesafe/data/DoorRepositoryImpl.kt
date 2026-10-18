package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.DoorNotify
import com.meticulouscreations.homesafe.domain.model.DoorsSnapshot
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.domain.repository.DoorRepository
import com.meticulouscreations.homesafe.network.PushRelayApi
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Mirrors the relay's doors. Like [PresenceRepositoryImpl], the poll runs only while [doors] has
 * a collector (the Home card, the Doors page) and restarts on every server change. A door can
 * change any second, so it polls more often than presence does; a push covers the openings that
 * matter while the app is closed.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DoorRepositoryImpl(
    private val relayApi: PushRelayApi,
    private val identity: DeviceIdentityStore,
    private val connectionRepository: ConnectionRepository,
    appScope: CoroutineScope,
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
) : DoorRepository {

    private val _doors = MutableStateFlow(DoorsSnapshot.EMPTY)
    override val doors: StateFlow<DoorsSnapshot> = _doors.asStateFlow()

    init {
        appScope.launch {
            connectionRepository.currentServerUrl.collect { url -> if (url == null) _doors.value = DoorsSnapshot.EMPTY }
        }
        appScope.launch {
            combine(
                _doors.subscriptionCount.map { it > 0 }.distinctUntilChanged(),
                connectionRepository.currentServerUrl,
            ) { observed, url -> url.takeIf { observed } }
                .distinctUntilChanged()
                .collectLatest { url ->
                    if (url == null) return@collectLatest
                    while (true) {
                        fetch(url)
                        delay(pollIntervalMs)
                    }
                }
        }
    }

    override suspend fun refresh(): Result<Unit> {
        val url = connectionRepository.currentServerUrl.value ?: return Result.failure(notConnected())
        return fetch(url)
    }

    override suspend fun setNotify(doorId: String, notify: DoorNotify): Result<Unit> {
        val url = connectionRepository.currentServerUrl.value ?: return Result.failure(notConnected())
        // Shown at once; the relay's answer then replaces it, or a failure puts the old one back.
        val before = _doors.value
        _doors.update { snapshot -> snapshot.copy(doors = snapshot.doors.map { if (it.id == doorId) it.copy(notify = notify) else it }) }
        return relayApi.setDoorNotify(url, doorId, notify)
            .map { _doors.value = it }
            .onFailure { _doors.value = before }
    }

    private suspend fun fetch(url: String): Result<Unit> =
        relayApi.getDoors(url, identity.deviceId(), identity.secret()).map { _doors.value = it }

    private companion object {
        const val POLL_INTERVAL_MS = 15_000L
    }
}
