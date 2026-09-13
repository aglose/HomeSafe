package com.meticulouscreations.homesafe.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Precision
import com.meticulouscreations.homesafe.AppVisibility
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlin.math.min

/**
 * The decisions every platform's live player makes the same way, kept platform-free so they're
 * unit-testable and can't drift between Android and iOS.
 *
 * The model: a camera's player is a long-lived object (see each platform's `LivePlayerPool`)
 * that any number of on-screen "binders" — the grid card, the detail screen's surface, both at
 * once mid-transition — attach to. It plays while at least one binder is attached *and* in a
 * started lifecycle, pauses the moment none is, and only drops its network session and decoder
 * after [IDLE_STOP_MS] of nobody watching. Coming back inside that window is a warm resume
 * (jump to the live edge; the last frame is still on screen); after it, a cold start, which is
 * what the poster layer covers.
 */
internal object LivePlaybackPolicy {
    /**
     * How long a player with nobody watching stays paused-but-connected before its network
     * session and decoder are released, while the app is in the background: the phone is in a
     * pocket, and every second of held-open stream is battery and data for nothing.
     */
    const val IDLE_STOP_MS = 30_000L

    /**
     * The same window while the app is on screen: the user is on another tab, or in one camera's
     * detail screen while the rest of the grid waits. Coming back inside this window is a warm
     * resume — the picture moves on the next frame instead of after a fresh join and a keyframe.
     * Streams left connected but unwatched keep flowing from the server, so the window is
     * generous rather than unbounded. See [awaitIdleWindow].
     */
    const val IDLE_STOP_FOREGROUND_MS = 90_000L

    /**
     * How long a pooled player outlives its last binder before the pool forgets it entirely.
     * Kept past the longer idle window so a "tab away and back" reuses the same object.
     */
    const val POOL_RELEASE_MS = IDLE_STOP_FOREGROUND_MS + 15_000L

    /**
     * How long a holder keeps a WebRTC peer it no longer shows — because the user stepped from
     * the grid stream to the full-quality one, or back — when that peer carries the expensive
     * stream (the one with audio: the detail screen's full-quality join). Long enough for "open
     * a camera, back to the grid, open it again" to be instant; short enough that the main
     * stream isn't left flowing for a viewer who has moved on. A peer carrying the cheap grid
     * stream is kept for as long as the holder itself is alive (see [standbyTtlMs]).
     */
    const val STANDBY_PEER_TTL_MS = 20_000L

    /** Cadence of the fresh-snapshot refresh while no video frame is on screen. */
    const val POSTER_REFRESH_MS = 1_000L

    /** Slower cadence once a snapshot fetch has failed, so an unreachable server isn't polled hard. */
    const val POSTER_RETRY_MS = 3_000L

    /**
     * How long a WebRTC join may take from creating the offer to ICE reporting a connection.
     * Host candidates over the LAN or Tailscale pair in well under a second; anything slower is
     * a blocked port or a dead route, and the HLS fallback is the faster way to a picture.
     */
    const val WEBRTC_CONNECT_TIMEOUT_MS = 3_000L

    /**
     * How long a connected peer may go without delivering a decoded frame. Bounded by the
     * camera's keyframe interval (a new consumer starts at the next keyframe; 2 s on these
     * cameras) plus decoder start-up.
     */
    const val WEBRTC_FIRST_FRAME_TIMEOUT_MS = 5_000L

    /** How many WebRTC joins of one stream may fail before the fallback sticks — see [WEBRTC_FALLBACK_TTL_MS]. */
    const val WEBRTC_FAILURES_BEFORE_FALLBACK = 2

    /**
     * How long a stream stays on HLS after WebRTC gave up on it, before the next cold start
     * tries WebRTC again. Long enough not to pay the connect timeout on every rebind while a
     * port is blocked; short enough that fixing the firewall is felt without restarting the app.
     */
    const val WEBRTC_FALLBACK_TTL_MS = 10 * 60_000L

    private const val FIRST_RETRY_DELAY_MS = 250L
    private const val MAX_RETRY_DELAY_MS = 30_000L

    /** The idle window that applies right now: [IDLE_STOP_FOREGROUND_MS] on screen, [IDLE_STOP_MS] otherwise. */
    fun idleStopMs(appInForeground: Boolean): Long = if (appInForeground) IDLE_STOP_FOREGROUND_MS else IDLE_STOP_MS

    /**
     * Suspends for the idle window, restarting the countdown from the new window whenever the
     * app's visibility changes: a player left unwatched on screen and then backgrounded is
     * released [IDLE_STOP_MS] after the app went away, not [IDLE_STOP_FOREGROUND_MS] after the
     * viewer left. [inForeground] defaults to the app's real state; tests pass their own.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun awaitIdleWindow(inForeground: Flow<Boolean> = AppVisibility.inForeground) {
        inForeground.mapLatest { delay(idleStopMs(it)) }.first()
    }

    /**
     * Whether a peer joined for [have] can show a source that asks for [want] without a new
     * join. The video is the same the moment the signaling URL is the same — go2rtc's stream
     * name is in it — and a peer that negotiated audio can play silently for a source that
     * didn't ask for any (volume, not track selection, is how mute works), but not the reverse:
     * WHEP has no renegotiation, so audio can't be added to a peer that offered without it.
     */
    fun canServe(have: WebRtcEndpoint, want: WebRtcEndpoint): Boolean =
        have.signalingUrl == want.signalingUrl && (have.audio || !want.audio)

