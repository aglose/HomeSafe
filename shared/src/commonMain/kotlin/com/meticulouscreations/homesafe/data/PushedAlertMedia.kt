package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.platform.AlertNotification
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.network.PushRelayApi
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.first
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * The picture and clip for a notification that arrived as a push. A push can wake the app from
 * nothing — no Activity, no sign-in, no Frigate session — so these come through the relay on
 * this install's device secret (see `PushRelayApi.getEventMedia`), from the live server if there
 * is one and otherwise the last one signed in to, remote address first (as presence does).
 */
@OptIn(ExperimentalTime::class)
@Inject
class PushedAlertMedia(
    private val connectionRepository: ConnectionRepository,
    private val relayApi: PushRelayApi,
    private val identity: DeviceIdentityStore,
    private val clock: Clock,
) {
    /**
     * Re-posts [text] — already on screen — with the picture and then the clip of [eventId],
     * which started at [startEpochSeconds]; see [addAlertMedia]. Returns once [post] has shown the
     * clip or its last ask has failed, about a minute at most.
     */
    suspend fun addTo(text: AlertNotification, eventId: String, startEpochSeconds: Double, post: suspend (AlertNotification) -> Unit) =
        addAlertMedia(
            text = text,
            startEpochSeconds = startEpochSeconds,
            clock = { clock.now().toEpochMilliseconds() / 1000.0 },
            thumbnail = { fetch(eventId, "thumbnail.jpg") },
            previewGif = { fetch(eventId, "preview.gif") },
            post = post,
        )

    private suspend fun fetch(eventId: String, name: String): Result<ByteArray> {
        val deviceId = identity.deviceId()
        val secret = identity.secret()
        var last: Result<ByteArray> = Result.failure(IllegalStateException("No server to ask"))
        for (url in candidateUrls()) {
            last = relayApi.getEventMedia(url, eventId, name, deviceId, secret)
            if (last.isSuccess) break
        }
        return last
    }

    private suspend fun candidateUrls(): List<String> {
        connectionRepository.currentServerUrl.value?.let { return listOf(it) }
        val recent = connectionRepository.mostRecentConnection.first() ?: return emptyList()
        return listOfNotNull(recent.serverUrl, recent.localUrl).distinct()
    }
}
