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
 *
 * **A server that doesn't answer is asked again soon, not next poll.** The cache means the feed
 * has something to show meanwhile, but what it shows is only as fresh as the last answer, and
 * the usual reason for no answer — the server still booting, the VPN still coming up when the
 * app is opened after a long idle — clears in seconds. So a failed fetch is retried after
 * [RETRY_INTERVAL_MS], doubling up to [POLL_INTERVAL_MS] ([nextPollDelayMs]), and a window into
 * the past that couldn't be fetched keeps asking too, instead of settling for the cache.
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

    /** How many pages [fillShortFeed] has fetched for [loadedFor]; a newly opened window starts again at zero. */
    private var autoFilledPages = 0

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
            var failures = 0
            while (true) {
                val fetched = fetchHead(server, window, includeZones = polls % ZONES_EVERY_N_POLLS == 0)
                polls++
                failures = if (fetched) 0 else failures + 1
                send(Unit)
                fillShortFeed(server.identity, window)
                // A window into the past doesn't change under us; once it has been fetched, only
                // the live feed is worth re-asking for.
                if (window.before != null && fetched) return@collectLatest
                delay(nextPollDelayMs(failures))
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
     * reported, and a disconnect keeps it too, as the feed does.
     *
     * **It pages back until it has something to show** (2026-09-22): Front Yard's Recent Activity
     * said "No detections on this camera yet" on an evening the Moments tab listed ten. A car
     * parked in the driveway is re-detected every few minutes, each sighting a still, two-point
     * path that [mergeVehicleVisits] drops, so the newest page could be nothing but that car and
     * fold away to nothing. So each poll reads down, a page at a time, until [limit] moments
     * survive and the pages reach [lookbackSeconds] back — what the camera's timeline marks —
     * or the server runs out, or [RECENT_MAX_PAGES] have been read. The cursor is the oldest raw
     * detection, as in [appendOlderPage], and the pages are folded together, so a visit that
     * straddles a page boundary is still one moment.
     */
    override fun observeRecentMoments(cameraName: String, limit: Int, lookbackSeconds: Double): Flow<List<MomentEvent>> = channelFlow {
        server.collectLatest { server ->
            if (server == null) return@collectLatest
            fun cutoff(): Double = clock.now().toEpochMilliseconds() / 1000.0 - lookbackSeconds

            // The newest [limit] whatever their age, and everything younger than the lookback besides.
            fun List<MomentEvent>.recent(): List<MomentEvent> {
                val since = cutoff()
                return mergeVehicleVisits().filterIndexed { index, moment -> index < limit || moment.startEpochSeconds >= since }
            }
            cachedPage(server.identity, before = null, camera = cameraName, limit = RECENT_MAX_PAGES * PAGE_SIZE)
                .takeIf { it.isNotEmpty() }
                ?.let { send(it.recent()) }
            var failures = 0
            while (true) {
                loadZones(server.url, force = false)
                val pages = fetchRecentPages(server.url, cameraName, limit, cutoff())
                if (pages != null) {
                    val (placed, oldest) = pages
                    send(placed.recent())
                    cache(server.identity, cameraName, placed, from = oldest, to = null)
                }
                failures = if (pages != null) 0 else failures + 1
                delay(nextPollDelayMs(failures))
            }
        }
    }

    /**
     * The pages behind [observeRecentMoments]: [cameraName]'s detections from now, placed by the
     * zones, read down until [limit] moments survive folding and the oldest reaches [cutoff], or
     * the server has no more, or [RECENT_MAX_PAGES] have been read. Returns them with the oldest
     * raw start read (what the cache is squared against), or null if the server didn't answer the
     * first page. A later page that fails ends the walk on what was read so far.
     */
    private suspend fun fetchRecentPages(url: String, cameraName: String, limit: Int, cutoff: Double): Pair<List<MomentEvent>, Double?>? {
        var placed = emptyList<MomentEvent>()
        var oldest: Double? = null
        for (page in 0 until RECENT_MAX_PAGES) {
            val events = apiClient.getEvents(url, limit = PAGE_SIZE, beforeEpochSeconds = oldest, cameras = listOf(cameraName))
                .getOrElse { return if (page == 0) null else placed to oldest }
            val zones = stateLock.withLock { zonesByCamera }
            placed = placed + events.map { it.toDomain() }.inZones(zones)
            oldest = events.minOfOrNull { it.startTime } ?: oldest
            val enough = placed.mergeVehicleVisits().size >= limit && (oldest ?: cutoff) <= cutoff
            if (events.size < PAGE_SIZE || enough) break
        }
        return placed to oldest
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
     * Like the feed, it opens on the cache and files what it fetches there: the strip paints with
     * the cars the device last knew about while the first poll is still on its way to the server,
     * and keeps showing them for as long as the server can't be reached. The cache is only ever a
     * stand-in for an answer the server hasn't given yet — every poll that lands replaces it
     * wholesale, and the clock runs against the cached sightings just as it does against fetched
     * ones, so a car unseen for [com.meticulouscreations.homesafe.domain.model.StationaryObjects.AT_REST_SECONDS]
     * drops off whether the server is answering or not. The one thing the cache can't know is
     * that a sighting still in progress when it was written has since ended: that car reads as in
     * view until the first fetch corrects it, which is seconds away once connected.
     */
    override fun observeStationaryObjects(): Flow<List<StationaryObject>> = channelFlow {
        server.collectLatest { server ->
            if (server == null) {
                send(emptyList())
                return@collectLatest
            }
            cachedStationaryObjects(server.identity).takeIf { it.isNotEmpty() }?.let { send(it) }
            var failures = 0
            while (true) {
                loadZones(server.url, force = false)
                val now = clock.now().toEpochMilliseconds() / 1000.0
                val lookbackStart = now - STATIONARY_LOOKBACK_SECONDS
                val fetched = apiClient.getEvents(server.url, limit = PAGE_SIZE, afterEpochSeconds = lookbackStart)
                    .onSuccess { events ->
                        val zones = stateLock.withLock { zonesByCamera }
                        // Full pages stop where the server ran the limit out, not where the window ends.
                        val oldestFetched = if (events.size >= PAGE_SIZE) {
                            events.minOfOrNull { it.startTime } ?: lookbackStart
                        } else {
                            lookbackStart
                        }
                        // Zones first: a car out on the street is not parked in the yard, whatever it is doing.
                        val placed = events.map { it.toDomain() }.inZones(zones)
                        send(placed.stationaryObjects(now, oldestFetched))
                        // The same slice the live feed files — the newest detections across every camera — so the
                        // two share one cache rather than fighting over it. [oldestFetched], not the raw oldest
                        // event: a short or empty page still answered for the whole window back to
                        // [lookbackStart], and a car purged from that gap must be pruned from the cache too.
                        cache(server.identity, camera = null, placed, from = oldestFetched, to = null)
                    }
                    // Unreachable: the cache stands in, re-aged against the clock so a car stops being claimed on time.
                    .onFailure { send(cachedStationaryObjects(server.identity)) }
                    .isSuccess
                failures = if (fetched) 0 else failures + 1
                delay(nextPollDelayMs(failures))
            }
        }
    }

    /**
     * The in-view strip out of the cache: this server's cached detections back to the lookback
     * window, folded exactly as a fetched page would be. How far the cache reaches is its oldest
     * row — it is a few pages of the feed, not necessarily twelve hours — so a stay that starts
     * at its edge says where the car is without claiming since when, the same courtesy a full
     * page from the server gets.
     */
    private suspend fun cachedStationaryObjects(identity: String): List<StationaryObject> {
        val now = clock.now().toEpochMilliseconds() / 1000.0
        val lookbackStart = now - STATIONARY_LOOKBACK_SECONDS
        val page = cachedPage(identity, before = null, camera = null, limit = PAGE_SIZE)
        // A cache whose oldest row is older than the window covers the whole window; one that stops inside it stops there.
        val reach = maxOf(page.minOfOrNull { it.startEpochSeconds } ?: lookbackStart, lookbackStart)
        return page.filter { it.startEpochSeconds >= lookbackStart }.stationaryObjects(now, reach)
    }

    override suspend fun loadOlder() {
        if (!olderInFlight.tryLock()) return
        try {
            appendOlderPage()
        } finally {
            olderInFlight.unlock()
        }
    }

    /** Under [olderInFlight]. Appends the next page down to the tail. True if one was appended, from the server or the cache. */
    private suspend fun appendOlderPage(): Boolean {
        val server = currentServer() ?: return false
        // The cursor is the oldest raw detection, tail first: the tail is always older than the head.
        val (target, oldest) = stateLock.withLock { loadedFor to (tail.lastOrNull() ?: head.lastOrNull())?.startEpochSeconds }
        if (target == null || oldest == null || target.first != server.identity || !_paging.value.hasOlder) return false
        _paging.update { it.copy(loadingOlder = true) }
        try {
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
                    return placed != null
                }
                .onFailure { failure ->
                    // The server is unreachable, but the next page down may already be on the
                    // device — an older page the feed has shown before is worth more than a message.
                    if (appendCachedOlder(target, oldest)) return true
                    _error.value = failure.message ?: "Couldn't load older detections"
                }
            return false
        } finally {
            _paging.update { it.copy(loadingOlder = false) }
        }
    }

    /**
     * Pages down on the reader's behalf while the feed is too short to be worth looking at and the
     * server has more. Pages are raw detections and the zones decide afterwards, so a busy street
     * can fill the newest page — or several — with cars nobody asked to see, and the feed would
     * otherwise open on "Nothing to show yet" with the evening's moments one tap further down.
     * At most [AUTO_FILL_MAX_PAGES] per window, so a camera that only ever sees the street costs a
     * bounded walk rather than the server's whole history; past that the feed's own "Look further
     * back" is still there.
     */
    private suspend fun fillShortFeed(identity: String, window: Window) {
        while (true) {
            val more = stateLock.withLock {
                val short = loadedFor == identity to window &&
                    autoFilledPages < AUTO_FILL_MAX_PAGES &&
                    _moments.value.size < AUTO_FILL_MIN_MOMENTS &&
                    _paging.value.hasOlder
                if (short) autoFilledPages++
                short
            }
            if (!more || !olderInFlight.withLock { appendOlderPage() }) return
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
            autoFilledPages = 0
            head = cached
            tail = emptyList()
            _paging.value = MomentsPaging(beforeEpochSeconds = window.before)
            // A full page off the device may have more below it; a short one is all the device has,
            // and either way the first fetch that lands has the last word on it.
            publish(lastPageFull = cached.size >= PAGE_SIZE)
        }
    }

    /** Replaces the head with the server's newest page. False when the server didn't answer (the feed keeps what it has). */
    private suspend fun fetchHead(server: Server, window: Window, includeZones: Boolean): Boolean {
        loadZones(server.url, force = includeZones)
        return apiClient.getEvents(server.url, limit = PAGE_SIZE, beforeEpochSeconds = window.before, cameras = window.cameras)
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
            .isSuccess
    }

    /**
     * How long a poll loop waits before asking again: the poll interval after an answer; after
     * [consecutiveFailures] without one, [RETRY_INTERVAL_MS] doubling each time until it is the
     * poll interval again, so a server that is nearly up is caught within seconds and one that
     * is down for the evening isn't hammered.
     */
    private fun nextPollDelayMs(consecutiveFailures: Int): Long =
        if (consecutiveFailures == 0) {
            POLL_INTERVAL_MS
        } else {
            minOf(POLL_INTERVAL_MS, RETRY_INTERVAL_MS shl minOf(consecutiveFailures - 1, RETRY_MAX_DOUBLINGS))
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

        /** The first retry after a fetch the server didn't answer; see [nextPollDelayMs]. */
        const val RETRY_INTERVAL_MS = 5_000L

        /** Enough doublings of [RETRY_INTERVAL_MS] to reach [POLL_INTERVAL_MS]; also keeps the shift from overflowing. */
        const val RETRY_MAX_DOUBLINGS = 3
        const val ZONES_EVERY_N_POLLS = 4
        const val PAGE_SIZE = 100

        /** Fewer moments than this after the zones is a feed [fillShortFeed] pages down for: about two screens of cards. */
        const val AUTO_FILL_MIN_MOMENTS = 10

        /** The most pages [fillShortFeed] fetches for one window before leaving the rest to the reader. */
        const val AUTO_FILL_MAX_PAGES = 5

        /**
         * How many of a server's detections the device keeps: a few pages down from the top, which
         * is as far as anyone scrolls before switching to a day, and small enough that the table
         * stays a cache rather than a copy of Frigate's database.
         */
        const val CACHE_LIMIT = 500

        /**
         * The most pages [observeRecentMoments] reads per poll: enough to see past a parked car's
         * afternoon of re-detections, or most of a busy camera's day for the 24-hour timeline,
         * without walking the server's whole history every thirty seconds.
         */
        const val RECENT_MAX_PAGES = 5

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