    /**
     * How long a peer joined for [endpoint] is kept on standby once the holder stops showing it:
     * null for the cheap grid stream (kept until the holder itself lets go), [STANDBY_PEER_TTL_MS]
     * for the full-quality one. The audio flag is the tell — only the detail screen's
     * full-quality source asks for sound.
     */
    fun standbyTtlMs(endpoint: WebRtcEndpoint): Long? = if (endpoint.audio) STANDBY_PEER_TTL_MS else null

    /**
     * Whether swapping the player from [previous] to [next] should be treated as a cold start
     * — i.e. whatever frame is on screen is no longer a preview of what's coming, so the poster
     * should cover it until [next] renders. Live-to-live (a quality step, or the grid re-requesting
     * its stream after the detail screen upgraded it) is warm: the old frame *is* the same camera
     * a moment ago, and hiding it behind a snapshot would be a regression. Anything involving a
     * recording is cold: a recording's last frame is not "what the camera sees now".
     */
    fun isColdSwap(previous: VideoSource?, next: VideoSource): Boolean =
        previous !is VideoSource.Live || next !is VideoSource.Live

    /**
     * Back-off before the [consecutiveFailures]th retry of a live stream. Starts fast — the
     * overwhelmingly common failure is go2rtc's short-lived HLS session having expired while
     * the player was paused, which a fresh playlist request fixes instantly — and doubles up to
     * [MAX_RETRY_DELAY_MS], where it stays for as long as someone is still watching. There is no
     * give-up: one request every 30 s per camera is cheap, and it's what brings the video back
     * on its own when the server reboots.
     */
    fun retryDelayMs(consecutiveFailures: Int): Long {
        val exponent = (consecutiveFailures - 1).coerceIn(0, 16)
        return min(FIRST_RETRY_DELAY_MS shl exponent, MAX_RETRY_DELAY_MS)
    }
}

/**
 * What's on screen while a player has no frame of its own to show for its current source.
 *
 * Drawn with [ContentScale.FillBounds], matching how both platforms' players fit the video to
 * the same box (stretched, never cropped — see `CameraStreamPlayer`), so the hand-over from
 * poster to first frame doesn't shift the picture.
 *
 * With [refresh] (live sources): never an old picture. Two images, stacked. The bottom one is the last snapshot this device ever saw for [posterUrl],
 * read from Coil's memory/disk cache — on screen in the same frame the card appears, even on a
 * cold app start, at the cost of possibly being stale. The top one is fetched from the server
 * right now, bypassing every cache read, and replaces it as soon as it lands (Frigate serves
 * `latest.jpg` from memory in a few milliseconds, so on the LAN that's well under 100 ms). It then
 * keeps refreshing at [LivePlaybackPolicy.POSTER_REFRESH_MS] for as long as this layer is shown,
 * so a stalled or still-connecting stream reads as a slow live view rather than a frozen photo.
 * Each fresh fetch also writes back into the cache under the same key, so the *next* cold start's
 * "stale" image is only as old as the last time this camera was on screen.
 *
 * Why this exists: Coil 3 does not honour HTTP cache headers by default. Frigate marks
 * `latest.jpg` `no-store`, but with the default cache strategy the first snapshot fetched for
 * a URL was served forever after — which is exactly the "old picture for ages" the grid used to show.
 */
@Composable
internal fun VideoPosterLayer(posterUrl: String, refresh: Boolean, modifier: Modifier = Modifier) {
    if (!refresh) {
        // A still of a fixed moment (a recording snapshot, an event frame) never changes, so the
        // ordinary cache path is exactly right: instant on a repeat, one fetch otherwise.
        AsyncImage(
            model = posterUrl,
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = modifier,
        )
        return
    }
    val platformContext = LocalPlatformContext.current
    val cacheKey = "live-poster:$posterUrl"

    // A refresh is scheduled only after the previous fetch finishes: a free-running ticker would
    // cancel a slow (Tailscale, cellular) fetch every second and never complete one.
    var tick by remember(posterUrl) { mutableIntStateOf(0) }
    var completedTick by remember(posterUrl) { mutableIntStateOf(-1) }
    var lastFetchFailed by remember(posterUrl) { mutableIntStateOf(0) }
    LaunchedEffect(posterUrl, completedTick) {
        if (completedTick < 0) return@LaunchedEffect
        delay(if (lastFetchFailed != 0) LivePlaybackPolicy.POSTER_RETRY_MS else LivePlaybackPolicy.POSTER_REFRESH_MS)
        tick = completedTick + 1
    }

    Box(modifier) {
        val lastKnown = remember(posterUrl) {
            ImageRequest.Builder(platformContext)
                .data(posterUrl)
                .memoryCacheKey(cacheKey)
                .diskCacheKey(cacheKey)
                .precision(Precision.INEXACT)
                .build()
        }
        AsyncImage(
            model = lastKnown,
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize(),
        )

        val fresh = remember(posterUrl, tick) {
            ImageRequest.Builder(platformContext)
                .data(posterUrl)
                .memoryCacheKey(cacheKey)
                .diskCacheKey(cacheKey)
                // While the next fetch is in flight, keep showing the previous fresh one.
                .placeholderMemoryCacheKey(cacheKey)
                .memoryCachePolicy(CachePolicy.WRITE_ONLY)
                // Only the first fresh snapshot per appearance goes to disk — enough to seed the
                // next cold start without rewriting flash once a second per camera.
                .diskCachePolicy(if (tick == 0) CachePolicy.WRITE_ONLY else CachePolicy.DISABLED)
                .precision(Precision.INEXACT)
                .build()
        }
        AsyncImage(
            model = fresh,
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize(),
            onSuccess = {
                lastFetchFailed = 0
                completedTick = tick
            },
            onError = {
                lastFetchFailed = 1
                completedTick = tick
            },
        )
    }
}
