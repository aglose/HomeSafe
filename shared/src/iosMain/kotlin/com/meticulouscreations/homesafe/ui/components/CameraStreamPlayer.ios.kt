package com.meticulouscreations.homesafe.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import coil3.compose.AsyncImage
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemFailedToPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemPlaybackStalledNotification
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.automaticallyWaitsToMinimizeStalling
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.error
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.preferredForwardBufferDuration
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
import platform.UIKit.UIColor
import platform.UIKit.UIView
import kotlin.math.min
import kotlin.math.pow

/**
 * One [AVPlayer] lives for as long as [VideoSource.url] stays the same; changing it (switching
 * between live and a recording, or between two recording playlists) rebuilds the player, while an
 * in-place seek within the current recording ([PlayerRequest.seek]) reuses it.
 *
 * go2rtc mints a new, short-lived HLS session per top-level playlist request; once it expires,
 * live playback fails. `AVPlayerItem.status` and `AVPlayerLayer.readyForDisplay` are KVO-only with
 * no notification equivalent, and this codebase's existing iOS interop
 * (`BiometricCredentialStore.ios.kt`) favors block-based APIs over raw KVO/NSObject subclassing —
 * so recovery here polls plain, directly-readable properties (`error`, `timeControlStatus`,
 * `currentTime`) on a short interval instead, combined with NotificationCenter observation for
 * end/stall/failure events. Live recovery replaces just the failed [AVPlayerItem] (not the whole
 * [AVPlayer]/[AVPlayerLayer]), with exponential backoff and a retry cap so a genuinely offline
 * server doesn't get polled/retried forever. Recordings are plain VOD playlists and get no such
 * retry: an error there is reported to the caller instead.
 *
 * A live source's poster (Frigate's cached snapshot) is drawn ON TOP of the player, not
 * underneath it. Compose Multiplatform renders a `UIKitView` *below* its own Metal layer and
 * punches a transparent hole through everything Compose drew in that region to reveal the native
 * view — so anything Compose draws beneath the interop view is erased, and while the
 * `AVPlayerLayer` has no frame yet the hole shows the `UIWindow`'s default (white) background.
 * That is exactly the white/black box seen on every fresh start of this composable (cold start,
 * and each time the grid is recreated after coming back from the detail screen). The poster
 * therefore sits above the player and hides itself only once `AVPlayerLayer.readyForDisplay`
 * reports a real frame is being shown; replacing the item on recovery flips that back to false,
 * so the poster returns until frames resume.
 */
private const val POLL_INTERVAL_MS = 250L
private const val MAX_CONSECUTIVE_FAILURES = 6
private const val BASE_RETRY_DELAY_MS = 1_000L
private const val MAX_RETRY_DELAY_MS = 30_000L
private const val PREFERRED_FORWARD_BUFFER_SECONDS = 3.0
private const val PREFERRED_TIMESCALE = 600

