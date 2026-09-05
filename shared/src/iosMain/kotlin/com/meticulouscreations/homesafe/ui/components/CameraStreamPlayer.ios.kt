package com.meticulouscreations.homesafe.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import androidx.lifecycle.compose.LifecycleStartEffect
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.seekToTime
import platform.AVFoundation.timeControlStatus
import platform.CoreGraphics.CGRectMake
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.CoreMedia.kCMTimeZero
import platform.QuartzCore.CATransaction
import platform.UIKit.UIColor
import platform.UIKit.UIView

private const val POLL_INTERVAL_MS = 250L

/**
 * Binds a [LivePlayerHolder] (pooled per [playerKey], see [LivePlayerPool]) to this call site's
 * own [AVPlayerLayer], and draws a live poster over it until that layer has a real frame.
 *
 * The holder owns the [platform.AVFoundation.AVPlayer], its item, its retry loop and its
 * idle/lifecycle policy; this composable owns only the layer, the poster and the caller's
 * callbacks. Several may bind to one holder at once (the grid card and the detail screen
 * overlap during the shared-element transition) — AVFoundation happily drives many layers from
 * one player.
 *
 * The poster sits ON TOP of the player: Compose Multiplatform renders a `UIKitView` *below* its
 * own Metal layer and punches a transparent hole through everything Compose drew in that region,
 * so anything drawn beneath the interop view is erased, and an empty `AVPlayerLayer` shows the
 * window's default (white) background through that hole. It hides once this layer reports
 * `readyForDisplay` for an item that is itself ready — both checks, because right after a cold
 * restart the layer can still claim readiness for the item that was just replaced — and reappears
 * only when the holder's [LivePlayerHolder.coldStartGeneration] moves on (a cold reconnect, a
 * return from a long background), never across a warm live-to-live swap.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun CameraStreamPlayer(
    request: PlayerRequest,
    modifier: Modifier,
    playerKey: String?,
    onPositionChanged: (positionMs: Long) -> Unit,
    onBufferingChanged: (isBuffering: Boolean) -> Unit,
    onPlaybackEnded: () -> Unit,
    onPlaybackError: () -> Unit,
) {
    val holder = remember(playerKey) { HolderLease(playerKey) }.holder
    val player = holder.player

    val source = request.source
    val currentSource by rememberUpdatedState(source)
    val currentOnPositionChanged by rememberUpdatedState(onPositionChanged)
    val currentOnBufferingChanged by rememberUpdatedState(onBufferingChanged)
    val currentOnPlaybackEnded by rememberUpdatedState(onPlaybackEnded)
    val currentOnPlaybackError by rememberUpdatedState(onPlaybackError)

    var renderedGeneration by remember(holder) { mutableIntStateOf(-1) }
    val posterVisible = renderedGeneration != holder.coldStartGeneration

    val playerLayer = remember(holder) {
        AVPlayerLayer().apply {
            this.player = player
            videoGravity = AVLayerVideoGravityResizeAspectFill
            backgroundColor = UIColor.clearColor.CGColor
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
        onDispose {
            holder.removeListener(listener)
            playerLayer.player = null
        }
    }

    LifecycleStartEffect(holder) {
        holder.onBinderStarted()
        onStopOrDispose { holder.onBinderStopped() }
    }

    LaunchedEffect(holder, source.url) { holder.load(source) }

    LaunchedEffect(holder, request.playWhenReady) { holder.setPlayWhenReady(request.playWhenReady) }

    LaunchedEffect(holder, request.seek) {
        val seek = request.seek ?: return@LaunchedEffect
        if (currentSource is VideoSource.Recording) {
            player.seekToTime(
                time = CMTimeMakeWithSeconds(seek.positionMs / 1000.0, PREFERRED_TIMESCALE),
                toleranceBefore = kCMTimeZero.readValue(),
                toleranceAfter = kCMTimeZero.readValue(),
            )
        }
    }

    LaunchedEffect(holder) {
        while (isActive) {
            delay(POLL_INTERVAL_MS)
            val item = player.currentItem
            if (playerLayer.readyForDisplay && item?.status == AVPlayerItemStatusReadyToPlay) {
                renderedGeneration = holder.coldStartGeneration
            }
            currentOnBufferingChanged(player.timeControlStatus == AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate)
            if (currentSource is VideoSource.Recording && item != null) {
                val seconds = CMTimeGetSeconds(player.currentTime())
                if (!seconds.isNaN()) currentOnPositionChanged((seconds * 1000).toLong())
            }
        }
    }

    Box(modifier = modifier) {
        UIKitView(
            factory = {
                object : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
                    override fun layoutSubviews() {
                        super.layoutSubviews()
                        CATransaction.begin()
                        CATransaction.setDisableActions(true)
                        playerLayer.frame = bounds
                        CATransaction.commit()
                    }
                }.apply {
                    backgroundColor = UIColor.clearColor
                    layer.addSublayer(playerLayer)
                }
            },
            modifier = Modifier.fillMaxSize(),
            // The video surface never needs touch input. Left interactive (the default), the
            // interop view swallows taps, so a `clickable` on the surrounding card never fires.
            properties = UIKitInteropProperties(interactionMode = null),
        )

        val posterUrl = source.posterUrl
        if (posterVisible && posterUrl != null) {
            VideoPosterLayer(posterUrl = posterUrl, refresh = source is VideoSource.Live, modifier = Modifier.fillMaxSize())
        }
    }
}

/** Ties a pool lease to a `remember` slot so it's released on forget *and* on abandon. */
private class HolderLease(key: String?) : RememberObserver {
    val holder: LivePlayerHolder = LivePlayerPool.acquire(key)

    override fun onRemembered() = Unit
    override fun onForgotten() = LivePlayerPool.release(holder)
    override fun onAbandoned() = LivePlayerPool.release(holder)
}
