package com.meticulouscreations.homesafe.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.compose.LifecycleStartEffect
import org.bytedeco.javacv.FFmpegFrameGrabber

/**
 * Binds a [LivePlayerHolder] (pooled per [playerKey], see [LivePlayerPool]) to this call site and
 * draws its latest decoded frame, with a live poster over the top until that frame is one this
 * source actually produced.
 *
 * The holder owns the FFmpeg session, the retry loop and the idle/lifecycle policy; this
 * composable owns only the caller's callbacks and what is drawn. Several may bind to one holder at
 * once — the grid card and the detail screen overlap during the shared-element transition — and
 * they all draw the same [LivePlayerHolder.frame], so no frame is ever bridged between surfaces
 * the way Android and iOS have to: there is no surface here, only a bitmap Compose paints.
 *
 * The video is stretched to fill whatever box the caller gives it — no letterboxing, no
 * aspect-ratio crop — and so is the poster. Every box in this app is a fixed 16:9 frame, and the
 * decoded frame's own pixel aspect is not a reliable guide to how it should be shown: a camera's
 * low-bitrate sub-stream is often an anamorphic squeeze of its full 16:9 view (the Amcrest's
 * 704x480 covers exactly the same field of view as its 2960x1668 main stream), so fitting *that*
 * by its pixel aspect crops the top and bottom off — and because one pooled player swaps between
 * the grid's sub-stream and the detail screen's full stream, the picture would re-crop on every
 * quality change. Filling the box shows both with the same geometry.
 */
@Composable
actual fun CameraStreamPlayer(
    request: PlayerRequest,
    modifier: Modifier,
    playerKey: String?,
    onPositionChanged: (positionMs: Long) -> Unit,
    onBufferingChanged: (isBuffering: Boolean) -> Unit,
    onStreamStatusChanged: (status: LiveStreamStatus) -> Unit,
    onPlaybackEnded: () -> Unit,
    onPlaybackError: () -> Unit,
    onAudioAvailabilityChanged: (hasAudio: Boolean) -> Unit,
) {
    val holder = remember(playerKey) { HolderLease(playerKey) }.holder

    val source = request.source
    val currentSource by rememberUpdatedState(source)
    val currentOnPositionChanged by rememberUpdatedState(onPositionChanged)
    val currentOnBufferingChanged by rememberUpdatedState(onBufferingChanged)
    val currentOnStreamStatusChanged by rememberUpdatedState(onStreamStatusChanged)
    val currentOnPlaybackEnded by rememberUpdatedState(onPlaybackEnded)
    val currentOnPlaybackError by rememberUpdatedState(onPlaybackError)
    val currentOnAudioAvailabilityChanged by rememberUpdatedState(onAudioAvailabilityChanged)

    val frame = holder.frame
    // Nothing decoded yet for the holder's current cold start, so whatever is on screen — an old
    // frame, or nothing at all — is not a picture of this source.
    val posterVisible = frame == null || holder.renderedGeneration != holder.coldStartGeneration

    val streamStatus = when {
        posterVisible -> LiveStreamStatus.Connecting
        holder.isStalled -> LiveStreamStatus.Buffering
        else -> LiveStreamStatus.Live
    }
    LaunchedEffect(streamStatus) { currentOnStreamStatusChanged(streamStatus) }
    LaunchedEffect(holder.isStalled) { currentOnBufferingChanged(holder.isStalled) }
    LaunchedEffect(holder.hasAudio) { currentOnAudioAvailabilityChanged(holder.hasAudio) }
    LaunchedEffect(holder.positionMs) {
        if (currentSource is VideoSource.Recording) currentOnPositionChanged(holder.positionMs)
    }

    Box(modifier = modifier.background(Color.Black)) {
        if (frame != null) {
            Image(
                bitmap = frame,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )
        }
        val posterUrl = source.posterUrl
        if (posterVisible && posterUrl != null) {
            VideoPosterLayer(
                posterUrl = posterUrl,
                refresh = source is VideoSource.Live,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    DisposableEffect(holder) {
        val listener = object : LivePlayerHolder.Listener {
            override fun onRecordingEnded() {
                if (currentSource is VideoSource.Recording) currentOnPlaybackEnded()
            }

            override fun onRecordingFailed() {
                if (currentSource is VideoSource.Recording) currentOnPlaybackError()
            }
        }
        holder.addListener(listener)
        onDispose { holder.removeListener(listener) }
    }

    LifecycleStartEffect(holder) {
        holder.onBinderStarted()
        onStopOrDispose { holder.onBinderStopped() }
    }

    LaunchedEffect(holder, source.url) { holder.load(source) }

    LaunchedEffect(holder, request.playWhenReady) { holder.setPlayWhenReady(request.playWhenReady) }

    LaunchedEffect(holder, request.muted) { holder.setMuted(request.muted) }

    LaunchedEffect(holder, request.seek) {
        val seek = request.seek ?: return@LaunchedEffect
        holder.seekTo(seek.positionMs)
    }
}

/**
 * FFmpeg decodes both of the codecs go2rtc will put in an HLS stream, so the desktop player asks
 * for either — unlike iOS, which can only play AAC there.
 */
actual val liveAudioCodecs: List<String> = listOf("aac", "opus")

/**
 * Unpacks FFmpeg's native libraries, off the caller's thread, so the first camera does not pay
 * for it. JavaCPP extracts them out of the jars into `~/.javacpp/cache` the first time anything
 * touches them, which on a cold machine is around six seconds — long enough to be the whole of
 * the wait for the first video, and short enough to disappear entirely behind the connect screen
 * if it is started at launch. Safe to call more than once, and safe to never call: it only moves
 * work that the first [CameraStreamPlayer] would otherwise do itself.
 */
fun warmUpVideoDecoder() {
    Thread({ runCatching { FFmpegFrameGrabber.tryLoad() } }, "homesafe-video-warmup").apply {
        isDaemon = true
        start()
    }
}

/**
 * Ties a pool lease to a `remember` slot, releasing it whether the composition forgets it or
 * abandons it before it ever applied — a plain DisposableEffect would leak the reference count
 * in the latter case.
 */
private class HolderLease(key: String?) : RememberObserver {
    val holder: LivePlayerHolder = LivePlayerPool.acquire(key)

    override fun onRemembered() = Unit
    override fun onForgotten() = LivePlayerPool.release(holder)
    override fun onAbandoned() = LivePlayerPool.release(holder)
}
