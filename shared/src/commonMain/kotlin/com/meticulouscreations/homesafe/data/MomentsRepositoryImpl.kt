package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.DetectionBox
import com.meticulouscreations.homesafe.domain.model.DetectionZone
import com.meticulouscreations.homesafe.domain.model.MomentEvent
import com.meticulouscreations.homesafe.domain.model.MomentsPaging
import com.meticulouscreations.homesafe.domain.model.RecordingStream
import com.meticulouscreations.homesafe.domain.model.StationaryObject
import com.meticulouscreations.homesafe.domain.model.inZones
import com.meticulouscreations.homesafe.domain.model.mergeVehicleVisits
import com.meticulouscreations.homesafe.domain.model.stationaryObjects
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * The feed is a window (see [MomentsPaging]), optionally narrowed to one camera, held as two raw lists: [head], the window's first
 * page, which the poll replaces wholesale, and [tail], the older pages [loadOlder] appends one at
 * a time, each contiguous with the last. Zones and vehicle-visit folding run over the two joined,
 * so a parked car whose sightings straddle a page boundary still folds into one card. Paging
 * cursors come from the raw lists, never the folded feed: the oldest *detection* fetched is
 * where the next page starts, whatever the feed made of it.
 *
 * **The window opens on what the device already has.** Every page the server answers is written
 * to [MomentsDao] under the server's identity, and a window that has just opened — a launch, a
 * different day, another camera — is filled from that cache before the first fetch is even sent.
 * So the feed shows the moments it showed last time straight away, and still shows them when the
 * server can't be reached at all. The fetch that follows is the truth and replaces the head
 * wholesale; a page that comes back also prunes the rows in its own range that the server no
 * longer has, which is how a detection Frigate purged leaves the cache too.
 *
 * **What counts as "the same feed" is the server, not the address it answers at.** The window is
 * filed under [com.meticulouscreations.homesafe.domain.model.ActiveConnection.serverUrl] (the
 * cameras are cached the same way), so a LAN ↔ Tailscale route flip re-fetches on the new address
 * without starting the feed over, and a moment with no connection at all — the flip itself, the
 * app coming back before the session is re-established — leaves what's loaded on screen rather
 * than emptying it.
 *
 * The polling loop lives in [observeMoments] (not in `init`) so it only runs while something is
 * actually looking at the feed — a background tab shouldn't keep hitting the server.
 */
