package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.platform.AlertNotification
import com.meticulouscreations.homesafe.domain.platform.AlertNotifier

import com.meticulouscreations.homesafe.domain.model.AlertSettings
import com.meticulouscreations.homesafe.domain.model.DetectionZone
import com.meticulouscreations.homesafe.domain.model.FaceLibrary
import com.meticulouscreations.homesafe.domain.model.MomentCategory
import com.meticulouscreations.homesafe.domain.model.MomentEvent
import com.meticulouscreations.homesafe.domain.model.categoryForLabel
import com.meticulouscreations.homesafe.domain.model.inZones
import com.meticulouscreations.homesafe.domain.model.present
import com.meticulouscreations.homesafe.domain.model.subLabelDisplayName
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.domain.repository.PresenceRepository
import com.meticulouscreations.homesafe.domain.repository.SettingsRepository
import com.meticulouscreations.homesafe.network.FrigateApiClient
import com.meticulouscreations.homesafe.network.FrigateEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Turns new Frigate detections into system notifications. Runs for the life of the app process,
 * but only *does* anything while the user has push notifications on and the app is signed in:
 * then it asks Frigate every [pollIntervalMs] for detections that started since its last look
 * and hands each one the user wants (per [AlertSettings]) to the [AlertNotifier].
 *
 * Why polling from the app: Frigate's own notifications are Web Push to a browser service
 * worker, which a native app can't subscribe to, and this app has no cloud relay of its own.
 * So notifications arrive while HomeSafe is running — foreground or recently backgrounded —
 * and stop when the OS ends the process. The Settings tab says as much.
 *
 * The baseline is "now" when polling starts, so switching the feature on never replays the
 * day's history as a burst of notifications.
 *
 * Away mode (see docs/away-mode.md): while [PresenceRepository] says everyone is away, any person
 * on any camera is posted — [AlertNotification.urgent], on the loud channel — whatever the zone
 * rules say. Presence is collected for the life of the poll so that flag stays fresh.
 */
