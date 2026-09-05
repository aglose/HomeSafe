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
import kotlinx.coroutines.delay
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
    /** How long a player with nobody watching stays paused-but-connected before its network session and decoder are released. */
    const val IDLE_STOP_MS = 30_000L

    /**
     * How long a pooled player outlives its last binder before the pool forgets it entirely.
     * Kept a little past [IDLE_STOP_MS] so a quick "tab away and back" reuses the same object.
     */
    const val POOL_RELEASE_MS = 45_000L

    /** Cadence of the fresh-snapshot refresh while no video frame is on screen. */
    const val POSTER_REFRESH_MS = 1_000L

    /** Slower cadence once a snapshot fetch has failed, so an unreachable server isn't polled hard. */
    const val POSTER_RETRY_MS = 3_000L

    private const val FIRST_RETRY_DELAY_MS = 250L
    private const val MAX_RETRY_DELAY_MS = 30_000L

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
            contentScale = ContentScale.Crop,
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
            contentScale = ContentScale.Crop,
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
            contentScale = ContentScale.Crop,
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