@OptIn(ExperimentalTime::class)
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class MomentsRepositoryImpl(
    private val apiClient: FrigateApiClient,
    private val connectionRepository: ConnectionRepository,
    private val momentsDao: MomentsDao,
    private val clock: Clock,
    appScope: CoroutineScope,
) : MomentsRepository {

    private val _moments = MutableStateFlow<List<MomentEvent>>(emptyList())
    private val _error = MutableStateFlow<String?>(null)
    private val _paging = MutableStateFlow(MomentsPaging())

    /** Which slice of the server's detections the feed is: where it opens and which camera it's on. */
    private data class Window(
        /** The top edge; null is live. See [MomentsPaging.beforeEpochSeconds]. */
        val before: Double? = null,
        /** Null is every camera. */
        val camera: String? = null,
    ) {
        val cameras: List<String>? get() = camera?.let(::listOf)
    }

    private val window = MutableStateFlow(Window())

    /** The server the feed reads: the [identity] its cache is filed under, and the [url] to ask right now. */
    private data class Server(val identity: String, val url: String)

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

    /** Which (server identity, window) the lists belong to, so a page that lands after the window moved is dropped rather than mixed in. */
    private var loadedFor: Pair<String, Window>? = null

    /** One page down at a time: a second [loadOlder] while one is in flight is simply ignored. */
    private val olderInFlight = Mutex()

    /**
     * A StateFlow only emits on change, so a route flip re-fetches on the new address and nothing
     * else re-fetches. Both halves matter: the identity decides what the feed and its cache are
     * for, the URL is merely where to ask.
     */
    private val server: Flow<Server?> = connectionRepository.activeConnection
        .map { connection -> connection?.let { Server(identity = it.serverUrl, url = it.activeUrl) } }
        .distinctUntilChanged()

    private fun currentServer(): Server? =
        connectionRepository.activeConnection.value?.let { Server(identity = it.serverUrl, url = it.activeUrl) }

    // channelFlow, not flow: collectLatest runs its body in a child coroutine, and emitting from
    // there would violate the flow invariant at runtime. send() from a child is what channelFlow is for.
    private val poller: Flow<Unit> = channelFlow {
        combine(server, window) { server, window -> server to window }.collectLatest { (server, window) ->
            // Disconnected: what's loaded stays on screen — it is what the cache would hand back
            // anyway, and an empty feed reads as "nothing has happened", which isn't what this is.
            if (server == null) return@collectLatest
            // Coming back to the same window (a tab switch, a route flip) keeps the pages already
            // loaded and only refreshes the top; a new server or a moved window opens on the cache.
            if (stateLock.withLock { loadedFor } != server.identity to window) openOnCache(server.identity, window)
            var polls = 0
            while (true) {
                fetchHead(server, window, includeZones = polls % ZONES_EVERY_N_POLLS == 0)
                polls++
                send(Unit)
                // A window into the past doesn't change under us; only the live feed is worth re-asking for.
                if (window.before != null) return@collectLatest
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
        window.update { it.copy(before = epochSeconds) }
    }

    override fun showCamera(cameraName: String?) {
        window.update { it.copy(camera = cameraName) }
    }

    /**
     * Its own poll rather than a slice of [observeMoments]: that feed is whatever window the
     * Moments tab last opened — another camera, an earlier day — and even live, a quiet camera's
     * newest moments needn't be among the newest hundred across all of them. Like the feed, it
     * opens on the cache so the strip isn't blank while the first poll runs, and files what it
     * fetches there. Zones come from the feed's cache, read here only when nothing has read them
     * for this server yet. A failed poll keeps what was shown; the feed is where fetch errors are
     * reported.
     */
    override fun observeRecentMoments(cameraName: String, limit: Int): Flow<List<MomentEvent>> = channelFlow {
        val rawEvents = maxOf(limit, RECENT_RAW_EVENTS)
        server.collectLatest { server ->
            if (server == null) {
                send(emptyList())
                return@collectLatest
            }
            cachedPage(server.identity, before = null, camera = cameraName, limit = rawEvents)
                .takeIf { it.isNotEmpty() }
                ?.let { send(it.mergeVehicleVisits().take(limit)) }
            while (true) {
                loadZones(server.url, force = false)
                // More than [limit] raw: zones drop some detections and folding merges others.
                apiClient.getEvents(server.url, limit = rawEvents, cameras = listOf(cameraName)).onSuccess { events ->
                    val zones = stateLock.withLock { zonesByCamera }
                    val placed = events.map { it.toDomain() }.inZones(zones)
                    send(placed.mergeVehicleVisits().take(limit))
                    cache(server.identity, cameraName, placed, from = events.minOfOrNull { it.startTime }, to = null)
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * Its own poll, like [observeRecentMoments], and for the same reason: the Moments feed's
     * window may be narrowed to one camera or opened at an earlier day, and this has to be every
     * camera, now. It reads back [STATIONARY_LOOKBACK_SECONDS] so a car parked this morning is
     * still anchored to the moment it arrived, and tells [stationaryObjects] how far back the page
     * actually reached — with a busy camera minting hundreds of car events a day, [PAGE_SIZE]
     * detections can run out well inside that window, and a card shouldn't claim an arrival time
     * it only inferred from where the page happened to stop.
     *
     * Deliberately not served from the cache: this answers "what is standing in the yard right
     * now", and a car the app saw an hour ago is no evidence that it is still there.
     */
    override fun observeStationaryObjects(): Flow<List<StationaryObject>> = channelFlow {
        server.collectLatest { server ->
            if (server == null) {
                send(emptyList())
                return@collectLatest
            }
            while (true) {
                loadZones(server.url, force = false)
                val now = clock.now().toEpochMilliseconds() / 1000.0
                val lookbackStart = now - STATIONARY_LOOKBACK_SECONDS
                apiClient.getEvents(server.url, limit = PAGE_SIZE, afterEpochSeconds = lookbackStart).onSuccess { events ->
                    val zones = stateLock.withLock { zonesByCamera }
                    // Full pages stop where the server ran the limit out, not where the window ends.
                    val oldestFetched = if (events.size >= PAGE_SIZE) {
                        events.minOfOrNull { it.startTime } ?: lookbackStart
                    } else {
                        lookbackStart
                    }
                    // Zones first: a car out on the street is not parked in the yard, whatever it is doing.
                    send(events.map { it.toDomain() }.inZones(zones).stationaryObjects(now, oldestFetched))
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    override suspend fun loadOlder() {
        if (!olderInFlight.tryLock()) return
        try {
            val server = currentServer() ?: return
            // The cursor is the oldest raw detection, tail first: the tail is always older than the head.
            val (target, oldest) = stateLock.withLock { loadedFor to (tail.lastOrNull() ?: head.lastOrNull())?.startEpochSeconds }
            if (target == null || oldest == null || target.first != server.identity || !_paging.value.hasOlder) return
            _paging.update { it.copy(loadingOlder = true) }
            apiClient.getEvents(server.url, limit = PAGE_SIZE, beforeEpochSeconds = oldest, cameras = target.second.cameras)
                .onSuccess { events ->
                    val page = events.map { it.toDomain() }
                    val placed: List<MomentEvent>? = stateLock.withLock {
                        // Only if the window hasn't moved while the page was in flight.
                        if (loadedFor != target) {
                            null
                        } else {
                            tail = tail + page
                            publish(lastPageFull = events.size >= PAGE_SIZE)
                            page.inZones(zonesByCamera)
                        }
                    }
                    _error.value = null
                    // The range this page covered is the cursor down to its oldest, and no higher:
                    // it says nothing about the pages already above it.
                    if (placed != null) {
                        cache(server.identity, target.second.camera, placed, from = events.minOfOrNull { it.startTime }, to = oldest)
                    }
                }
                .onFailure { failure ->
                    // The server is unreachable, but the next page down may already be on the
                    // device — an older page the feed has shown before is worth more than a message.
                    if (!appendCachedOlder(target, oldest)) {
                        _error.value = failure.message ?: "Couldn't load older detections"
                    }
                }
        } finally {
            _paging.update { it.copy(loadingOlder = false) }
            olderInFlight.unlock()
        }
    }

    override suspend fun refresh() {
        currentServer()?.let { fetchHead(it, window.value, includeZones = true) }
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

    /** Re-reads [url]'s zones when [force]d, or when what's cached belongs to another server (or nothing). */
    private suspend fun loadZones(url: String, force: Boolean) {
        // A failed config read keeps the last zones (or none): the feed still shows, just unplaced.
        if (force || url != stateLock.withLock { zonesUrl }) {
            apiClient.getServerConfig(url).onSuccess { config ->
                stateLock.withLock {
                    zonesByCamera = config.zonesByCamera()
                    zonesUrl = url
                }
            }
        }
    }

    /**
     * Points the lists at a window the feed has just moved to and fills its first page from the
     * cache, so the new window paints with what the device has instead of blanking until the
     * fetch lands. Nothing cached is simply an empty feed, exactly as before.
     */
    private suspend fun openOnCache(identity: String, window: Window) {
        // Read outside the lock: a query is IO, and nothing else may touch the lists meanwhile.
        val cached = cachedPage(identity, window.before, window.camera, PAGE_SIZE)
        stateLock.withLock {
            loadedFor = identity to window
            head = cached
            tail = emptyList()
            _paging.value = MomentsPaging(beforeEpochSeconds = window.before)
            // A full page off the device may have more below it; a short one is all the device has,
            // and either way the first fetch that lands has the last word on it.
            publish(lastPageFull = cached.size >= PAGE_SIZE)
        }
    }

    private suspend fun fetchHead(server: Server, window: Window, includeZones: Boolean) {
        loadZones(server.url, force = includeZones)
        apiClient.getEvents(server.url, limit = PAGE_SIZE, beforeEpochSeconds = window.before, cameras = window.cameras)
            .onSuccess { events ->
                val page = events.map { it.toDomain() }
                val placed: List<MomentEvent>? = stateLock.withLock {
                    if (loadedFor != server.identity to window) {
                        null
                    } else {
                        head = page
                        // With older pages already below, the last page loaded is still the last one
                        // down; a fresh head short of a page is the whole of what the server has.
                        publish(lastPageFull = if (tail.isEmpty()) events.size >= PAGE_SIZE else _paging.value.hasOlder)
                        page.inZones(zonesByCamera)
                    }
                }
                _error.value = null
                if (placed != null) {
                    cache(server.identity, window.camera, placed, from = events.minOfOrNull { it.startTime }, to = window.before)
                }
            }
            .onFailure { _error.value = it.message ?: "Couldn't load detections" }
    }

    /** [MomentsDao.page] as moments. A cache that can't be read is an empty one: it must never be why the feed fails. */
    private suspend fun cachedPage(identity: String, before: Double?, camera: String?, limit: Int): List<MomentEvent> =
        runCatching { momentsDao.page(identity, before, camera, limit) }
            .getOrDefault(emptyList())
            .map { it.toDomain() }

    /**
     * Files a page the server answered, and squares the cache with it over exactly the range that
     * page covered: [from] (its oldest detection) up to [to] (the instant it was asked to start
     * before, null for now). Anything still cached in that range the page didn't mention has been
     * purged by Frigate, or rejected by the zones, and goes with it. The bounds matter — a page
     * fetched below the feed says nothing about the pages above it. An empty page prunes nothing:
     * a server that momentarily answers with nothing shouldn't cost the device all it had.
     */
    private suspend fun cache(identity: String, camera: String?, placed: List<MomentEvent>, from: Double?, to: Double?) {
        if (from == null) return
        // Writing is a nicety; failing to write must never surface as a broken feed.
        runCatching {
            momentsDao.insertAll(placed.map { it.toEntity(identity) })
            momentsDao.deleteMissingInRange(
                serverUrl = identity,
                cameraName = camera,
                fromEpochSeconds = from,
                beforeEpochSeconds = to,
                keptIds = placed.map { it.id },
            )
            momentsDao.trimToNewest(identity, CACHE_LIMIT)
        }
    }

    /** The next page down out of the cache, when the server couldn't answer for it. True if it had one. */
    private suspend fun appendCachedOlder(target: Pair<String, Window>, oldest: Double): Boolean {
        val cached = cachedPage(target.first, before = oldest, camera = target.second.camera, limit = PAGE_SIZE)
        if (cached.isEmpty()) return false
        stateLock.withLock {
            if (loadedFor != target) return false
            tail = tail + cached
            publish(lastPageFull = cached.size >= PAGE_SIZE)
        }
        return true
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

        /**
         * How many of a server's detections the device keeps: a few pages down from the top, which
         * is as far as anyone scrolls before switching to a day, and small enough that the table
         * stays a cache rather than a copy of Frigate's database.
         */
        const val CACHE_LIMIT = 500

        /** How many detections a camera's recent strip reads to find its few moments. */
        const val RECENT_RAW_EVENTS = 25

        /**
         * How far back the in-view strip looks for the vehicles standing in the yard. Long enough
         * to catch the morning's arrival — so a card can say "since 8:12 AM" rather than only that
         * the car is there — without reaching back into yesterday's parking for a car that left.
         */
        const val STATIONARY_LOOKBACK_SECONDS = 12.0 * 60.0 * 60.0
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
