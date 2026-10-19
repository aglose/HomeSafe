package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.DoorNotify
import com.meticulouscreations.homesafe.domain.model.DoorSpot
import com.meticulouscreations.homesafe.domain.model.DoorsSnapshot
import com.meticulouscreations.homesafe.domain.model.FloorPlan
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    /** The doors as the relay last said, before any setting still on its way is laid over them. */
    private var fromRelay = DoorsSnapshot.EMPTY

    /** Settings chosen here and not yet answered, by door: each with its number, so a later choice for the same door supersedes it. */
    private val pending = mutableMapOf<String, Pair<Long, DoorNotify>>()

    /** Spots placed here and not yet answered, by door, the same way: a null spot is a door taken off the plan. */
    private val pendingSpots = mutableMapOf<String, Pair<Long, DoorSpot?>>()
    private var edits = 0L

    private val _floorPlan = MutableStateFlow<FloorPlan?>(null)
    override val floorPlan: StateFlow<FloorPlan?> = _floorPlan.asStateFlow()

    /** The server [floorPlan] was read from; another server (or a refresh) reads it again. */
    private var planFrom: String? = null

    /** Guards [fromRelay], [pending], [pendingSpots] and [edits]: polls and saves land on different coroutines. */
    private val state = Mutex()

    /** One save at a time, in the order they were chosen (a [Mutex] is fair), so the relay keeps the last one. */
    private val writes = Mutex()

    init {
        appScope.launch {
            connectionRepository.currentServerUrl.collect { url ->
                if (url == null) {
                    state.withLock {
                        pending.clear()
                        pendingSpots.clear()
                        fromRelay = DoorsSnapshot.EMPTY
                        publish()
                    }
                    planFrom = null
                    _floorPlan.value = null
                }
            }
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
        planFrom = null
        return fetch(url)
    }

    /**
     * Shows [notify] at once, then saves it. Saves go to the relay one at a time in the order they
     * were chosen, so two quick taps end on the second. A save that fails takes back only its own
     * door's setting, and only while it is still the latest choice for that door; everything else
     * (other doors' choices, what a poll brought in meanwhile) stays.
     */
    override suspend fun setNotify(doorId: String, notify: DoorNotify): Result<Unit> {
        val url = connectionRepository.currentServerUrl.value ?: return Result.failure(notConnected())
        val edit = state.withLock {
            val edit = ++edits
            pending[doorId] = edit to notify
            publish()
            edit
        }
        val answer = writes.withLock { relayApi.setDoorNotify(url, doorId, notify) }
        state.withLock {
            if (pending[doorId]?.first == edit) pending.remove(doorId)
            // An answer from the server signed out of meanwhile is no one's doors now.
            answer.onSuccess { if (connectionRepository.currentServerUrl.value == url) fromRelay = it }
            publish()
        }
        return answer.map { }
    }

    override suspend fun setSpot(doorId: String, spot: DoorSpot?): Result<Unit> {
        val url = connectionRepository.currentServerUrl.value ?: return Result.failure(notConnected())
        val edit = state.withLock {
            val edit = ++edits
            pendingSpots[doorId] = edit to spot
            publish()
            edit
        }
        val answer = writes.withLock { relayApi.setDoorSpot(url, doorId, spot) }
        state.withLock {
            if (pendingSpots[doorId]?.first == edit) pendingSpots.remove(doorId)
            answer.onSuccess { if (connectionRepository.currentServerUrl.value == url) fromRelay = it }
            publish()
        }
        return answer.map { }
    }

    private suspend fun fetch(url: String): Result<Unit> {
        val deviceId = identity.deviceId()
        val secret = identity.secret()
        // The plan barely ever changes: read once per server, and again on a refresh.
        if (planFrom != url) {
            relayApi.getFloorPlan(url, deviceId, secret).onSuccess {
                _floorPlan.value = it
                planFrom = url
            }
        }
        return relayApi.getDoors(url, deviceId, secret).map { snapshot ->
            state.withLock {
                fromRelay = snapshot
                publish()
            }
        }
    }

    /** [fromRelay] with every setting still on its way laid over it. Called holding [state]. */
    private fun publish() {
        _doors.value = if (pending.isEmpty() && pendingSpots.isEmpty()) {
            fromRelay
        } else {
            fromRelay.copy(
                doors = fromRelay.doors.map { door ->
                    val notify = pending[door.id]?.second ?: door.notify
                    val spot = if (door.id in pendingSpots) pendingSpots.getValue(door.id).second else door.spot
                    door.copy(notify = notify, spot = spot)
                },
            )
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 15_000L
    }
}
