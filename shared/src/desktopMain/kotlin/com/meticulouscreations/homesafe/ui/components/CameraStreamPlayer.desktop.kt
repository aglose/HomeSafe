package com.meticulouscreations.homesafe.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.lifecycle.compose.LifecycleStartEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode

private const val POLL_INTERVAL_MS = 250L

/**
 * Binds a [DesktopVideoEngine] (pooled per [playerKey], see [DesktopVideoPool]) to this call
 * site, and draws its pictures, stretched to fill the caller's (always 16:9) box as the phones'
 * players do, with the live poster over them until this engine has a picture of its current cold
 * start. Several may bind to one engine at once, and every one gets every picture.
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
    onStreamStats: ((stats: LiveStreamStats?) -> Unit)?,
    reconnectRequests: Int,
    turnDegrees: Float,
) {
    val engine = remember(playerKey) { EngineLease(playerKey) }.engine
    val source = request.source
    val currentSource by rememberUpdatedState(source)
    val currentOnPositionChanged by rememberUpdatedState(onPositionChanged)
    val currentOnBufferingChanged by rememberUpdatedState(onBufferingChanged)
    val currentOnStreamStatusChanged by rememberUpdatedState(onStreamStatusChanged)
    val currentOnPlaybackEnded by rememberUpdatedState(onPlaybackEnded)
    val currentOnPlaybackError by rememberUpdatedState(onPlaybackError)
    val currentOnAudioAvailabilityChanged by rememberUpdatedState(onAudioAvailabilityChanged)
    val currentOnStreamStats by rememberUpdatedState(onStreamStats)

    LifecycleStartEffect(engine) {
        engine.onBinderStarted()
        onStopOrDispose {
            engine.onBinderStopped()
            DesktopVideoPool.stopped(engine)
        }
    }

    LaunchedEffect(engine, source) { engine.load(source) }
    LaunchedEffect(engine, request.playWhenReady) { engine.setPlayWhenReady(request.playWhenReady) }
    LaunchedEffect(engine, request.muted) { engine.setMuted(request.muted) }
    LaunchedEffect(engine, request.seek) {
        val seek = request.seek ?: return@LaunchedEffect
        if (currentSource is VideoSource.Recording) engine.seek(seek.positionMs)
    }

    var reportedStatus by remember(engine) { mutableStateOf<LiveStreamStatus?>(null) }
    OnReconnectRequest(reconnectRequests) {
        if (LivePlaybackPolicy.shouldReconnect(reportedStatus ?: LiveStreamStatus.Connecting, sessionLost = false, msSinceStart = Long.MAX_VALUE)) engine.reconnect()
    }

    // The end and the failure of a recording, once each, and only those that happen while bound here.
    LaunchedEffect(engine) {
        snapshotFlow { engine.ended }.drop(1).collect { if (currentSource is VideoSource.Recording) currentOnPlaybackEnded() }
    }
    LaunchedEffect(engine) {
        snapshotFlow { engine.failed }.drop(1).collect { if (currentSource is VideoSource.Recording) currentOnPlaybackError() }
    }
    LaunchedEffect(engine) { snapshotFlow { engine.hasAudio }.collect { currentOnAudioAvailabilityChanged(it) } }
    LaunchedEffect(engine) { snapshotFlow { engine.positionMs }.collect { if (currentSource is VideoSource.Recording) currentOnPositionChanged(it) } }
    if (onStreamStats != null) {
        LaunchedEffect(engine) { snapshotFlow { engine.stats }.collect { currentOnStreamStats?.invoke(if (currentSource is VideoSource.Live) it else null) } }
    }

    // How far the stream has got, worked out four times a second and reported only when it moves.
    LaunchedEffect(engine) {
        var reportedBuffering: Boolean? = null
        while (isActive) {
            engine.checkStalled()
            val shown = engine.pictureGeneration == engine.coldStartGeneration && engine.picture != null
            val status = when {
                !shown -> LiveStreamStatus.Connecting
                engine.stalled -> LiveStreamStatus.Buffering
                else -> LiveStreamStatus.Live
            }
            if (status != reportedStatus) {
                if (status == LiveStreamStatus.Live && currentSource is VideoSource.Live) markFirstLivePixel(engine)
                reportedStatus = status
                currentOnStreamStatusChanged(status)
            }
            val buffering = engine.stalled
            if (buffering != reportedBuffering) {
                reportedBuffering = buffering
                currentOnBufferingChanged(buffering)
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    Box(modifier = modifier.clipToBounds()) {
        Canvas(modifier = Modifier.fillMaxSize().turned(turnDegrees)) {
            val picture = engine.picture ?: return@Canvas
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawImageRect(
                    picture,
                    Rect.makeWH(picture.width.toFloat(), picture.height.toFloat()),
                    Rect.makeWH(size.width, size.height),
                    SamplingMode.LINEAR,
                    null,
                    true,
                )
            }
        }
        val posterUrl = source.posterUrl
        val posterVisible = engine.pictureGeneration != engine.coldStartGeneration || engine.picture == null
        if (posterVisible && posterUrl != null) {
            VideoPosterLayer(
                posterUrl = posterUrl,
                refresh = source is VideoSource.Live,
                modifier = Modifier.fillMaxSize().turned(turnDegrees),
                lastSeen = (source as? VideoSource.Live)?.lastSeenPoster == true,
            )
        }
    }
}

/** FFmpeg decodes AAC and Opus alike, so go2rtc may send either; Opus first, as on Android. */
actual val liveAudioCodecs: List<String> = listOf("opus", "aac")

/** Every bound surface draws every picture. */
actual val liveSurfaceIsExclusive: Boolean = false

/** Ties a pool lease to a `remember` slot so it's released on forget *and* on abandon. */
private class EngineLease(key: String?) : RememberObserver {
    val engine: DesktopVideoEngine = DesktopVideoPool.acquire(key)

    override fun onRemembered() = Unit
    override fun onForgotten() = DesktopVideoPool.release(engine)
    override fun onAbandoned() = DesktopVideoPool.release(engine)
}

/** The first picture a surface showed for a live source — what the startup timings end on — once per camera. */
private fun markFirstLivePixel(engine: DesktopVideoEngine) {
    LiveStartupMilestones.mark("pixel ${engine.key}")
    LiveStartupMilestones.mark("pixel.hls ${engine.key}")
    LiveStartupMilestones.mark("pixel.first")
}
