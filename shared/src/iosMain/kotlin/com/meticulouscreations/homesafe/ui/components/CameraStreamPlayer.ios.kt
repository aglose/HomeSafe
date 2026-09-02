package com.meticulouscreations.homesafe.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.AVFoundation.timeControlStatus
import platform.CoreGraphics.CGRectMake
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.CoreMedia.kCMTimeZero
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.QuartzCore.CATransaction
import platform.UIKit.UIView

/**
 * One [AVPlayer] lives for as long as this composable does; changing [PlayerRequest.source]
 * swaps its [AVPlayerItem] in place, so switching between live and a recording never re-attaches
 * the video layer.
 *
 * Buffering, position and failure are polled on a short interval instead of using KVO: it keeps
 * the Kotlin/Native interop trivial, and at 4 Hz the cost is negligible. A failed live item is
 * simply re-created after a moment — go2rtc's short-lived HLS sessions expire with a 404, which
 * AVPlayer treats as fatal, just like ExoPlayer does on Android. A failed recording is reported.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun CameraStreamPlayer(
    request: PlayerRequest,
    modifier: Modifier,
    onPositionChanged: (positionMs: Long) -> Unit,
    onBufferingChanged: (isBuffering: Boolean) -> Unit,
    onPlaybackEnded: () -> Unit,
    onPlaybackError: () -> Unit,
) {
    val player = remember { AVPlayer() }
    val playerLayer = remember(player) {
        AVPlayerLayer().apply {
            this.player = player
            videoGravity = AVLayerVideoGravityResizeAspectFill
        }
    }

    val source = request.source
    val currentSource by rememberUpdatedState(source)
    val currentOnPositionChanged by rememberUpdatedState(onPositionChanged)
    val currentOnBufferingChanged by rememberUpdatedState(onBufferingChanged)
    val currentOnPlaybackEnded by rememberUpdatedState(onPlaybackEnded)
    val currentOnPlaybackError by rememberUpdatedState(onPlaybackError)

    DisposableEffect(source) {
        val item = player.load(source)
        if (request.playWhenReady) player.play()

        val endObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            name = AVPlayerItemDidPlayToEndTimeNotification,
            `object` = item,
            queue = NSOperationQueue.mainQueue,
        ) { _ ->
            when (source) {
                is VideoSource.Live -> player.load(source).also { player.play() }
                is VideoSource.Recording -> currentOnPlaybackEnded()
            }
        }

        onDispose { NSNotificationCenter.defaultCenter.removeObserver(endObserver) }
    }

    LaunchedEffect(request.seek) {
        val seek = request.seek ?: return@LaunchedEffect
        if (currentSource is VideoSource.Recording) {
            player.seekToTime(
                time = CMTimeMakeWithSeconds(seek.positionMs / 1000.0, PREFERRED_TIMESCALE),
                toleranceBefore = kCMTimeZero.readValue(),
                toleranceAfter = kCMTimeZero.readValue(),
            )
        }
    }

    LaunchedEffect(request.playWhenReady) {
        if (request.playWhenReady) player.play() else player.pause()
    }

    LaunchedEffect(player) {
        var failedItem: AVPlayerItem? = null
        while (isActive) {
            val item = player.currentItem
            currentOnBufferingChanged(player.timeControlStatus == AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate)

            if (item != null && item.status == AVPlayerItemStatusFailed && item !== failedItem) {
                failedItem = item
                when (val failed = currentSource) {
                    is VideoSource.Live -> {
                        delay(1_000)
                        if (currentSource == failed && player.currentItem === item) {
                            player.load(failed)
                            player.play()
                        }
                    }
                    is VideoSource.Recording -> currentOnPlaybackError()
                }
            } else if (item != null && currentSource is VideoSource.Recording) {
                val seconds = CMTimeGetSeconds(player.currentTime())
                if (!seconds.isNaN()) currentOnPositionChanged((seconds * 1000).toLong())
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    DisposableEffect(player) {
        onDispose { player.pause() }
    }

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
                layer.addSublayer(playerLayer)
            }
        },
        modifier = modifier,
    )
}

private const val POLL_INTERVAL_MS = 250L
private const val PREFERRED_TIMESCALE = 600

/** AVURLAsset option key for per-request HTTP headers; a string literal because the SDK constant is not exposed to Kotlin/Native. */
private const val HTTP_HEADER_FIELDS_KEY = "AVURLAssetHTTPHeaderFieldsKey"

@OptIn(ExperimentalForeignApi::class)
private fun AVPlayer.load(source: VideoSource): AVPlayerItem {
    val options: Map<Any?, *>? = source.headers.takeIf { it.isNotEmpty() }?.let { mapOf(HTTP_HEADER_FIELDS_KEY to it) }
    val asset = AVURLAsset(uRL = NSURL(string = source.url), options = options)
    val item = AVPlayerItem(asset = asset)
    replaceCurrentItemWithPlayerItem(item)
    if (source is VideoSource.Recording) {
        seekToTime(
            time = CMTimeMakeWithSeconds(source.startPositionMs / 1000.0, PREFERRED_TIMESCALE),
            toleranceBefore = kCMTimeZero.readValue(),
            toleranceAfter = kCMTimeZero.readValue(),
        )
    }
    return item
}
