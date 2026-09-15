package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.DetectionBox
import com.meticulouscreations.homesafe.domain.model.DetectionZone
import com.meticulouscreations.homesafe.domain.model.MomentEvent
import com.meticulouscreations.homesafe.domain.model.MomentsPaging
import com.meticulouscreations.homesafe.domain.model.RecordingStream
import com.meticulouscreations.homesafe.domain.model.inZones
import com.meticulouscreations.homesafe.domain.model.mergeVehicleVisits
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.domain.repository.MomentsRepository
import com.meticulouscreations.homesafe.network.FrigateApiClient
import com.meticulouscreations.homesafe.network.FrigateEvent
import com.meticulouscreations.homesafe.network.frigateEventClipDownloadUrl
import com.meticulouscreations.homesafe.network.frigateEventClipUrl
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * No Room cache on purpose (same reasoning as recordings): Frigate mints and ends events
 * continuously, a stale cache would show detections that have since been purged, and the feed
 * re-polls on a short interval anyway. The in-memory list is the truth for the session.
 *
 * The feed is a window (see [MomentsPaging]) held as two raw lists: [head], the window's first
 * page, which the poll replaces wholesale, and [tail], the older pages [loadOlder] appends one at
 * a time, each contiguous with the last. Zones and vehicle-visit folding run over the two joined,
 * so a parked car whose sightings straddle a page boundary still folds into one card. Paging
 * cursors come from the raw lists, never the folded feed: the oldest *detection* fetched is
 * where the next page starts, whatever the feed made of it.
 *
 * The polling loop lives in [observeMoments] (not in `init`) so it only runs while something is
 * actually looking at the feed — a background tab shouldn't keep hitting the server.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class MomentsRepositoryImpl(
    private val apiClient: FrigateApiClient,
    private val connectionRepository: ConnectionRepository,
    appScope: CoroutineScope,
) : MomentsRepository {

    private val _moments = MutableStateFlow<List<MomentEvent>>(emptyList())
    private val _error = MutableStateFlow<String?>(null)
    private val _paging = MutableStateFlow(MomentsPaging())

    /** The window's top edge; null is live. See [MomentsPaging.beforeEpochSeconds]. */
    private val window = MutableStateFlow<Double?>(null)

    /**
     * The zones drawn on each camera, for [inZones]: read from `/api/config` on the first poll
     * and then every [ZONES_EVERY_N_POLLS], since the config is big and only changes when someone
     * edits it in the zone editor (which is a couple of minutes' staleness at worst).
     */
    private var zonesByCamera: Map<String, List<DetectionZone>> = emptyMap()
    private var zonesUrl: String? = null

    /** Guards [head], [tail], [loadedFor] and [zonesByCamera]: fetches run outside it, only their results are applied under it. */
    private val stateLock = Mutex()
    private var head: List<MomentEvent> = emptyList()
    private var tail: List<MomentEvent> = emptyList()

    /** Which (server, window) the lists belong to, so a page that lands after the window moved is dropped rather than mixed in. */
    private var loadedFor: Pair<String, Double?>? = null

    /** One page down at a time: a second [loadOlder] while one is in flight is simply ignored. */
    private val olderInFlight = Mutex()

    /** A StateFlow only emits on change, so a LAN/Tailscale route flip re-fetches on the new host and nothing else re-fetches. */
    private val activeUrl = connectionRepository.currentServerUrl

    // channelFlow, not flow: collectLatest runs its body in a child coroutine, and emitting from
    // there would violate the flow invariant at runtime. send() from a child is what channelFlow is for.
    private val poller: Flow<Unit> = channelFlow {
        combine(activeUrl, window) { url, before -> url to before }.collectLatest { (url, before) ->
            if (url == null) {
                stateLock.withLock { reset(loadedFor = null) }
                return@collectLatest
            }
            // Coming back to the same window (a tab switch, say) keeps the pages already loaded
            // and only refreshes the top; a new server or a moved window starts over.
            stateLock.withLock { if (loadedFor != url to before) reset(loadedFor = url to before) }
            var polls = 0
            while (true) {
                fetchHead(url, before, includeZones = polls % ZONES_EVERY_N_POLLS == 0)
                polls++
                send(Unit)
                // A window into the past doesn't change under us; only the live feed is worth re-asking for.
                if (before != null) return@collectLatest
                delay(POLL_INTERVAL_MS)
            }
        }
    }.shareIn(appScope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000))

    /**
     * Combining with [poller] is what subscribes it: the loop runs exactly while someone collects
     * this flow. `onStart { emit(Unit) }` guarantees a first emission even before (or without) a
     * fetch — e.g. while disconnected, so the UI sees an empty list rather than nothing.
     */
    override fun observeMoments(): Flow<List<MomentEvent>> =
        combine(_moments, poller.onStart { emit(Unit) }) { list, _ -> list }

    override fun observeError(): Flow<String?> = _error.asStateFlow()

    override fun observePaging(): Flow<MomentsPaging> = _paging.asStateFlow()

    override fun showBefore(epochSeconds: Double?) {
        window.value = epochSeconds
    }

    override suspend fun loadOlder() {
        if (!olderInFlight.tryLock()) return
        try {
            val url = activeUrl.value ?: return
            // The cursor is the oldest raw detection, tail first: the tail is always older than the head.
            val (target, oldest) = stateLock.withLock { loadedFor to (tail.lastOrNull() ?: head.lastOrNull())?.startEpochSeconds }
            if (oldest == null || target?.first != url || !_paging.value.hasOlder) return
            _paging.update { it.copy(loadingOlder = true) }
            apiClient.getEvents(url, limit = PAGE_SIZE, beforeEpochSeconds = oldest)
                .onSuccess { events ->
                    stateLock.withLock {
                        // Only if the window hasn't moved while the page was in flight.
                        if (loadedFor == target) {
                            tail = tail + events.map { it.toDomain() }
                            publish(lastPageFull = events.size >= PAGE_SIZE)
                        }
                    }
                    _error.value = null
                }
                .onFailure { _error.value = it.message ?: "Couldn't load older detections" }
        } finally {
            _paging.update { it.copy(loadingOlder = false) }
            olderInFlight.unlock()
        }
    }

    override suspend fun refresh() {
        connectionRepository.currentServerUrl.value?.let { fetchHead(it, window.value, includeZones = true) }
    }

    override suspend fun getClipStream(eventId: String): RecordingStream {
        val url = checkNotNull(connectionRepository.currentServerUrl.value) { "Not connected" }
        return RecordingStream(
            url = frigateEventClipUrl(url, eventId),
            headers = apiClient.sessionCookieHeader(url)?.let { mapOf("Cookie" to it) }.orEmpty(),
        )
    }

    override suspend fun getClipDownloadUrl(eventId: String): RecordingStream {
        val url = checkNotNull(connectionRepository.currentServerUrl.value) { "Not connected" }
        return RecordingStream(
            url = frigateEventClipDownloadUrl(url, eventId),
            headers = apiClient.sessionCookieHeader(url)?.let { mapOf("Cookie" to it) }.orEmpty(),
        )
    }

    private suspend fun fetchHead(url: String, before: Double?, includeZones: Boolean) {
        // A failed config read keeps the last zones (or none): the feed still shows, just unplaced.
        if (includeZones || url != zonesUrl) {
            apiClient.getServerConfig(url).onSuccess { config ->
                stateLock.withLock {
                    zonesByCamera = config.zonesByCamera()
                    zonesUrl = url
                }
            }
        }
        apiClient.getEvents(url, limit = PAGE_SIZE, beforeEpochSeconds = before)
            .onSuccess { events ->
                stateLock.withLock {
                    if (loadedFor == url to before) {
                        head = events.map { it.toDomain() }
                        // With older pages already below, the last page loaded is still the last one
                        // down; a fresh head short of a page is the whole of what the server has.
                        publish(lastPageFull = if (tail.isEmpty()) events.size >= PAGE_SIZE else _paging.value.hasOlder)
                    }
                }
                _error.value = null
            }
            .onFailure { _error.value = it.message ?: "Couldn't load detections" }
    }

    /** Under [stateLock]. Forgets the lists and points them at [loadedFor]. */
    private fun reset(loadedFor: Pair<String, Double?>?) {
        this.loadedFor = loadedFor
        head = emptyList()
        tail = emptyList()
        _moments.value = emptyList()
        _paging.value = MomentsPaging(beforeEpochSeconds = loadedFor?.second)
    }

    /**
     * Under [stateLock]. Joins the head and the tail (dropping a detection the tail repeats
     * from the head — a live head can grow into what the tail already had), then places and
     * folds the lot. Zones first, so a street car the zones reject never anchors a visit.
     */
    private fun publish(lastPageFull: Boolean) {
        val seen = HashSet<String>()
        val raw = (head + tail).filter { seen.add(it.id) }
        _moments.value = raw.inZones(zonesByCamera).mergeVehicleVisits()
        _paging.update { it.copy(hasOlder = raw.isNotEmpty() && lastPageFull) }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 30_000L
        const val ZONES_EVERY_N_POLLS = 4
        const val PAGE_SIZE = 100
    }
}

internal fun FrigateEvent.toDomain(): MomentEvent = MomentEvent(
    id = id,
    cameraName = camera,
    label = label,
    subLabel = subLabel,
    startEpochSeconds = startTime,
    endEpochSeconds = endTime,
    topScore = data?.topScore,
    hasClip = hasClip,
    hasSnapshot = hasSnapshot,
    zones = zones,
    pathPoints = data?.bottomCentrePath().orEmpty(),
    box = DetectionBox.fromFractions(data?.box),
    subLabelScore = data?.subLabelScore,
)