class DetectionAlertService(
    private val apiClient: FrigateApiClient,
    private val connectionRepository: ConnectionRepository,
    settingsRepository: SettingsRepository,
    private val presenceRepository: PresenceRepository,
    private val notifier: AlertNotifier,
    private val scope: CoroutineScope,
    /** Epoch seconds; injectable so tests control the baseline. */
    private val clock: () -> Double,
    private val pollIntervalMs: Long,
) {
    private val settings: StateFlow<AlertSettings> =
        settingsRepository.observeSettings().stateIn(scope, SharingStarted.Eagerly, AlertSettings.DEFAULT)

    private var job: Job? = null

    /** Idempotent; the app calls it once at startup. Does nothing on platforms without notifications. */
    fun start() {
        if (job?.isActive == true || !notifier.isSupported) return
        job = scope.launch {
            combine(settings, connectionRepository.currentServerUrl) { prefs, url -> url.takeIf { prefs.pushNotificationsEnabled } }
                // Zone rules must not restart the loop (and reset its baseline); only on/off and the server do.
                .distinctUntilChanged()
                .collectLatest { url -> if (url != null) poll(url) }
        }
    }

    /**
     * Ends polling. On Android the app graph is built per Activity and its scope is never
     * cancelled, so an Activity that's being recreated (rotation) must stop its poller or the
     * replacement's would run alongside it.
     */
    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun poll(url: String) = coroutineScope {
        // Keeps the presence repository polling the relay while we poll Frigate (it only does so while collected).
        launch { presenceRepository.presence.collect {} }
        var after = clock()
        val seen = LinkedHashSet<String>()
        // People Frigate hasn't put a name to yet, held back while face recognition may still
        // catch up (see RECOGNITION_GRACE_SECONDS). Only used while "only strangers" is on.
        val pending = LinkedHashMap<String, FrigateEvent>()
        // Each camera's zones, for MomentEvent.inZones; re-read now and then so an edit in the
        // zone editor takes effect without a restart. A failed read keeps the last zones.
        var zones: Map<String, List<DetectionZone>> = emptyMap()
        var zonesReadAt = Double.NEGATIVE_INFINITY
        while (true) {
            if (clock() - zonesReadAt >= ZONES_REFRESH_SECONDS) {
                apiClient.getServerConfig(url).onSuccess { zones = it.zonesByCamera() }
                zonesReadAt = clock()
            }
            // Reach back far enough to re-read anything still pending, so its sub-label can arrive.
            val from = minOf(after, pending.values.minOfOrNull { it.startTime - OVERLAP_SECONDS } ?: after)
            apiClient.getEvents(url, limit = PAGE_SIZE, afterEpochSeconds = from).onSuccess { events ->
                val now = clock()
                events.filter { it.id !in seen }.sortedBy { it.startTime }.forEach { event ->
                    if (shouldHold(event, now)) {
                        pending[event.id] = event
                    } else {
                        pending.remove(event.id)
                        seen += event.id
                        decide(url, event, zones)
                    }
                }
                // A held detection that fell out of the page (or ended without a name) is judged as it last stood.
                val returned = events.mapTo(HashSet()) { it.id }
                pending.values.filter { it.id !in returned && !shouldHold(it, now) }.forEach { event ->
                    pending.remove(event.id)
                    seen += event.id
                    decide(url, event, zones)
                }
                while (seen.size > MAX_REMEMBERED) seen.remove(seen.first())
                // Overlap by a second so an event whose start rounds onto the boundary isn't lost;
                // `seen` keeps the overlap from double-posting.
                val newest = events.maxOfOrNull { it.startTime }
                if (newest != null) after = maxOf(after, newest - OVERLAP_SECONDS)
            }
            delay(pollIntervalMs)
        }
    }

    /**
     * Frigate names a face a few seconds into a person's visit, and a stranger-only rule can't be
     * applied until then. So a still-anonymous, still-present person is held for up to
     * [RECOGNITION_GRACE_SECONDS] after they appeared before being judged a stranger.
     */
    private fun shouldHold(event: FrigateEvent, now: Double): Boolean =
        !everyoneAway &&
            settings.value.quietFamiliarPeople &&
            categoryForLabel(event.label) == MomentCategory.PEOPLE &&
            !event.isRecognized &&
            event.endTime == null &&
            now - event.startTime < RECOGNITION_GRACE_SECONDS

    /**
     * Whether and how loudly to post: with nobody home every person notifies on the loud channel,
     * zone rules and the stranger rule notwithstanding; otherwise the user's rules decide, once
     * [inZones] has worked out where the object went and whether those zones wanted it at all —
     * a car crossing a birds-only street zone is dropped here, not judged as "anywhere else".
     */
    private suspend fun decide(url: String, event: FrigateEvent, zones: Map<String, List<DetectionZone>>) {
        val category = categoryForLabel(event.label)
        val escalated = everyoneAway && category == MomentCategory.PEOPLE
        val moment = event.toDomain()
        val placed = moment.inZones(zones[event.camera].orEmpty())
        when {
            escalated -> notify(url, placed ?: moment, urgent = true)
            placed == null -> Unit
            settings.value.notifies(event.camera, placed.zones, category, recognized = event.isRecognized) -> notify(url, placed, urgent = false)
        }
    }

    private val everyoneAway: Boolean get() = presenceRepository.presence.value.everyoneAway

    /** A named person. Frigate only writes a sub-label for a face above its recognition threshold, but guard its "unknown" marker anyway. */
    private val FrigateEvent.isRecognized: Boolean
        get() = !subLabel.isNullOrBlank() && !subLabel.equals(FaceLibrary.UNKNOWN_GUESS, ignoreCase = true)

    @OptIn(ExperimentalTime::class)
    private suspend fun notify(url: String, moment: MomentEvent, urgent: Boolean) {
        val timeZone = TimeZone.currentSystemDefault()
        val today = Instant.fromEpochSeconds(clock().toLong()).toLocalDateTime(timeZone).date
        val presentation = moment.present(today, timeZone)
        val where = buildString {
            append(moment.cameraDisplayName)
            append(" · ")
            append(presentation.timeLabel)
            moment.subLabel?.takeIf { it.isNotBlank() }?.let { append(" · ").append(subLabelDisplayName(it)) }
        }
        notifier.notify(
            AlertNotification(
                id = moment.id,
                title = if (urgent) "Away: ${presentation.title}" else presentation.title,
                body = where,
                thumbnail = apiClient.getEventThumbnail(url, moment.id).getOrNull(),
                urgent = urgent,
            ),
        )
    }

    private companion object {
        const val PAGE_SIZE = 50
        const val ZONES_REFRESH_SECONDS = 120.0
        const val MAX_REMEMBERED = 200
        const val OVERLAP_SECONDS = 1.0
        const val RECOGNITION_GRACE_SECONDS = 20.0
    }
}
