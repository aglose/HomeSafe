package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.AlertSettings
import com.meticulouscreations.homesafe.domain.model.DetectionZone
import com.meticulouscreations.homesafe.domain.model.FaceLibrary
import com.meticulouscreations.homesafe.domain.model.MomentCategory
import com.meticulouscreations.homesafe.domain.model.MomentEvent
import com.meticulouscreations.homesafe.domain.model.VehicleVisits
import com.meticulouscreations.homesafe.domain.model.atSameSpotAs
import com.meticulouscreations.homesafe.domain.model.categoryForLabel
import com.meticulouscreations.homesafe.domain.model.foldedInto
import com.meticulouscreations.homesafe.domain.model.inZones
import com.meticulouscreations.homesafe.domain.model.isStill
import com.meticulouscreations.homesafe.domain.model.present
import com.meticulouscreations.homesafe.domain.model.subLabelDisplayName
import com.meticulouscreations.homesafe.domain.platform.AlertNotification
import com.meticulouscreations.homesafe.domain.platform.AlertNotifier
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.domain.repository.PresenceRepository
import com.meticulouscreations.homesafe.domain.repository.SettingsRepository
import com.meticulouscreations.homesafe.navigation.MomentDeepLink
import com.meticulouscreations.homesafe.network.FrigateApiClient
import com.meticulouscreations.homesafe.network.FrigateEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
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
 * worker, which a native app can't subscribe to. So notifications arrive while HomeSafe is
 * running — foreground or recently backgrounded — and stop when the OS ends the process. The
 * Settings tab says as much. A phone the HomeSafe relay (relay/relay.py) pushes to gets its
 * alerts from there instead, and this poller stays off (see [start]).
 *
 * The baseline is "now" when polling starts, so switching the feature on never replays the
 * day's history as a burst of notifications.
 *
 * Away mode (see docs/away-mode.md): while [PresenceRepository] says everyone is away, any person
 * on any camera is posted — [AlertNotification.urgent], on the loud channel — whatever the zone
 * rules say. Presence is collected for the life of the poll so that flag stays fresh. Quiet hours
 * and "only when everyone's away" ([AlertSettings.ordinaryAlertsSilenced]) hold back everything
 * else, but never those.
 *
 * A car is only reported once it has moved, and once per visit. Frigate re-detects the same
 * vehicle over and over — a parked car is picked up anew every few minutes as the detector's box
 * on it flickers, a passer-by steals its tracker, and a car manoeuvring is lost and re-acquired
 * several times (see `VehicleVisits`). So a vehicle whose path shows no travel is held for a
 * short grace while it is in progress (a car pulling in moves within seconds) and then dropped
 * without a word, and a moving vehicle seen again at a spot one was just posted from is remembered
 * as another sighting of that visit instead of being posted again. The push relay applies the
 * same rule (`motion_verdict` in relay/relay.py).
 *
 * Every notification is posted the moment its detection is judged, text only, and then
 * updated in place (same id) as its media arrives: the thumbnail first, then Frigate's animated
 * preview of the clip (see [addAlertMedia] for when that is asked for). Tapping any stage opens
 * the detection full screen (see [MomentDeepLink]).
 */