/** AVURLAsset option key for per-request HTTP headers; a string literal because the SDK constant is not exposed to Kotlin/Native. */
private const val HTTP_HEADER_FIELDS_KEY = "AVURLAssetHTTPHeaderFieldsKey"

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
    val coroutineScope = rememberCoroutineScope()
    val source = request.source
    var posterVisible by remember(source.url) { mutableStateOf(true) }

    val currentSource by rememberUpdatedState(source)
    val currentOnPositionChanged by rememberUpdatedState(onPositionChanged)
    val currentOnBufferingChanged by rememberUpdatedState(onBufferingChanged)
    val currentOnPlaybackEnded by rememberUpdatedState(onPlaybackEnded)
    val currentOnPlaybackError by rememberUpdatedState(onPlaybackError)

    val player = remember(source.url) {
        AVPlayer().apply { automaticallyWaitsToMinimizeStalling = false }
    }
    val playerLayer = remember(player) {
        AVPlayerLayer().apply {
            this.player = player
            videoGravity = AVLayerVideoGravityResizeAspectFill
            backgroundColor = UIColor.clearColor.CGColor
        }
    }

    DisposableEffect(player) {
        var consecutiveFailures = 0
        var retryScheduled = false
        var reportedErrorForItem: AVPlayerItem? = null
        val notificationCenter = NSNotificationCenter.defaultCenter
        var endObserver: Any? = null
        var failedObserver: Any? = null
        var stalledObserver: Any? = null

        fun clearObservers() {
            endObserver?.let { notificationCenter.removeObserver(it) }
            failedObserver?.let { notificationCenter.removeObserver(it) }
            stalledObserver?.let { notificationCenter.removeObserver(it) }
            endObserver = null
            failedObserver = null
            stalledObserver = null
        }

        fun newItem(toLoad: VideoSource): AVPlayerItem {
            val options: Map<Any?, *>? = toLoad.headers.takeIf { it.isNotEmpty() }?.let { mapOf(HTTP_HEADER_FIELDS_KEY to it) }
            val asset = AVURLAsset(uRL = NSURL(string = toLoad.url), options = options)
            return AVPlayerItem(asset = asset).apply { preferredForwardBufferDuration = PREFERRED_FORWARD_BUFFER_SECONDS }
        }

        fun registerObservers(item: AVPlayerItem) {
            endObserver = notificationCenter.addObserverForName(
                name = AVPlayerItemDidPlayToEndTimeNotification,
                `object` = item,
                queue = NSOperationQueue.mainQueue,
            ) { _ ->
                when (currentSource) {
                    is VideoSource.Live -> scheduleRetryOnLiveEnd()
                    is VideoSource.Recording -> currentOnPlaybackEnded()
                }
            }
            failedObserver = notificationCenter.addObserverForName(
                AVPlayerItemFailedToPlayToEndTimeNotification,
                item,
                NSOperationQueue.mainQueue,
            ) { onStallOrFailure() }
            stalledObserver = notificationCenter.addObserverForName(
                AVPlayerItemPlaybackStalledNotification,
                item,
                NSOperationQueue.mainQueue,
            ) { onStallOrFailure() }
        }

        fun loadLive(toLoad: VideoSource.Live) {
            val item = newItem(toLoad)
            player.replaceCurrentItemWithPlayerItem(item)
            registerObservers(item)
        }

        fun scheduleRetryOnLiveEnd() {
            val failed = currentSource as? VideoSource.Live ?: return
            if (retryScheduled || consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) return
            retryScheduled = true
            consecutiveFailures++
            val backoffMs = min(
                BASE_RETRY_DELAY_MS * 2.0.pow(consecutiveFailures - 1).toLong(),
                MAX_RETRY_DELAY_MS,
            )
            coroutineScope.launch {
                delay(backoffMs)
                retryScheduled = false
                if (currentSource == failed) {
                    loadLive(failed)
                    player.play()
                }
            }
        }

        fun onStallOrFailure() {
            when (val failed = currentSource) {
                is VideoSource.Live -> scheduleRetryOnLiveEnd()
                is VideoSource.Recording -> if (reportedErrorForItem !== player.currentItem) {
                    reportedErrorForItem = player.currentItem
                    currentOnPlaybackError()
                }
            }
        }

        val initialItem = newItem(source)
        player.replaceCurrentItemWithPlayerItem(initialItem)
        if (source is VideoSource.Recording) {
            player.seekToTime(
                time = CMTimeMakeWithSeconds(source.startPositionMs / 1000.0, PREFERRED_TIMESCALE),
                toleranceBefore = kCMTimeZero.readValue(),
                toleranceAfter = kCMTimeZero.readValue(),
            )
        }
        registerObservers(initialItem)
        if (request.playWhenReady) player.play()

        val pollJob = coroutineScope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                posterVisible = !playerLayer.readyForDisplay
                currentOnBufferingChanged(player.timeControlStatus == AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate)

                val item = player.currentItem
                when (currentSource) {
                    is VideoSource.Live -> {
                        if (item?.error != null) scheduleRetryOnLiveEnd() else if (playerLayer.readyForDisplay) consecutiveFailures = 0
                    }
                    is VideoSource.Recording -> {
                        if (item != null && item.status == AVPlayerItemStatusFailed && reportedErrorForItem !== item) {
                            reportedErrorForItem = item
                            currentOnPlaybackError()
                        } else if (item != null) {
                            val seconds = CMTimeGetSeconds(player.currentTime())
                            if (!seconds.isNaN()) currentOnPositionChanged((seconds * 1000).toLong())
                        }
                    }
                }
            }
        }

        onDispose {
            pollJob.cancel()
            clearObservers()
            player.pause()
        }
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

        // On top of the player, not underneath — see the class-level note. Recordings have no poster.
        val posterUrl = (source as? VideoSource.Live)?.posterUrl
        if (posterVisible && posterUrl != null) {
            AsyncImage(
                model = posterUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
