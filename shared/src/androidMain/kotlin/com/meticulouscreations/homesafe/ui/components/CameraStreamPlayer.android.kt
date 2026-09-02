package com.meticulouscreations.homesafe.ui.components

import android.view.TextureView
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.pow

/**
 * go2rtc mints a new, short-lived HLS session (and session id embedded in the playlist/segment
 * URLs) on every request to the top-level `stream.m3u8` playlist. Once that session ages out,
 * in-flight requests for its playlist or segments start returning HTTP 404, which ExoPlayer
 * surfaces as a fatal [PlaybackException] with [PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS].
 * Restarting playback against the same [streamUrl] makes ExoPlayer re-fetch the top-level
 * playlist, and go2rtc mints a fresh session in response — so recovering from that specific
 * error just means re-preparing with the same [MediaItem]. Other IO errors (network blips) get
 * the same treatment; non-IO errors (e.g. decoder failures) are not assumed to be recoverable by
 * simply restarting. Retries back off exponentially and stop after [MAX_CONSECUTIVE_FAILURES] so
 * a genuinely offline server doesn't get hammered forever.
 *
 * This deliberately does NOT use [androidx.media3.ui.PlayerView]: PlayerView defaults to a
 * `SurfaceView` with an opaque black "shutter" drawn on top until the first frame renders, and
 * that shutter/SurfaceView pairing doesn't reliably composite with a Compose-drawn poster
 * underneath it (SurfaceView punches its own hole in the window rather than participating in
 * normal View alpha/z-order compositing) — which is exactly what caused black boxes to persist
 * even with a poster in place (e.g. every time this composable is torn down and recreated, such
 * as navigating from the camera detail screen back to the grid). A bare [TextureView] inside an
 * [AspectRatioFrameLayout] fully participates in normal View compositing, so the poster
 * underneath shows through reliably until a real frame is actually rendered to the texture.
 */
private const val MAX_CONSECUTIVE_FAILURES = 6
private const val BASE_RETRY_DELAY_MS = 1_000L
private const val MAX_RETRY_DELAY_MS = 30_000L

@Composable
actual fun CameraStreamPlayer(streamUrl: String, modifier: Modifier, posterUrl: String?) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val exoPlayer = remember(streamUrl) {
        val loadControl = DefaultLoadControl.Builder()
            // Tuned for live low-latency HLS rather than DefaultLoadControl's VOD-oriented
            // defaults (15s/50s/2.5s/5s) — a smaller max buffer means a reconnect doesn't have
            // to refill a large buffer before resuming playback. Starting points only; not
            // empirically tuned against a real network/server.
            .setBufferDurationsMs(1_500, 8_000, 500, 1_000)
            .build()
        ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .build()
            .apply { repeatMode = ExoPlayer.REPEAT_MODE_OFF }
    }
    // Hoisted alongside the player (not created in AndroidView's factory) so the same instance
    // is available to the video-size listener below, which keeps the crop/zoom aspect ratio
    // correct — PlayerView used to wire this internally, and we lose that for free by bypassing it.
    val aspectRatioFrameLayout = remember(exoPlayer) {
        AspectRatioFrameLayout(context).apply {
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            val textureView = TextureView(context).apply {
                // Never composite as an opaque (black) layer while there's no frame yet, so the
                // poster underneath stays visible until real video arrives.
                isOpaque = false
            }
            addView(textureView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            exoPlayer.setVideoTextureView(textureView)
        }
    }

    Box(modifier = modifier) {
        // Always mounted underneath — the video surface above is transparent until it actually
        // has a frame to draw, so this is the only thing visible during any load/reconnect, and
        // real frames simply paint over it once they arrive. No visibility state to track.
        if (posterUrl != null) {
            AsyncImage(
                model = posterUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { aspectRatioFrameLayout },
        )
    }

    DisposableEffect(exoPlayer, streamUrl) {
        var consecutiveFailures = 0

        fun startPlayback() {
            exoPlayer.setMediaItem(MediaItem.fromUri(streamUrl))
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }

        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                consecutiveFailures = 0
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                aspectRatioFrameLayout.setAspectRatio(
                    if (videoSize.height == 0 || videoSize.width == 0) {
                        0f
                    } else {
                        videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                    },
                )
            }

            override fun onPlayerError(error: PlaybackException) {
                val isRecoverableIoError = when (error.errorCode) {
                    PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                    PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
                    -> true
                    else -> false
                }
                if (!isRecoverableIoError || consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    return
                }
                consecutiveFailures++
                val backoffMs = min(
                    BASE_RETRY_DELAY_MS * 2.0.pow(consecutiveFailures - 1).toLong(),
                    MAX_RETRY_DELAY_MS,
                )
                coroutineScope.launch {
                    delay(backoffMs)
                    startPlayback()
                }
            }
        }
        exoPlayer.addListener(listener)
        startPlayback()

        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }
}
