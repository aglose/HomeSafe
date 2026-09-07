package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.DetectionZone
import com.meticulouscreations.homesafe.domain.model.MomentEvent
import com.meticulouscreations.homesafe.domain.model.RecordingStream
import com.meticulouscreations.homesafe.domain.model.inZones
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

/**
 * No Room cache on purpose (same reasoning as recordings): Frigate mints and ends events
 * continuously, a stale cache would show detections that have since been purged, and the feed
 * re-polls on a short interval anyway. The in-memory list is the truth for the session.
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

    /**
     * The zones drawn on each camera, for [inZones]: read from `/api/config` on the first poll
     * and then every [ZONES_EVERY_N_POLLS], since the config is big and only changes when someone
     * edits it in the zone editor (which is a couple of minutes' staleness at worst).
     */
    private var zonesByCamera: Map<String, List<DetectionZone>> = emptyMap()
    private var zonesUrl: String? = null

    /** A StateFlow only emits on change, so a LAN/Tailscale route flip re-fetches on the new host and nothing else re-fetches. */
    private val activeUrl = connectionRepository.currentServerUrl

    // channelFlow, not flow: collectLatest runs its body in a child coroutine, and emitting from
    // there would violate the flow invariant at runtime. send() from a child is what channelFlow is for.
    private val poller: Flow<Unit> = channelFlow {
        activeUrl.collectLatest { url ->
            if (url == null) {
                _moments.value = emptyList()
                return@collectLatest
            }
            var polls = 0
            while (true) {
                fetch(url, includeZones = polls % ZONES_EVERY_N_POLLS == 0)
                polls++
                send(Unit)
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

    override suspend fun refresh() {
        connectionRepository.currentServerUrl.value?.let { fetch(it, includeZones = true) }
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

    private suspend fun fetch(url: String, includeZones: Boolean) {
        // A failed config read keeps the last zones (or none): the feed still shows, just unplaced.
        if (includeZones || url != zonesUrl) {
            apiClient.getServerConfig(url).onSuccess { config ->
                zonesByCamera = config.zonesByCamera()
                zonesUrl = url
            }
        }
        apiClient.getEvents(url, limit = PAGE_SIZE)
            .onSuccess { events ->
                _moments.value = events.map { it.toDomain() }.inZones(zonesByCamera)
                _error.value = null
            }
            .onFailure { _error.value = it.message ?: "Couldn't load detections" }
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
)
