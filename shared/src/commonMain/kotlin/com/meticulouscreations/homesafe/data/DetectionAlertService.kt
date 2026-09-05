package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.AlertSettings
import com.meticulouscreations.homesafe.domain.model.categoryForLabel
import com.meticulouscreations.homesafe.domain.model.present
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.domain.repository.SettingsRepository
import com.meticulouscreations.homesafe.network.FrigateApiClient
import com.meticulouscreations.homesafe.network.FrigateEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
 */
class DetectionAlertService(
    private val apiClient: FrigateApiClient,
    private val connectionRepository: ConnectionRepository,
    settingsRepository: SettingsRepository,
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

    /** Posts a sample notification so the user can see what one looks like and that the OS lets them through. */
    fun sendTestNotification() {
        notifier.notify(
            AlertNotification(
                id = "test",
                title = "Test alert",
                body = "Notifications from HomeSafe are working. Detections will look like this.",
            ),
        )
    }

    private suspend fun poll(url: String) {
        var after = clock()
        val seen = LinkedHashSet<String>()
        while (true) {
            apiClient.getEvents(url, limit = PAGE_SIZE, afterEpochSeconds = after).onSuccess { events ->
                events.filter { it.id !in seen }.sortedBy { it.startTime }.forEach { event ->
                    seen += event.id
                    if (settings.value.notifies(event.camera, event.zones, categoryForLabel(event.label))) notify(url, event)
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

    @OptIn(ExperimentalTime::class)
    private suspend fun notify(url: String, event: FrigateEvent) {
        val moment = event.toDomain()
        val timeZone = TimeZone.currentSystemDefault()
        val today = Instant.fromEpochSeconds(clock().toLong()).toLocalDateTime(timeZone).date
        val presentation = moment.present(today, timeZone)
        val where = buildString {
            append(moment.cameraDisplayName)
            append(" · ")
            append(presentation.timeLabel)
            moment.subLabel?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
        }
        notifier.notify(
            AlertNotification(
                id = event.id,
                title = presentation.title,
                body = where,
                thumbnail = apiClient.getEventThumbnail(url, event.id).getOrNull(),
            ),
        )
    }

    private companion object {
        const val PAGE_SIZE = 50
        const val MAX_REMEMBERED = 200
        const val OVERLAP_SECONDS = 1.0
    }
}
