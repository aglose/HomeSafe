package com.meticulouscreations.homesafe.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.size.Precision
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The last picture this device saw of a live stream, for a card to show before its video has a
 * frame: what a Frigate camera's `latest.jpg` is to its card, for a camera whose server can't
 * hand one out quickly. A Nest camera's stream exists only while someone watches it, and even a
 * running one gives a still only at its next keyframe (seconds away), so asking the server when
 * the card appears would be no quicker than the video itself. Instead the still is taken while
 * the video is already playing ([capture], from go2rtc's `frame.jpeg`, which costs nothing extra
 * then), kept in Coil's disk cache, and shown from there the next time: on screen the moment the
 * card is, even on a cold start.
 *
 * The model for an image request that only ever reads what was kept ([LastSeenStillFetcher]): a
 * still never taken shows nothing, rather than sending the request to the server.
 */
data class LastSeenStill(val url: String)

/**
 * Where a still is kept, in the disk and memory caches, and what its capture is throttled by: the
 * whole URL but its scheme, host and stream name included. Two servers' cameras of the same name
 * never share a still (one server's picture must never show on another's card). The price is
 * that a server's LAN and Tailscale addresses keep a still each.
 */
internal fun lastSeenStillKey(url: String): String = "last-seen:" + url.substringAfter("://")

/** Reads a [LastSeenStill] from the disk cache, and nowhere else. Registered on the app's image loader. */
class LastSeenStillFetcher(private val still: LastSeenStill, private val imageLoader: ImageLoader) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val diskCache = imageLoader.diskCache ?: throw NoStillException()
        val key = lastSeenStillKey(still.url)
        val snapshot = diskCache.openSnapshot(key) ?: throw NoStillException()
        return SourceFetchResult(
            source = ImageSource(file = snapshot.data, fileSystem = diskCache.fileSystem, diskCacheKey = key, closeable = snapshot),
            mimeType = "image/jpeg",
            dataSource = DataSource.DISK,
        )
    }

    class Factory : Fetcher.Factory<LastSeenStill> {
        override fun create(data: LastSeenStill, options: Options, imageLoader: ImageLoader): Fetcher = LastSeenStillFetcher(data, imageLoader)
    }

    /** Nothing kept yet: the request fails quietly and the card shows what it would have without one. */
    private class NoStillException : IllegalStateException("No still kept yet")
}

/** Taking the stills: at most once every [CAPTURE_INTERVAL] for each stream, for the life of the process. */
object LastSeenStills {
    private val captured = mutableMapOf<String, TimeMark>()

    /**
     * Fetches [url] (a still of a stream that is playing right now) into the caches the
     * [LastSeenStill] for it reads. Runs on the image loader's own scope, so a card scrolled
     * away meanwhile doesn't cancel it. Main thread only.
     */
    fun capture(context: PlatformContext, url: String) {
        val key = lastSeenStillKey(url)
        if (captured[key]?.let { it.elapsedNow() < CAPTURE_INTERVAL } == true) return
        captured[key] = TimeSource.Monotonic.markNow()
        val request = ImageRequest.Builder(context)
            .data(url)
            .diskCacheKey(key)
            .memoryCacheKey(key)
            .diskCachePolicy(CachePolicy.WRITE_ONLY)
            .memoryCachePolicy(CachePolicy.WRITE_ONLY)
            .size(STILL_SIZE_PX)
            .precision(Precision.INEXACT)
            .build()
        SingletonImageLoader.get(context).enqueue(request)
    }

    /** The request that shows [url]'s kept still. */
    fun request(context: PlatformContext, url: String): ImageRequest {
        val key = lastSeenStillKey(url)
        return ImageRequest.Builder(context)
            .data(LastSeenStill(url))
            .memoryCacheKey(key)
            .precision(Precision.INEXACT)
            .build()
    }

    /** Often enough that the still is of today, rarely enough that a camera on screen isn't asked all the time. */
    private val CAPTURE_INTERVAL = 10.minutes

    /** Plenty for a card or a phone's page; the file on disk is the server's own JPEG. */
    private const val STILL_SIZE_PX = 1280
}

/**
 * Takes [stillUrl]'s still ([LastSeenStills.capture]) once its stream is [live] and has been for
 * a moment, so the still is of the picture rather than of the first, blocky frames.
 */
@Composable
fun CaptureLastSeenStill(stillUrl: String?, live: Boolean) {
    val context = LocalPlatformContext.current
    LaunchedEffect(stillUrl, live) {
        if (stillUrl == null || !live) return@LaunchedEffect
        delay(CAPTURE_DELAY_MS)
        LastSeenStills.capture(context, stillUrl)
    }
}

private const val CAPTURE_DELAY_MS = 2_000L
