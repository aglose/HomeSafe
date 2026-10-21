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
import kotlinx.coroutines.NonCancellable
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
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Mirrors the relay's doors. Like [PresenceRepositoryImpl], the reading runs only while [doors] has
 * a collector (the Home card, the Doors page) and restarts on every server change; a push covers
 * the openings that matter while the app is closed.
 *
 * A door should show the moment it opens, so rather than polling, each read hands the relay the
 * version it last answered and the relay holds it open until a door changes (or [waitSeconds]
 * pass), then the next read goes straight out. A relay from before that answers at once with no
 * version, and is polled every [pollIntervalMs] instead.
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
    private val waitSeconds: Int = WAIT_SECONDS,
    private val retryMs: Long = RETRY_MS,
) : DoorRepository {

    private val _doors = MutableStateFlow(DoorsSnapshot.EMPTY)
    override val doors: StateFlow<DoorsSnapshot> = _doors.asStateFlow()

    /** The doors as the relay last said, before any setting still on its way is laid over them. */
    private var fromRelay = DoorsSnapshot.EMPTY

    /** The server [fromRelay] came from, null while it's [DoorsSnapshot.EMPTY]. */
    private var doorsFrom: String? = null

    /** Settings chosen here and not yet answered, by door: each with its number, so a later choice for the same door supersedes it. */
    private val pending = mutableMapOf<String, Pair<Long, DoorNotify>>()

    /** Spots placed here and not yet answered, by door, the same way: a null spot is a door taken off the plan. */
    private val pendingSpots = mutableMapOf<String, Pair<Long, DoorSpot?>>()
    private var edits = 0L

    private val _floorPlan = MutableStateFlow<FloorPlan?>(null)
    override val floorPlan: StateFlow<FloorPlan?> = _floorPlan.asStateFlow()

    /** The server [floorPlan] was read from; another server (or a refresh) reads it again. Guarded by [planLock]. */
    private var planFrom: String? = null

    /** One plan read at a time, so a refresh and a poll landing together read it once. */
    private val planLock = Mutex()

    /** Guards [fromRelay], [doorsFrom], [pending], [pendingSpots] and [edits]: polls and saves land on different coroutines. */
    private val state = Mutex()

    /** One save at a time, in the order they were chosen (a [Mutex] is fair), so the relay keeps the last one. */
    private val writes = Mutex()

    init {
        appScope.launch {
            // Signing out, or switching straight to another server, forgets this one's doors and
            // plan, so the next server's sensors are never drawn over this one's house. Judged by
            // where each came from rather than by the last address seen here: a switch made
            // before this first looks would otherwise be missed.
            connectionRepository.currentServerUrl.collect { url ->
                state.withLock {
                    if (url == null || (doorsFrom != null && doorsFrom != url)) {
                        pending.clear()
                        pendingSpots.clear()
                        fromRelay = DoorsSnapshot.EMPTY
                        doorsFrom = null
                        publish()
                    }
                }
                planLock.withLock {
                    if (url == null || (planFrom != null && planFrom != url)) {
                        planFrom = null
                        _floorPlan.value = null
                    }
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
                    var version: Long? = null
                    while (true) {
                        val answer = fetch(url, since = version)
                        version = answer.getOrNull()?.version
                        // With a version, straight back to waiting on the next change.
                        if (version == null) delay(if (answer.isFailure) retryMs else pollIntervalMs)
                    }
                }
        }
    }

    override suspend fun refresh(): Result<Unit> {
        val url = connectionRepository.currentServerUrl.value ?: return Result.failure(notConnected())
        return fetch(url, rereadPlan = true).map { }
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
        val answer = try {
            writes.withLock { relayApi.setDoorNotify(url, doorId, notify) }
        } catch (cancelled: CancellationException) {
            // The page closed while the choice waited its turn or its answer: it was never saved.
            settle { if (pending[doorId]?.first == edit) pending.remove(doorId) }
            throw cancelled
        }
        settle {
            if (pending[doorId]?.first == edit) pending.remove(doorId)
            // An answer from the server signed out of meanwhile is no one's doors now.
            answer.onSuccess {
                if (connectionRepository.currentServerUrl.value == url) {
                    fromRelay = it
                    doorsFrom = url
                }
            }
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
        val answer = try {
            writes.withLock { relayApi.setDoorSpot(url, doorId, spot) }
        } catch (cancelled: CancellationException) {
            settle { if (pendingSpots[doorId]?.first == edit) pendingSpots.remove(doorId) }
            throw cancelled
        }
        settle {
            if (pendingSpots[doorId]?.first == edit) pendingSpots.remove(doorId)
            answer.onSuccess {
                if (connectionRepository.currentServerUrl.value == url) {
                    fromRelay = it
                    doorsFrom = url
                }
            }
        }
        return answer.map { }
    }

    override suspend fun setPairing(seconds: Int): Result<Unit> = command { url -> relayApi.setDoorPairing(url, seconds) }

    override suspend fun rename(doorId: String, name: String): Result<Unit> = command { url -> relayApi.renameDoor(url, doorId, name) }

    /** Asks the relay to do something, in turn with the saves, and shows the doors it answers with. Nothing is shown before it answers. */
    private suspend fun command(ask: suspend (url: String) -> Result<DoorsSnapshot>): Result<Unit> {
        val url = connectionRepository.currentServerUrl.value ?: return Result.failure(notConnected())
        val answer = writes.withLock { ask(url) }
        answer.onSuccess {
            settle {
                if (connectionRepository.currentServerUrl.value == url) {
                    fromRelay = it
                    doorsFrom = url
                }
            }
        }
        return answer.map { }
    }

    /** Reads the doors, waiting for the next change when [since] is the version already shown. Answers what the relay said. */
    private suspend fun fetch(url: String, rereadPlan: Boolean = false, since: Long? = null): Result<DoorsSnapshot> {
        val deviceId = identity.deviceId()
        val secret = identity.secret()
        // The plan barely ever changes: read once per server, and again on a refresh.
        planLock.withLock {
            if (rereadPlan || planFrom != url) {
                relayApi.getFloorPlan(url, deviceId, secret).onSuccess {
                    // A refresh isn't cancelled by a server switch: an answer from the old server is dropped.
                    if (connectionRepository.currentServerUrl.value == url) {
                        _floorPlan.value = it
                        planFrom = url
                    }
                }
            }
        }
        val waiting = if (since != null) waitSeconds else 0
        return relayApi.getDoors(url, deviceId, secret, since = since, waitSeconds = waiting).onSuccess { snapshot ->
            state.withLock {
                if (connectionRepository.currentServerUrl.value == url) {
                    fromRelay = snapshot
                    doorsFrom = url
                    publish()
                }
            }
        }
    }

    /**
     * Takes back or lands an edit under [state], then publishes. Not cancellable, so an edit whose
     * caller is cancelled can't stay laid over the doors after it.
     */
    private suspend fun settle(change: () -> Unit) = withContext(NonCancellable) {
        state.withLock {
            change()
            publish()
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
        /** Against a relay that can't hold a read open. */
        const val POLL_INTERVAL_MS = 15_000L

        /**
         * How long the relay may hold a read open with nothing changing. Under the 30 s an idle
         * connection lasts through most mobile carriers' NAT, and the relay's own cap.
         */
        const val WAIT_SECONDS = 25

        /** After a read that failed: soon, since a door may change meanwhile, but not in a tight loop. */
        const val RETRY_MS = 3_000L
    }
}
