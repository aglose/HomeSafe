package com.meticulouscreations.homesafe.ui.components

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.TextureView
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Binds a [LivePlayerHolder] (pooled per [playerKey], see [LivePlayerPool]) to this call site's
 * own surfaces, and draws a live poster over them until one has a real frame.
 *
 * The holder owns the players, its source, its retry loop and its idle/lifecycle policy; this
 * composable owns only what's specific to one place on screen: the surfaces, the poster, and
 * the caller's callbacks. Several of these can be bound to one holder at the same time (the
 * grid card and the detail screen overlap during the shared-element transition).
 *
 * Two surfaces, stacked, one per engine the holder can play through ([LivePlayerHolder.transport]):
 *
 *  - Underneath, a bare `TextureView` for HLS. ExoPlayer draws to whichever binder's was bound
 *    most recently, and each keeps showing its last frame after it stops receiving them.
 *  - On top, a [WebRtcTextureRenderer], created only for a source that carries a WebRTC
 *    endpoint. It's a sink on the peer's video track, so every binder draws every frame — no
 *    hand-over between binders to bridge. It's transparent until it has drawn, and is cleared
 *    again whenever the holder goes back to HLS, so the HLS surface shows through.
 *
 * The video is stretched to fill whatever box the caller gives it — no letterboxing, no
 * aspect-ratio crop — and so is the poster (see [VideoPosterLayer]). Every box in this app is a
 * fixed 16:9 frame, and the decoded frame's own pixel aspect is not a reliable guide to how it
 * should be shown: a camera's low-bitrate sub-stream is often an anamorphic squeeze of its full
 * 16:9 view (the Amcrest's 704x480 covers exactly the same field of view as its 2960x1668 main
 * stream), so fitting *that* by its pixel aspect crops the top and bottom off — and then, because
 * one pooled player swaps between the grid's sub-stream and the detail screen's full stream, the
 * picture re-cropped every time it changed quality. Filling the box shows both streams with the
 * same geometry, so a quality swap or a reconnect never moves the picture.
 *
 * The poster is a [VideoPosterLayer] drawn *on top* of the video and hidden once a surface here
 * renders its first frame for the holder's current [LivePlayerHolder.coldStartGeneration]. That
 * covers the cases where the surfaces have nothing or something stale to show — a brand-new
 * binder, a cold connect, a reconnect after an error, a return from a long background — with a
 * snapshot that is at most a second old, and leaves a good frame alone across warm swaps.
 *
 * A bare `TextureView` is used rather than [androidx.media3.ui.PlayerView] or libwebrtc's
 * `SurfaceViewRenderer`: a `SurfaceView` punches its own hole in the window and doesn't
 * composite with Compose content above or below it, which is how black boxes used to persist
 * over posters.
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
    onStreamStatusChanged: (status: LiveStreamStatus) -> Unit,
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
    val currentOnStreamStatusChanged by rememberUpdatedState(onStreamStatusChanged)
    val currentOnPlaybackEnded by rememberUpdatedState(onPlaybackEnded)
    val currentOnPlaybackError by rememberUpdatedState(onPlaybackError)
    val currentOnAudioAvailabilityChanged by rememberUpdatedState(onAudioAvailabilityChanged)

    // The cold-start generation a surface here last rendered a frame for; the poster stays up
    // until it catches up with the holder's current one.
    var renderedGeneration by remember(holder) { mutableIntStateOf(-1) }
    val posterVisible = renderedGeneration != holder.coldStartGeneration

    // The two bits behind LiveStreamStatus: nothing decoded yet for this cold start (the poster is
    // still up), and the player starved mid-stream. Reported from an effect rather than straight
    // out of the listener so the caller sees one value per distinct state, not one per event.
    var hlsStarved by remember(holder) { mutableStateOf(false) }
    val transport = holder.transport
    val starved = if (transport == LiveTransport.WEBRTC) holder.webRtcStalled else hlsStarved
    val streamStatus = when {
        posterVisible -> LiveStreamStatus.Connecting
        starved -> LiveStreamStatus.Buffering
        else -> LiveStreamStatus.Live
    }
    LaunchedEffect(streamStatus) { currentOnStreamStatusChanged(streamStatus) }

    val textureView = remember(holder) {
        TextureView(context).apply {
            // Never composite as an opaque (black) layer while there's no frame yet.
            isOpaque = false
        }
    }
    // Only a source that can play over WebRTC gets a renderer (each one owns a render thread);
    // once made it stays for the holder, since the detail screen's source keeps the endpoint.
    val wantsWebRtc = source is VideoSource.Live && source.webRtc != null
    val renderer = if (wantsWebRtc) remember(holder) { RendererLease(context) }.renderer else null

    // The last frame of whichever surface this one took over from (see LivePlayerHolder.bindSurface),
    // shown until this surface has a frame of its own. Cleared on that first frame.
    var bridgeFrame by remember(holder) { mutableStateOf<ImageBitmap?>(null) }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { textureView },
        )
        if (renderer != null) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { renderer },
            )
        }
        val bridge = bridgeFrame
        val posterUrl = source.posterUrl
        if (bridge != null) {
            Image(
                bitmap = bridge,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (posterVisible && posterUrl != null) {
            VideoPosterLayer(posterUrl = posterUrl, refresh = source is VideoSource.Live, modifier = Modifier.fillMaxSize())
        }
    }

    DisposableEffect(holder) {
        bridgeFrame = holder.bindSurface(textureView)?.asImageBitmap()

        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (holder.transport != LiveTransport.HLS) return
                renderedGeneration = holder.coldStartGeneration
                holder.onSurfaceRenderedFrame(textureView)
                bridgeFrame = null
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                hlsStarved = playbackState == Player.STATE_BUFFERING
                if (holder.transport == LiveTransport.HLS) currentOnBufferingChanged(playbackState == Player.STATE_BUFFERING)
                if (playbackState == Player.STATE_ENDED && currentSource is VideoSource.Recording) {
                    currentOnPlaybackEnded()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (currentSource is VideoSource.Recording) currentOnPlaybackError()
            }

            override fun onTracksChanged(tracks: Tracks) {
                if (holder.transport == LiveTransport.HLS) currentOnAudioAvailabilityChanged(tracks.hasAudio())
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            holder.unbindSurface(textureView)
        }
    }

    if (renderer != null) {
        DisposableEffect(holder, renderer) {
            val mainThread = Handler(Looper.getMainLooper())
            // Called on the render thread for every frame; only the first per generation matters,
            // and only that one is hopped to the main thread.
            var renderedFor = -1
            renderer.onFrameRendered = {
                val generation = holder.coldStartGeneration
                if (renderedFor != generation) {
                    renderedFor = generation
                    mainThread.post {
                        if (holder.transport == LiveTransport.WEBRTC) {
                            renderedGeneration = generation
                            bridgeFrame = null
                        }
                    }
                }
            }
            holder.bindRenderer(renderer)
            onDispose {
                holder.unbindRenderer(renderer)
                renderer.onFrameRendered = null
            }
        }

        // Back on HLS: the peer's last frame must not sit on top of the HLS surface.
        LaunchedEffect(renderer, transport) {
            if (transport == LiveTransport.HLS) renderer.clear()
        }
    }

    // A warm holder won't re-announce its tracks, and the peer's audio arrives after adoption.
    LaunchedEffect(holder, transport, holder.webRtcHasAudio) {
        when (transport) {
            LiveTransport.WEBRTC -> currentOnAudioAvailabilityChanged(holder.webRtcHasAudio)
            LiveTransport.HLS -> currentOnAudioAvailabilityChanged(player.currentTracks.hasAudio())
        }
    }
    LaunchedEffect(holder, transport, holder.webRtcStalled) {
        if (transport == LiveTransport.WEBRTC) currentOnBufferingChanged(holder.webRtcStalled)
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
            val steady = holder.transport == LiveTransport.HLS && player.playbackState == Player.STATE_READY && player.isPlaying
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

/** Same idea for the WebRTC renderer, whose render thread must be torn down when this call site goes. */
private class RendererLease(context: Context) : RememberObserver {
    val renderer = WebRtcTextureRenderer(context, WebRtcRuntime.eglContext)

    override fun onRemembered() = Unit
    override fun onForgotten() = renderer.release()
    override fun onAbandoned() = renderer.release()
}
