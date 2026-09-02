package com.meticulouscreations.homesafe.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import coil3.compose.AsyncImage
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemFailedToPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemPlaybackStalledNotification
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.automaticallyWaitsToMinimizeStalling
import platform.AVFoundation.currentItem
import platform.AVFoundation.error
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.preferredForwardBufferDuration
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.QuartzCore.CATransaction
import platform.UIKit.UIColor
import platform.UIKit.UIView
import kotlin.math.min
import kotlin.math.pow

/**
 * go2rtc mints a new, short-lived HLS session per top-level playlist request; once it expires,
 * playback fails. `AVPlayerItem.status` and `AVPlayerLayer.readyForDisplay` are KVO-only with no
 * notification equivalent, and this codebase's existing iOS interop
 * (`BiometricCredentialStore.ios.kt`) favors block-based APIs over raw KVO/NSObject subclassing —
 * so recovery here polls a plain, directly-readable property (`error`) on a short interval
 * instead, combined with NotificationCenter observation for stall/failure events. Recovery
 * replaces just the failed [AVPlayerItem] (not the whole [AVPlayer]/[AVPlayerLayer]), with
 * exponential backoff and a retry cap so a genuinely offline server doesn't get polled/retried
 * forever.
 *
 * [posterUrl] (Frigate's cached snapshot) is drawn ON TOP of the player, not underneath it.
 * Compose Multiplatform renders a `UIKitView` *below* its own Metal layer and punches a
 * transparent hole through everything Compose drew in that region to reveal the native view —
 * so anything Compose draws beneath the interop view is erased, and while the `AVPlayerLayer`
 * has no frame yet the hole shows the `UIWindow`'s default (white) background. That is exactly
 * the white/black box seen on every fresh start of this composable (cold start, and each time
 * the grid is recreated after coming back from the detail screen). The poster therefore sits
 * above the player and hides itself only once `AVPlayerLayer.readyForDisplay` reports a real
 * frame is being shown; replacing the item on recovery flips that back to false, so the poster
 * returns until frames resume.
 */
private const val POLL_INTERVAL_MS = 250L
private const val MAX_CONSECUTIVE_FAILURES = 6
private const val BASE_RETRY_DELAY_MS = 1_000L
private const val MAX_RETRY_DELAY_MS = 30_000L
private const val PREFERRED_FORWARD_BUFFER_SECONDS = 3.0

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun CameraStreamPlayer(streamUrl: String, modifier: Modifier, posterUrl: String?) {
    val coroutineScope = rememberCoroutineScope()
    var posterVisible by remember(streamUrl) { mutableStateOf(true) }

    val player = remember(streamUrl) {
        AVPlayer(uRL = NSURL(string = streamUrl)).apply {
            automaticallyWaitsToMinimizeStalling = false
        }
    }
    val playerLayer = remember(player) {
        AVPlayerLayer().apply {
            this.player = player
            videoGravity = AVLayerVideoGravityResizeAspectFill
            backgroundColor = UIColor.clearColor.CGColor
        }
    }

    DisposableEffect(player, streamUrl) {
        var consecutiveFailures = 0
        var retryScheduled = false
        val notificationCenter = NSNotificationCenter.defaultCenter
        var failedObserver: Any? = null
        var stalledObserver: Any? = null

        fun clearObservers() {
            failedObserver?.let { notificationCenter.removeObserver(it) }
            stalledObserver?.let { notificationCenter.removeObserver(it) }
            failedObserver = null
            stalledObserver = null
        }

        fun scheduleRetry() {
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
                val newItem = AVPlayerItem(uRL = NSURL(string = streamUrl))
                newItem.preferredForwardBufferDuration = PREFERRED_FORWARD_BUFFER_SECONDS
                player.replaceCurrentItemWithPlayerItem(newItem)
                registerObservers(notificationCenter, newItem, onEvent = ::scheduleRetry).let {
                    failedObserver = it.first
                    stalledObserver = it.second
                }
                player.play()
            }
        }

        player.currentItem?.let { item ->
            item.preferredForwardBufferDuration = PREFERRED_FORWARD_BUFFER_SECONDS
            registerObservers(notificationCenter, item, onEvent = ::scheduleRetry).let {
                failedObserver = it.first
                stalledObserver = it.second
            }
        }

        val pollJob = coroutineScope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                posterVisible = !playerLayer.readyForDisplay
                val item = player.currentItem
                if (item?.error != null) {
                    scheduleRetry()
                } else if (playerLayer.readyForDisplay) {
                    consecutiveFailures = 0
                }
            }
        }

        player.play()

        onDispose {
            pollJob.cancel()
            clearObservers()
            player.pause()
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

        // On top of the player, not underneath — see the class-level note.
        if (posterVisible && posterUrl != null) {
            AsyncImage(
                model = posterUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun registerObservers(
    notificationCenter: NSNotificationCenter,
    item: AVPlayerItem,
    onEvent: () -> Unit,
): Pair<Any, Any> {
    val failedObserver = notificationCenter.addObserverForName(
        AVPlayerItemFailedToPlayToEndTimeNotification,
        item,
        NSOperationQueue.mainQueue,
    ) { onEvent() }
    val stalledObserver = notificationCenter.addObserverForName(
        AVPlayerItemPlaybackStalledNotification,
        item,
        NSOperationQueue.mainQueue,
    ) { onEvent() }
    return failedObserver to stalledObserver
}
