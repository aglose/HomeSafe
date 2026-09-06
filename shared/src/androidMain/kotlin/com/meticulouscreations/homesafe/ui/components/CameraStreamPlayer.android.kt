package com.meticulouscreations.homesafe.ui.components

import android.content.Context
import android.view.TextureView
import android.view.ViewGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.ui.AspectRatioFrameLayout
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Binds a [LivePlayerHolder] (pooled per [playerKey], see [LivePlayerPool]) to this call site's
 * own `TextureView`, and draws a live poster over it until that surface has a real frame.
 *
 * The holder owns the [androidx.media3.exoplayer.ExoPlayer], its source, its retry loop and its
 * idle/lifecycle policy; this composable owns only what's specific to one place on screen: the
 * surface, the aspect-ratio fit, the poster, and the caller's callbacks. Several of these can be
 * bound to one holder at the same time (the grid card and the detail screen overlap during the
 * shared-element transition); frames go to whichever surface was bound most recently, and each
 * `TextureView` keeps showing its last frame after it stops receiving them.
 *
 * The poster is a [VideoPosterLayer] drawn *on top* of the video and hidden once this surface
 * renders its first frame for the holder's current [LivePlayerHolder.coldStartGeneration]. That
 * covers the cases where the surface has nothing or something stale to show — a brand-new
 * surface, a cold connect, a reconnect after an error, a return from a long background — with a
 * snapshot that is at most a second old, and leaves a good frame alone across warm swaps.
 *
 * A bare `TextureView` is used rather than [androidx.media3.ui.PlayerView]: PlayerView's default
 * `SurfaceView` punches its own hole in the window and doesn't composite with Compose content
 * above or below it, which is how black boxes used to persist over posters.
 */
private const val POSITION_POLL_INTERVAL_MS = 250L

/** Fallback for hiding the poster if a surface swap ever fails to re-fire `onRenderedFirstFrame`: this many polls of steady playback. */
private const val STEADY_PLAYBACK_POLLS_TO_TRUST = 3

@Composable
actual fun CameraStreamPlayer(
    request: PlayerRequest,
    modifier: Modifier,
    playerKey: String?,
    onPositionChanged: (positionMs: Long) -> Unit,
    onBufferingChanged: (isBuffering: Boolean) -> Unit,
    onPlaybackEnded: () -> Unit,
    onPlaybackError: () -> Unit,
    onAudioAvailabilityChanged: (hasAudio: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val holder = remember(playerKey) { HolderLease(context, playerKey) }.holder
    val player = holder.player

    val source = request.source
    val currentSource by rememberUpdatedState(source)
    val currentOnPositionChanged by rememberUpdatedState(onPositionChanged)
    val currentOnBufferingChanged by rememberUpdatedState(onBufferingChanged)
    val currentOnPlaybackEnded by rememberUpdatedState(onPlaybackEnded)
    val currentOnPlaybackError by rememberUpdatedState(onPlaybackError)
    val currentOnAudioAvailabilityChanged by rememberUpdatedState(onAudioAvailabilityChanged)

    // The cold-start generation this surface last rendered a frame for; the poster stays up
    // until it catches up with the holder's current one.
    var renderedGeneration by remember(holder) { mutableIntStateOf(-1) }
    val posterVisible = renderedGeneration != holder.coldStartGeneration

    val textureView = remember(holder) {
        TextureView(context).apply {
            // Never composite as an opaque (black) layer while there's no frame yet.
            isOpaque = false
        }
    }
    val aspectRatioFrameLayout = remember(holder) {
        AspectRatioFrameLayout(context).apply {
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            addView(textureView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    // The last frame of whichever surface this one took over from (see LivePlayerHolder.bindSurface),
    // shown until this surface has a frame of its own. Cleared on that first frame.
    var bridgeFrame by remember(holder) { mutableStateOf<ImageBitmap?>(null) }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { aspectRatioFrameLayout },
        )
        val bridge = bridgeFrame
        val posterUrl = source.posterUrl
        if (bridge != null) {
            Image(
                bitmap = bridge,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (posterVisible && posterUrl != null) {
            VideoPosterLayer(posterUrl = posterUrl, refresh = source is VideoSource.Live, modifier = Modifier.fillMaxSize())
        }
    }

    DisposableEffect(holder) {
        bridgeFrame = holder.bindSurface(textureView)?.asImageBitmap()
        // A warm holder won't re-announce its video size; fit the surface to what it's already playing.
        aspectRatioFrameLayout.applyVideoSize(player.videoSize)

        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                renderedGeneration = holder.coldStartGeneration
                holder.onSurfaceRenderedFrame(textureView)
                bridgeFrame = null
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                aspectRatioFrameLayout.applyVideoSize(videoSize)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                currentOnBufferingChanged(playbackState == Player.STATE_BUFFERING)
                if (playbackState == Player.STATE_ENDED && currentSource is VideoSource.Recording) {
                    currentOnPlaybackEnded()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (currentSource is VideoSource.Recording) currentOnPlaybackError()
            }

            override fun onTracksChanged(tracks: Tracks) {
                currentOnAudioAvailabilityChanged(tracks.hasAudio())
            }
        }
        player.addListener(listener)
        // A warm holder won't re-announce its tracks either.
        currentOnAudioAvailabilityChanged(player.currentTracks.hasAudio())
        onDispose {
            player.removeListener(listener)
            holder.unbindSurface(textureView)
        }
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
        if (currentSource is VideoSource.Recording) player.seekTo(seek.positionMs)
    }

    LaunchedEffect(holder) {
        var steadyPolls = 0
        while (isActive) {
            val steady = player.playbackState == Player.STATE_READY && player.isPlaying
            steadyPolls = if (steady) steadyPolls + 1 else 0
            if (steadyPolls >= STEADY_PLAYBACK_POLLS_TO_TRUST) {
                renderedGeneration = holder.coldStartGeneration
                holder.onSurfaceRenderedFrame(textureView)
                bridgeFrame = null
            }
            if (currentSource is VideoSource.Recording && player.playbackState == Player.STATE_READY) {
                currentOnPositionChanged(player.currentPosition)
            }
            delay(POSITION_POLL_INTERVAL_MS)
        }
    }
}

/** An audio track the player both found and can decode — a track it merely knows about but can't play is no use to a mute button. */
private fun Tracks.hasAudio(): Boolean = isTypeSupported(C.TRACK_TYPE_AUDIO)

/** MediaCodec has shipped a software Opus decoder since Android 5.0, so both of go2rtc's usual HLS audio codecs play. */
actual val liveAudioCodecs: List<String> = listOf("aac", "opus")

private fun AspectRatioFrameLayout.applyVideoSize(videoSize: VideoSize) {
    setAspectRatio(
        if (videoSize.height == 0 || videoSize.width == 0) {
            0f
        } else {
            videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
        },
    )
}

/**
 * Ties a pool lease to a `remember` slot, releasing it whether the composition forgets it or
 * abandons it before it ever applied — a plain DisposableEffect would leak the reference count
 * in the latter case.
 */
private class HolderLease(context: Context, key: String?) : RememberObserver {
    val holder: LivePlayerHolder = LivePlayerPool.acquire(context, key)

    override fun onRemembered() = Unit
    override fun onForgotten() = LivePlayerPool.release(holder)
    override fun onAbandoned() = LivePlayerPool.release(holder)
}
