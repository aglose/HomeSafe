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
import com.meticulouscreations.homesafe.domain.repository.RecentMoments
import com.meticulouscreations.homesafe.network.FrigateApiClient
import com.meticulouscreations.homesafe.network.FrigateEvent
import com.meticulouscreations.homesafe.network.frigateEventClipDownloadUrl
import com.meticulouscreations.homesafe.network.frigateEventClipUrl
import com.meticulouscreations.homesafe.text.UiText
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.error_load_detections
import homesafe.shared.generated.resources.error_load_older_detections
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
 * page, which the poll re-reads, and [tail], the older pages [loadOlder] appends one at a time,
 * each contiguous with the last. The two must meet, and a re-read head moves up as detections
 * arrive, so the poll keeps whatever slid out of the head's bottom and, when the head has moved
 * further than a page, reads the stretch between (see [fetchHead]). Zones and vehicle-visit folding run over the two joined,
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
    private val _error = MutableStateFlow<UiText?>(null)
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

    /**
     * [head] came out of the cache, not from the server, since the window opened. The cache is
     * filled by several polls — the feed's and each camera's recent moments — so its newest
     * rows needn't be contiguous across every camera, and nothing below the first fetched page may
     * be kept on the strength of them.
     */
    private var headFromCache = false

    /** One walk down at a time ([readDown]): a second [loadOlder] while one is in flight is simply ignored. */
    private val olderInFlight = Mutex()

    /**
     * One newest-page fetch at a time, the poll's and [refresh]'s, so they land in the order they
     * were asked. Otherwise a poll already on its way when the reader pulls to refresh could land
     * after the refresh's answer and put its older page back on top.
     */
    private val headInFlight = Mutex()

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
        combine(_moments, namedByHand, poller.onStart { emit(Unit) }) { list, named, _ -> list.withNames(named) }

    override fun observeError(): Flow<UiText?> = _error.asStateFlow()

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
     * opens on the cache so the list isn't blank while the first poll runs, and files what it
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
    }.combine(namedByHand) { moments, named -> moments.withNames(named) }

    override suspend fun readRecentMoments(cameraName: String, limit: Int, lookbackSeconds: Double): RecentMoments? {
        val server = currentServer() ?: return null
        val cutoff = clock.now().toEpochMilliseconds() / 1000.0 - lookbackSeconds
        loadZones(server.url, force = false)
        val fetched = fetchRecentPages(server.url, cameraName, limit, cutoff)
        val placed = if (fetched != null) {
            cache(server.identity, cameraName, fetched.first, from = fetched.second, to = null)
            fetched.first
        } else {
            cachedPage(server.identity, before = null, camera = cameraName, limit = RECENT_MAX_PAGES * PAGE_SIZE)
        }
        val recent = placed.mergeVehicleVisits().filterIndexed { index, moment -> index < limit || moment.startEpochSeconds >= cutoff }
        return RecentMoments(recent.withNames(namedByHand.value), fresh = fetched != null)
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
            placed = placed + events.map { it.toDomain() }.inZones(zones, nowEpochSeconds())
            oldest = events.minOfOrNull { it.startTime } ?: oldest
            val enough = placed.mergeVehicleVisits().size >= limit && (oldest ?: cutoff) <= cutoff
            if (events.size < PAGE_SIZE || enough) break
        }
        return placed to oldest
    }

    /**
     * Pages [cameraName]'s detections down from [before] to [after], newest first, until a page
     * comes back short or [RANGE_MAX_PAGES] have been read. Placed by the zones and folded once
     * the whole interval is in, so a visit straddling a page boundary is one moment.
     */
    override fun observeMomentsBetween(cameraName: String, afterEpochSeconds: Double, beforeEpochSeconds: Double): Flow<List<MomentEvent>> = channelFlow {
        server.collectLatest { server ->
            if (server == null) return@collectLatest
            var failures = 0
            while (true) {
                loadZones(server.url, force = false)
                val placed = fetchRangePages(server.url, cameraName, afterEpochSeconds, beforeEpochSeconds)
                if (placed != null) send(placed.mergeVehicleVisits().sortedBy { it.startEpochSeconds })
                failures = if (placed != null) 0 else failures + 1
                delay(nextPollDelayMs(failures))
            }
        }
    }.combine(namedByHand) { moments, named -> moments.withNames(named) }

    /** The pages behind [observeMomentsBetween], or null if the server didn't answer the first one. */
    private suspend fun fetchRangePages(url: String, cameraName: String, after: Double, before: Double): List<MomentEvent>? {
        var placed = emptyList<MomentEvent>()
        var cursor = before
        for (page in 0 until RANGE_MAX_PAGES) {
            val events = apiClient.getEvents(url, limit = PAGE_SIZE, afterEpochSeconds = after, beforeEpochSeconds = cursor, cameras = listOf(cameraName))
                .getOrElse { return if (page == 0) null else placed }
            val zones = stateLock.withLock { zonesByCamera }
            placed = placed + events.map { it.toDomain() }.inZones(zones, nowEpochSeconds())
            cursor = events.minOfOrNull { it.startTime } ?: break
            if (events.size < PAGE_SIZE) break
        }
        return placed
    }

    /** Cars a person named in this run of the app, by event id (see [nameCar]). */
    private val namedByHand = MutableStateFlow<Map<String, String>>(emptyMap())

    override fun nameCar(eventId: String, subLabel: String) {
        namedByHand.update { it + (eventId to subLabel) }
    }

    /**
     * [namedByHand] over what the server said. Once the server says so too this changes nothing;
     * until then it is the difference between a card that reads "Car" after it was just named and
     * one that reads the name.
     */
    private fun List<MomentEvent>.withNames(named: Map<String, String>): List<MomentEvent> =
        if (named.isEmpty()) this else map { event -> named[event.id]?.let { event.copy(subLabel = it, subLabelScore = MANUAL_NAME_SCORE) } ?: event }

    /**
     * Reads down until the feed has a moment more to show, not merely a page more: on a camera
     * that faces a street a page is a hundred passing cars the zones reject, and a tap that
     * fetched one of those and stopped looked like a button that did nothing. The walk is
     * [readDown]'s, so it gives up [REACH_SECONDS] below where it started and says so by
     * leaving [MomentsPaging.hasOlder] up for the next ask.
     *
     * "A moment more" is one from below where the walk started, not a longer feed: the poll
     * re-reads the head while a walk is on its way down, and a moment arriving at the top is
     * not the older one that was asked for.
     */
    override suspend fun loadOlder() {
        if (!olderInFlight.tryLock()) return
        try {
            val (target, bottom) = stateLock.withLock { loadedFor to loadedBottom() }
            if (target == null || bottom == null) return
            readDown(target, floor = { bottom - REACH_SECONDS }, enough = { _moments.value.any { it.startEpochSeconds < bottom } })
        } finally {
            olderInFlight.unlock()
        }
    }

    /**
     * Under [olderInFlight]. Appends pages to the tail, one after another, until [enough] says the
     * feed has what the walk was for, the pages reach down to [floor], the server has no more, a
     * page can't be had, or the feed stops being [target]'s. [floor] and [enough] are asked under
     * [stateLock], before each page.
     *
     * [MomentsPaging.loadingOlder] is up from the first page asked for until the walk ends, not
     * page by page: between two pages the feed is still looking, and a spinner that blinked to
     * "Look further back" and back each time would say otherwise.
     */
    private suspend fun readDown(target: Pair<String, Window>, floor: () -> Double?, enough: () -> Boolean) {
        var askedFrom: Double? = null
        try {
            while (true) {
                val bottom = stateLock.withLock { if (hasFurtherToRead(target, floor, enough)) loadedBottom() else null } ?: return
                // A page that left the bottom where it was (the cursor is rounded to the millisecond,
                // so a page can be the rows the last one closed on) would be asked for again forever.
                // Only that: a bottom that moved up is a head re-read letting the older pages go
                // (see fetchHead), and the walk carries on down from there.
                if (bottom == askedFrom) return
                askedFrom = bottom
                _paging.update { it.copy(loadingOlder = true) }
                if (!appendOlderPage()) return
            }
        } finally {
            _paging.update { it.copy(loadingOlder = false) }
        }
    }

    /** Under [stateLock]. Whether a [readDown] for [target] would ask for a page: see there for what stops one. */
    private fun hasFurtherToRead(target: Pair<String, Window>, floor: () -> Double?, enough: () -> Boolean): Boolean {
        val bottom = loadedBottom() ?: return false
        val stop = floor() ?: return false
        return loadedFor == target && _paging.value.hasOlder && !enough() && bottom > stop
    }

    /** Under [olderInFlight]. Appends the next page down to the tail. True if one was appended, from the server or the cache. */
    private suspend fun appendOlderPage(): Boolean {
        val server = currentServer() ?: return false
        // The cursor is the oldest raw detection, tail first: the tail is always older than the head.
        val (target, oldest) = stateLock.withLock { loadedFor to loadedBottom() }
        if (target == null || oldest == null || target.first != server.identity || !_paging.value.hasOlder) return false
        apiClient.getEvents(server.url, limit = PAGE_SIZE, beforeEpochSeconds = oldest, cameras = target.second.cameras)
            .onSuccess { events ->
                val page = events.map { it.toDomain() }
                val placed: List<MomentEvent>? = stateLock.withLock {
                    // Only if the window hasn't moved while the page was in flight, and the page
                    // still starts where the loaded lists end: a head re-read meanwhile may have let
                    // the older pages go (see fetchHead), and this page belongs below them.
                    if (loadedFor != target || loadedBottom() != oldest) {
                        null
                    } else {
                        tail = tail + page
                        publish(lastPageFull = events.size >= PAGE_SIZE)
                        page.inZones(zonesByCamera, nowEpochSeconds())
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
                _error.value = failure.errorText(Res.string.error_load_older_detections)
            }
        return false
    }

    /**
     * Reads down on the reader's behalf while the feed is too short to be worth looking at and the
     * server has more. Pages are raw detections and the zones decide afterwards, so a busy street
     * can fill the newest page — or several — with cars nobody asked to see, and the feed would
     * otherwise open on "Nothing to show yet" with the evening's moments one tap further down.
     *
     * **It reads as far as a day, not as far as a count of pages** (2026-10-11): the walk used to
     * stop after five pages, and on a Sunday evening Front Yard's street filled those and the
     * head — six hundred detections — in two hours forty, while the last thing to happen in the
     * yard was three hours old. The feed opened on the moments the device had kept, the fetch
     * replaced them with the street, and the tab said "Nothing to show yet" over a server that
     * had the afternoon two pages further down. A page is as long as the traffic makes it, so it
     * measures nothing a reader cares about; [REACH_SECONDS] below the newest detection loaded
     * does, and still leaves a camera that only ever sees the street a bounded walk rather than
     * the server's whole history. Past that the feed's own "Look further back" is still there.
     *
     * Nothing is counted between calls: how far the walk may go is read off the lists, so a feed
     * that starts over (a gap [fetchHead] couldn't close) is filled again like one just opened,
     * and one already read a day down costs nothing at each poll.
     */
    private suspend fun fillShortFeed(identity: String, window: Window) {
        val target = identity to window
        val floor = { loadedTop()?.minus(REACH_SECONDS) }
        val enough = { _moments.value.size >= AUTO_FILL_MIN_MOMENTS }
        // Asked before waiting on a walk already in flight: a feed with nothing to fill has no business behind it.
        if (!stateLock.withLock { hasFurtherToRead(target, floor, enough) }) return
        olderInFlight.withLock { readDown(target, floor, enough) }
    }

    override suspend fun refresh() {
        val server = currentServer() ?: return
        val window = window.value
        if (fetchHead(server, window, includeZones = true)) fillShortFeed(server.identity, window)
    }

    override suspend fun getClipStream(eventId: String): RecordingStream {
        val url = connectionRepository.currentServerUrl.value ?: throw notConnected()
        return RecordingStream(
            url = frigateEventClipUrl(url, eventId),
            headers = apiClient.sessionCookieHeader(url)?.let { mapOf("Cookie" to it) }.orEmpty(),
        )
    }

    override suspend fun getClipDownloadUrl(eventId: String): RecordingStream {
        val url = connectionRepository.currentServerUrl.value ?: throw notConnected()
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
            headFromCache = true
            tail = emptyList()
            _paging.value = MomentsPaging(beforeEpochSeconds = window.before)
            // A full page off the device may have more below it; a short one is all the device has,
            // and either way the first fetch that lands has the last word on it.
            publish(lastPageFull = cached.size >= PAGE_SIZE)
        }
    }

    /**
     * Re-reads the window's newest page, and joins it to what is loaded below. False when the
     * server didn't answer (the feed keeps what it has).
     *
     * **The head must still meet the tail** (2026-09-26): the head is the newest page, so every
     * detection that arrives pushes one off its bottom — and the tail, loaded below the head as it
     * was, doesn't have it. Replacing the head wholesale lost every detection that slid out: on a
     * busy street the newest hundred span minutes, and a feed left open at noon and looked at again
     * at 1:15 went straight from 1:05 PM to last night, the whole morning gone. So what was loaded
     * below the fresh page is kept rather than thrown away, and when the fresh page doesn't reach
     * down to it (a full page, all of it newer than anything loaded), the stretch between is read a
     * page at a time, up to [BRIDGE_MAX_PAGES]. If even that doesn't close the gap, the older pages
     * are let go instead, and paging down starts again from the bottom of what was read: the feed
     * is never allowed to show two stretches as if nothing happened between them.
     *
     * Whatever was loaded in the fresh pages' range but isn't in them is gone from the server (or
     * the zones), and goes from the feed too.
     */
    private suspend fun fetchHead(server: Server, window: Window, includeZones: Boolean): Boolean = headInFlight.withLock {
        loadZones(server.url, force = includeZones)
        val target = server.identity to window
        val events = apiClient.getEvents(server.url, limit = PAGE_SIZE, beforeEpochSeconds = window.before, cameras = window.cameras)
            .getOrElse {
                _error.value = it.errorText(Res.string.error_load_detections)
                return@withLock false
            }
        _error.value = null
        var fresh = events.map { it.toDomain() }
        val lastPageFull = events.size >= PAGE_SIZE
        // Where the fresh page has to reach down to: the newest detection loaded below it, if any.
        val loadedNewest = stateLock.withLock {
            if (loadedFor != target || headFromCache) null else (head + tail).maxOfOrNull { it.startEpochSeconds }
        }
        var reachesLoaded = true
        var bridgeFailure: Throwable? = null
        if (lastPageFull && loadedNewest != null) {
            val bridge = bridgeDown(server.url, window, from = fresh.minOf { it.startEpochSeconds }, to = loadedNewest)
            fresh = fresh + bridge.events
            reachesLoaded = bridge.closed
            bridgeFailure = bridge.failure
        }
        val freshOldest = fresh.minOfOrNull { it.startEpochSeconds }
        val placed: List<MomentEvent>? = stateLock.withLock {
            if (loadedFor != target) {
                null
            } else {
                val below = if (headFromCache) emptyList() else head + tail
                head = fresh
                headFromCache = false
                tail = when {
                    // A short head page is the bottom of what the server has: nothing below it is real.
                    !lastPageFull || freshOldest == null -> emptyList()

                    // Couldn't reach what was loaded: start the pages below over from here.
                    !reachesLoaded -> emptyList()

                    // Anything starting at the fresh pages' oldest instant stays too; publish() drops the repeats.
                    else -> below.filter { it.startEpochSeconds <= freshOldest }
                }
                // With older pages still below, the last page loaded is still the last one down.
                publish(lastPageFull = if (tail.isEmpty()) lastPageFull else _paging.value.hasOlder)
                // What the cache is squared against includes the tail's rows at that same instant: the
                // prune below runs from it inclusive, and they are in the feed.
                val freshIds = fresh.mapTo(HashSet()) { it.id }
                val boundary = tail.filter { it.startEpochSeconds == freshOldest && it.id !in freshIds }
                (fresh + boundary).inZones(zonesByCamera, nowEpochSeconds())
            }
        }
        if (placed != null) {
            cache(server.identity, window.camera, placed, from = freshOldest, to = window.before)
        }
        // What was read is on screen; a gap that couldn't be read is still a fetch that failed, and asks again soon.
        if (bridgeFailure != null) {
            _error.value = bridgeFailure.errorText(Res.string.error_load_detections)
            return@withLock false
        }
        true
    }

    /** What [bridgeDown] read, newest first; whether it reached down to what was loaded; and why it stopped, if the server stopped answering. */
    private class Bridge(val events: List<MomentEvent>, val closed: Boolean, val failure: Throwable? = null)

    /**
     * The detections between a fresh head page's oldest, [from], and the newest already loaded,
     * [to]: read down a page at a time until a page comes back short (nothing more between) or
     * reaches [to], or [BRIDGE_MAX_PAGES] have been read, or the server stops answering.
     */
    private suspend fun bridgeDown(url: String, window: Window, from: Double, to: Double): Bridge {
        var bridged = emptyList<MomentEvent>()
        var cursor = from
        repeat(BRIDGE_MAX_PAGES) {
            if (cursor <= to) return Bridge(bridged, closed = true)
            val events = apiClient.getEvents(url, limit = PAGE_SIZE, afterEpochSeconds = to, beforeEpochSeconds = cursor, cameras = window.cameras)
                .getOrElse { return Bridge(bridged, closed = false, failure = it) }
            bridged = bridged + events.map { it.toDomain() }
            // Short: nothing else lies between. Bounded below by [to], so it says nothing about the bottom of the server.
            if (events.size < PAGE_SIZE) return Bridge(bridged, closed = true)
            cursor = events.minOf { it.startTime }
        }
        return Bridge(bridged, closed = cursor <= to)
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
            if (loadedFor != target || loadedBottom() != oldest) return false
            tail = tail + cached
            publish(lastPageFull = cached.size >= PAGE_SIZE)
        }
        return true
    }

    /** The clock, as Frigate counts time: what "parked" is judged against. */
    private fun nowEpochSeconds(): Double = clock.now().toEpochMilliseconds() / 1000.0

    /** Under [stateLock]. Where the loaded lists end: the oldest raw detection, which is where the next page down starts. */
    private fun loadedBottom(): Double? = (tail.lastOrNull() ?: head.lastOrNull())?.startEpochSeconds

    /** Under [stateLock]. Where the loaded lists start: the newest raw detection. */
    private fun loadedTop(): Double? = (head.firstOrNull() ?: tail.firstOrNull())?.startEpochSeconds

    /**
     * Under [stateLock]. Joins the head and the tail (dropping a detection the tail repeats
     * from the head — a live head can grow into what the tail already had), then places and
     * folds the lot. Zones first, so a street car the zones reject never anchors a visit.
     */
    private fun publish(lastPageFull: Boolean) {
        val seen = HashSet<String>()
        val raw = (head + tail).filter { seen.add(it.id) }
        _moments.value = raw.inZones(zonesByCamera, nowEpochSeconds()).mergeVehicleVisits()
        _paging.update { it.copy(hasOlder = raw.isNotEmpty() && lastPageFull) }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 30_000L

        /** What the server records for a name a person gave (see ClassifierRepositoryImpl), so a hand-named card matches it. */
        const val MANUAL_NAME_SCORE = 1.0

        /** The first retry after a fetch the server didn't answer; see [nextPollDelayMs]. */
        const val RETRY_INTERVAL_MS = 5_000L

        /** Enough doublings of [RETRY_INTERVAL_MS] to reach [POLL_INTERVAL_MS]; also keeps the shift from overflowing. */
        const val RETRY_MAX_DOUBLINGS = 3
        const val ZONES_EVERY_N_POLLS = 4
        const val PAGE_SIZE = 100

        /**
         * The most pages [fetchHead] reads to join a fresh head to the pages already loaded below
         * it. Past that the gap is a busy afternoon's worth of detections, and paging down from the
         * new head is cheaper than closing it.
         */
        const val BRIDGE_MAX_PAGES = 5

        /** Fewer moments than this after the zones is a feed [fillShortFeed] pages down for: about two screens of cards. */
        const val AUTO_FILL_MIN_MOMENTS = 10

        /**
         * How far down a walk ([readDown]) reads without finding what it is after before leaving
         * the rest to the reader: a day below the newest detection loaded for [fillShortFeed], a
         * day below where it started for [loadOlder]. "Nothing in a day" is something a reader
         * can make sense of; a busy street's day is some fifty pages, so it is also an end.
         */
        const val REACH_SECONDS = 24.0 * 60.0 * 60.0

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
         * The most pages [observeMomentsBetween] reads per poll. The clip editor asks for half an
         * hour, which even a busy camera fills with far fewer than a thousand detections.
         */
        const val RANGE_MAX_PAGES = 10
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
    pathEpochSeconds = data?.pathEpochSeconds().orEmpty(),
    box = DetectionBox.fromFractions(data?.box),
    subLabelScore = data?.subLabelScore,
)