class DetectionAlertService(
    private val apiClient: FrigateApiClient,
    private val connectionRepository: ConnectionRepository,
    settingsRepository: SettingsRepository,
    private val presenceRepository: PresenceRepository,
    private val notifier: AlertNotifier,
    /** True while the relay has this phone's push token, so it hears every alert as a push (see [start]); `DeviceRegistrar.pushRegistered`. */
    private val pushRegistered: Flow<Boolean>,
    private val scope: CoroutineScope,
    /** Epoch seconds; injectable so tests control the baseline. */
    private val clock: () -> Double,
    private val pollIntervalMs: Long,
    /** Where quiet hours are read; asked on every detection so a phone that travels keeps local time. */
    private val timeZone: () -> TimeZone = { TimeZone.currentSystemDefault() },
    /** How long after a detection starts its animated preview is complete (Frigate's 20 s, plus frames landing late). */
    private val previewWindowSeconds: Double = PREVIEW_WINDOW_SECONDS,
    /** Waits between asks for a preview that isn't there yet; its length is how many retries there are. */
    private val previewRetryDelaysMs: List<Long> = PREVIEW_RETRY_DELAYS_MS,
) {
    private val settings: StateFlow<AlertSettings> =
        settingsRepository.observeSettings().stateIn(scope, SharingStarted.Eagerly, AlertSettings.DEFAULT)

    private var job: Job? = null

    /**
     * Idempotent; the app calls it once at startup. Does nothing on platforms without
     * notifications, and pauses while the relay has this phone's push token: that phone hears
     * every alert (and every Away escalation) as a push, folded into one notification per visit,
     * and this poller's notifications, tagged by Frigate event rather than by visit, would come on
     * top of each one. Until the relay has taken the token, or on a phone with no push, it polls.
     */
    fun start() {
        if (job?.isActive == true || !notifier.isSupported) return
        job = scope.launch {
            combine(settings, connectionRepository.currentServerUrl, pushRegistered) { prefs, url, pushed ->
                url.takeIf { prefs.pushNotificationsEnabled && !pushed }
            }
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
        // Vehicles already posted, newest last, so a car being lost and re-acquired as it
        // manoeuvres stays quiet after its first look.
        val recentVehicles = ArrayDeque<RecentVehicle>()
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
                        decide(url, event, zones, recentVehicles)
                    }
                }
                // A held detection that fell out of the page (or ended without a name) is judged as it last stood.
                val returned = events.mapTo(HashSet()) { it.id }
                pending.values.filter { it.id !in returned && !shouldHold(it, now) }.forEach { event ->
                    pending.remove(event.id)
                    seen += event.id
                    decide(url, event, zones, recentVehicles)
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
     * Two reasons to look again later rather than judge now. Frigate names a face a few seconds
     * into a person's visit, and a stranger-only rule can't be applied until then, so a
     * still-anonymous, still-present person is held for up to [RECOGNITION_GRACE_SECONDS] after
     * they appeared before being judged a stranger. And a vehicle's path is a point or two when it
     * first appears, whether it is pulling in or has sat there all day, so a still, still-present
     * vehicle is held for up to [MOTION_GRACE_SECONDS] to give its path time to show travel.
     */
    private fun shouldHold(event: FrigateEvent, now: Double): Boolean = when (categoryForLabel(event.label)) {
        MomentCategory.PEOPLE ->
            !everyoneAway &&
                settings.value.quietFamiliarPeople &&
                !event.isRecognized &&
                event.endTime == null &&
                now - event.startTime < RECOGNITION_GRACE_SECONDS

        MomentCategory.VEHICLES ->
            event.endTime == null &&
                now - event.startTime < MOTION_GRACE_SECONDS &&
                event.toDomain().isStill()

        else -> false
    }

    /**
     * Whether and how loudly to post: with nobody home every person notifies on the loud channel,
     * zone rules, quiet hours and the stranger rule notwithstanding; otherwise nothing posts during
     * quiet hours (or ever, with "only when everyone's away"), and outside them the user's rules decide, once
     * [inZones] has worked out where the object went and whether those zones wanted it at all —
     * a car crossing a birds-only street zone is dropped here, not judged as "anywhere else".
     * A vehicle that never moved is dropped before any of that: it is not news, wherever it sat.
     */
    private suspend fun decide(url: String, event: FrigateEvent, zones: Map<String, List<DetectionZone>>, recentVehicles: ArrayDeque<RecentVehicle>) {
        val category = categoryForLabel(event.label)
        val escalated = everyoneAway && category == MomentCategory.PEOPLE
        if (!escalated && settings.value.ordinaryAlertsSilenced(localMinuteOfDay())) return
        val moment = event.toDomain()
        if (category == MomentCategory.VEHICLES && moment.isStill()) return
        val placed = moment.inZones(zones[event.camera].orEmpty())
        if (!escalated && placed != null && isRepeatSighting(placed, recentVehicles)) return
        when {
            escalated -> notify(url, placed ?: moment, urgent = true)

            placed == null -> Unit

            settings.value.notifies(event.camera, placed.zones, category, recognized = event.isRecognized) -> {
                notify(url, placed, urgent = false)
                if (category == MomentCategory.VEHICLES) {
                    recentVehicles.addLast(RecentVehicle(placed, placed.startEpochSeconds))
                    while (recentVehicles.size > MAX_RECENT_VEHICLES) recentVehicles.removeFirst()
                }
            }
        }
    }

    /** A vehicle already posted, kept so its re-detections at the same spot read as more sightings of it. */
    private class RecentVehicle(var moment: MomentEvent, var lastSeenEpochSeconds: Double)

    /**
     * True when [placed] is a vehicle re-detected where one was posted within the visit window; the
     * memory is updated to it. Only moving vehicles get here (still ones are dropped in [decide]),
     * so the window is the visit's, [VehicleVisits.VISIT_GAP_SECONDS]. It can't come from
     * `MomentEvent.repeats`: this runs at an event's start, when it has no end yet, so the gap is
     * measured from the last sighting this poller actually saw.
     */
    private fun isRepeatSighting(placed: MomentEvent, recentVehicles: ArrayDeque<RecentVehicle>): Boolean {
        if (placed.category != MomentCategory.VEHICLES) return false
        val now = clock()
        recentVehicles.removeAll { now - it.lastSeenEpochSeconds > VehicleVisits.VISIT_GAP_SECONDS }
        val prior = recentVehicles.lastOrNull {
            placed.atSameSpotAs(it.moment) && placed.startEpochSeconds - it.lastSeenEpochSeconds <= VehicleVisits.VISIT_GAP_SECONDS
        } ?: return false
        prior.moment = placed.foldedInto(prior.moment)
        prior.lastSeenEpochSeconds = placed.startEpochSeconds
        return true
    }

    private val everyoneAway: Boolean get() = presenceRepository.presence.value.everyoneAway

    /** Minutes since local midnight by [clock], for quiet hours. */
    @OptIn(ExperimentalTime::class)
    private fun localMinuteOfDay(): Int {
        val time = Instant.fromEpochSeconds(clock().toLong()).toLocalDateTime(timeZone()).time
        return time.hour * 60 + time.minute
    }

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
        val text = AlertNotification(
            id = moment.id,
            title = if (urgent) "Away: ${presentation.title}" else presentation.title,
            body = where,
            target = MomentDeepLink(eventId = moment.id, cameraName = moment.cameraName, startEpochSeconds = moment.startEpochSeconds),
            urgent = urgent,
        )
        notifier.notify(text)
        // Off the poll loop: the next detection mustn't wait on this one's downloads.
        scope.launch {
            addAlertMedia(
                text = text,
                startEpochSeconds = moment.startEpochSeconds,
                clock = clock,
                thumbnail = { apiClient.getEventThumbnail(url, moment.id) },
                previewGif = { apiClient.getEventPreviewGif(url, moment.id) },
                post = { notifier.notify(it) },
                windowSeconds = previewWindowSeconds,
                retryDelaysMs = previewRetryDelaysMs,
            )
        }
    }

    private companion object {
        const val PAGE_SIZE = 50
        const val ZONES_REFRESH_SECONDS = 120.0
        const val MAX_REMEMBERED = 200
        const val OVERLAP_SECONDS = 1.0
        const val RECOGNITION_GRACE_SECONDS = 20.0

        /** How long a still, in-progress vehicle is given to show some travel before it's judged parked. */
        const val MOTION_GRACE_SECONDS = 60.0
        const val MAX_RECENT_VEHICLES = 20
    }
}
