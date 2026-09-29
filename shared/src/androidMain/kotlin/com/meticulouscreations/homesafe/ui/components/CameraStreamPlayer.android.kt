package com.meticulouscreations.homesafe.ui.components

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
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
 * A binder that arrives while the holder is warm doesn't need the poster at all: it starts with
 * the holder's [LivePlayerHolder.bridgeFrame] — the camera's picture as it is on screen (or was,
 * when the last surface went) — counts it as this generation's frame, and so reports
 * [LiveStreamStatus.Live] from its first composition. The copy is drawn over its surfaces until
 * one of them renders a frame of its own. That is what keeps a live camera looking live when the
 * detail screen opens over its card, when the grid comes back from the detail screen or another
 * tab, and when a card scrolls back into view.
 *
 * A bare `TextureView` is used rather than [androidx.media3.ui.PlayerView] or libwebrtc's
 * `SurfaceViewRenderer`: a `SurfaceView` punches its own hole in the window and doesn't
 * composite with Compose content above or below it, which is how black boxes used to persist
 * over posters.
 */
private const val POSITION_POLL_INTERVAL_MS = 250L
private const val LOG_TAG = "HomeSafeLive"

/** Fallback for hiding the poster if a surface never reports its first frame (an HLS surface swap, a WebRTC renderer's report misattributed): this many polls of steady playback. */
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

    // What the camera is showing elsewhere (or was, when its last surface went), if the holder is
    // warm: taken now, in composition, so this binder's very first frame already has the picture.
    val initialBridge = remember(holder) { holder.bridgeFrame()?.asImageBitmap() }

    // The cold-start generation a surface here last rendered a frame for; the poster stays up
    // until it catches up with the holder's current one. A bridge is a frame of the current one.
    var renderedGeneration by remember(holder) { mutableIntStateOf(if (initialBridge != null) holder.coldStartGeneration else -1) }
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
    val textureView = remember(holder) {
        LiveTextureView(context).apply {
            // Never composite as an opaque (black) layer while there's no frame yet.
            isOpaque = false
        }
    }
    LaunchedEffect(streamStatus) {
        Log.d(LOG_TAG, "${holder.key}: binder ${System.identityHashCode(textureView)} status $streamStatus (transport=$transport)")
        currentOnStreamStatusChanged(streamStatus)
    }
    // Only a binder that may see WebRTC frames gets a renderer (each one owns a render thread):
    // its source carries an endpoint, or the holder is already playing a peer — the detail
    // screen's warm join names only the URL, and must still draw the card's peer. Once made it
    // stays for the holder.
    val wantsWebRtc = (source is VideoSource.Live && source.webRtc != null) || transport == LiveTransport.WEBRTC
    val renderer = if (wantsWebRtc) remember(holder) { RendererLease(context) }.renderer else null

    // Shown over the surfaces until one of them has a frame of its own; cleared on that first frame.
    var bridgeFrame by remember(holder) { mutableStateOf(initialBridge) }
    val currentPlayWhenReady by rememberUpdatedState(request.playWhenReady)

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
        holder.bindSurface(textureView)
        textureView.onDetaching = { holder.keepFrame(textureView) }

        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (holder.transport != LiveTransport.HLS) return
                markFirstLivePixel(holder, currentSource, "hls")
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
            textureView.onDetaching = null
        }
    }

    if (renderer != null) {
        DisposableEffect(holder, renderer) {
            val mainThread = Handler(Looper.getMainLooper())
            // The generation this surface has been seen showing the peer's picture for. Recorded
            // only when the holder is on WebRTC at the moment the surface updates: a swap that
            // lands while the holder is still on HLS is not a frame of the peer's (the renderer's
            // clear is one — see below), and recording it would make every real frame that
            // follows look like a repeat, leaving the poster up for good. That is exactly what
            // happened to grid cards, which bind before their join, while the detail screen,
            // binding to a holder already on WebRTC, was fine.
            //
            // The one exception is a cold join drawing before it has been adopted (the holder's
            // earlyDrawGeneration): its first frame is the picture this generation is waiting for.
            var renderedFor = -1
            val onSurfaceUpdated = Runnable {
                val generation = holder.coldStartGeneration
                val peerDrawing = holder.transport == LiveTransport.WEBRTC || holder.earlyDrawGeneration == generation
                if (!peerDrawing) return@Runnable
                if (renderedFor == generation) return@Runnable
                renderedFor = generation
                renderer.peerFrameGeneration = generation
                markFirstLivePixel(holder, currentSource, "rtc")
                Log.d(LOG_TAG, "${holder.key}: renderer drew its first frame for generation $generation")
                renderedGeneration = generation
                bridgeFrame = null
            }
            renderer.onFrameRendered = {
                // TextureView reports surface updates on the UI thread; hop only if that ever changes.
                if (Looper.myLooper() == Looper.getMainLooper()) onSurfaceUpdated.run() else mainThread.post(onSurfaceUpdated)
            }
            holder.bindRenderer(renderer)
            renderer.onDetaching = { holder.keepFrame(renderer) }
            onDispose {
                holder.unbindRenderer(renderer)
                renderer.onFrameRendered = null
                renderer.onDetaching = null
            }
        }

        // Back on HLS after WebRTC: the peer's last frame must not sit on top of the HLS surface.
        // Only on that transition — a renderer starts transparent, and clearing it on the initial
        // HLS transport swapped a blank frame into the surface for no reason.
        var lastTransport by remember(renderer) { mutableStateOf<LiveTransport?>(null) }
        LaunchedEffect(renderer, transport) {
            if (transport == LiveTransport.HLS && lastTransport == LiveTransport.WEBRTC) renderer.clear()
            lastTransport = transport
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

    // Keyed on the endpoint as well as the URL: the same stream gaining one is a load the holder acts on.
    LaunchedEffect(holder, source.url, (source as? VideoSource.Live)?.webRtc) { holder.load(source) }

    LaunchedEffect(holder, request.playWhenReady) { holder.setPlayWhenReady(request.playWhenReady) }

    LaunchedEffect(holder, request.muted) { holder.setMuted(request.muted) }

    LaunchedEffect(holder, request.seek) {
        val seek = request.seek ?: return@LaunchedEffect
        if (currentSource is VideoSource.Recording) player.seekTo(seek.positionMs)
    }

    LaunchedEffect(holder) {
        var steadyPolls = 0
        while (isActive) {
            // Steady on either engine: HLS ready and playing, or a WebRTC peer that has been adopted
            // (the join waited for its first decoded frame) and isn't stalled. A surface that has
            // still not reported a frame after this many polls is trusted to be showing one anyway,
            // so a missed or misattributed report can't leave the poster up over live video.
            val steady = when (holder.transport) {
                LiveTransport.HLS -> player.playbackState == Player.STATE_READY && player.isPlaying
                LiveTransport.WEBRTC -> !holder.webRtcStalled
            }
            steadyPolls = if (steady) steadyPolls + 1 else 0
            if (steadyPolls >= STEADY_PLAYBACK_POLLS_TO_TRUST && renderedGeneration != holder.coldStartGeneration) {
                Log.d(LOG_TAG, "${holder.key}: trusting steady ${holder.transport} playback for generation ${holder.coldStartGeneration}")
                renderedGeneration = holder.coldStartGeneration
                if (holder.transport == LiveTransport.HLS) holder.onSurfaceRenderedFrame(textureView)
                bridgeFrame = null
            }
            // The same fallback for a bridge: once video is flowing, a missed first-frame report
            // mustn't leave a still over it. Not while paused — then the still is the picture.
            if (steadyPolls >= STEADY_PLAYBACK_POLLS_TO_TRUST && bridgeFrame != null && currentPlayWhenReady) {
                Log.d(LOG_TAG, "${holder.key}: dropping the bridge frame after steady ${holder.transport} playback")
                bridgeFrame = null
            }
            if (currentSource is VideoSource.Recording && player.playbackState == Player.STATE_READY) {
                currentOnPositionChanged(player.currentPosition)
            }
            delay(POSITION_POLL_INTERVAL_MS)
        }
    }
}

/** The first frame a surface drew for a live source — what the startup benchmark times — once per camera, and once per engine. */
private fun markFirstLivePixel(holder: LivePlayerHolder, source: VideoSource, engine: String) {
    if (source !is VideoSource.Live) return
    LiveStartupMilestones.mark("pixel ${holder.key}")
    LiveStartupMilestones.mark("pixel.$engine ${holder.key}")
    LiveStartupMilestones.mark("pixel.first")
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

/** The HLS surface, with the same last-chance hook as [WebRtcTextureRenderer.onDetaching]. */
private class LiveTextureView(context: Context) : TextureView(context) {
    var onDetaching: (() -> Unit)? = null

    override fun onDetachedFromWindow() {
        // Before super: TextureView drops its layer right after this, and the holder reads it.
        onDetaching?.invoke()
        super.onDetachedFromWindow()
    }
}

/** ExoPlayer renders HLS to the one TextureView bound last (see [LivePlayerHolder.bindSurface]). */
actual val liveSurfaceIsExclusive: Boolean = true
