package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.domain.model.ActiveConnection
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.domain.repository.MediaUrlRepository
import com.meticulouscreations.homesafe.network.swapUrlScheme
import com.meticulouscreations.homesafe.ui.components.LivePlayerPrefetch
import com.meticulouscreations.homesafe.ui.components.LivePrefetch
import com.meticulouscreations.homesafe.ui.components.LiveStartupMilestones
import com.meticulouscreations.homesafe.ui.components.VideoSource
import com.meticulouscreations.homesafe.ui.components.WebRtcEndpoint
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Starts the Home grid's live players while the sign-in is still going, so the cards open onto
 * video that is already joining instead of starting from scratch once they compose.
 *
 * A cold start's first live picture used to wait on everything in turn: the server checking the
 * password (~450 ms over Tailscale), the route being verified, Home composing (~250 ms), and only
 * then the join itself. The streams come from go2rtc, which takes no credentials, so none of that
 * has to come first: as soon as [ConnectionRepository.expectedConnection] names the address the
 * sign-in will land on (the LAN answering, usually within ~50 ms), the players for the cameras
 * cached for that server are started under the same keys and sources the cards will use. Nothing
 * is drawn anywhere until a card binds, which only happens once the sign-in has succeeded; if it
 * fails instead, the players are let go at once.
 *
 * Before any of that, at launch, the offers those joins will make are prepared for the last
 * server this device signed in to ([LivePlayerPrefetch.prepare]): peer created and ICE gathered,
 * nothing sent. A device that has never signed in (nothing cached) prepares and prefetches nothing.
 * For a returning user the expectation can arrive while the biometric prompt is still up — see
 * `ConnectionRepositoryImpl.anticipateSavedLogin`.
 *
 * The sources must match what the Home grid builds (see `HomeViewModel.tile` — grid stream,
 * silent WebRTC) or the cards would start their own. A wrong guess costs little: the card's
 * source replaces the prefetched one, and the prefetched peer's picture stays up until the new
 * join has a frame.
 */
@Inject
@SingleIn(AppScope::class)
class LiveStreamPrefetcher(
    private val connectionRepository: ConnectionRepository,
    private val cameraDao: CameraDao,
    private val mediaUrls: MediaUrlRepository,
    private val players: LivePlayerPrefetch,
    private val appScope: CoroutineScope,
) {
    private var job: Job? = null

    /** Idempotent; follows sign-ins for the life of the app. */
    fun start() {
        if (job != null) return
        job = appScope.launch {
            launch { prepareOffers() }
            var started = false
            connectionRepository.expectedConnection.collectLatest { expected ->
                if (expected != null) {
                    started = prefetch(expected) || started
                } else if (started) {
                    started = false
                    // The sign-in ended without a connection: nobody is going to watch these.
                    if (connectionRepository.activeConnection.value == null) players.cancel()
                }
            }
        }
    }

    private suspend fun prepareOffers() {
        val record = connectionRepository.mostRecentConnection.first() ?: return
        val count = cachedCameras(record.serverUrl).count { it.enabled }.coerceAtMost(MAX_PREFETCHED)
        if (count > 0) players.prepare(count)
    }

    /** Returns whether anything was started. */
    private suspend fun prefetch(expected: ActiveConnection): Boolean {
        val activeUrl = expected.activeUrl
        val streams = cachedCameras(expected.serverUrl)
            .filter { it.enabled }
            .take(MAX_PREFETCHED)
            .map { camera ->
                val webRtc = WebRtcEndpoint(mediaUrls.liveWebRtcSignalingUrl(activeUrl, camera.gridStreamName), audio = false)
                LivePrefetch(
                    playerKey = camera.name,
                    source = VideoSource.Live(mediaUrls.liveStreamUrl(activeUrl, camera.gridStreamName), webRtc = webRtc),
                )
            }
        if (streams.isEmpty()) return false
        LiveStartupMilestones.mark("live.prefetch")
        players.prefetch(streams)
        return true
    }

    /**
     * The cameras cached for [serverUrl] — or for the same address under the other scheme: until
     * the login has answered, the URL is the one typed or saved, and a sign-in that heals a stale
     * scheme (see `ConnectionRepositoryImpl.loginResolvingScheme`) files the cameras under the
     * healed one.
     */
    private suspend fun cachedCameras(serverUrl: String): List<CameraEntity> =
        cameraDao.observeByServer(serverUrl).first().ifEmpty {
            swapUrlScheme(serverUrl)?.let { cameraDao.observeByServer(it).first() }.orEmpty()
        }

    private companion object {
        /** About a phone screen of Home cards; a card further down the list may never be scrolled to. */
        const val MAX_PREFETCHED = 4
    }
}
