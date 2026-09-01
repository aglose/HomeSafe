package com.meticulouscreations.homesafe.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSURL
import platform.QuartzCore.CATransaction
import platform.UIKit.UIView

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun CameraStreamPlayer(streamUrl: String, modifier: Modifier) {
    val player = remember(streamUrl) { AVPlayer(uRL = NSURL(string = streamUrl)) }
    val playerLayer = remember(player) {
        AVPlayerLayer().apply {
            this.player = player
            videoGravity = AVLayerVideoGravityResizeAspectFill
        }
    }

    DisposableEffect(player) {
        player.play()
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
