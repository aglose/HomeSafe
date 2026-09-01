package com.meticulouscreations.homesafe.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * go2rtc mints a new, short-lived HLS session (and session id embedded in the playlist/segment
 * URLs) on every request to the top-level `stream.m3u8` playlist. Once that session ages out,
 * in-flight requests for its playlist or segments start returning HTTP 404, which ExoPlayer
 * surfaces as a fatal [PlaybackException] ("Source error" / `InvalidResponseCodeException`).
 * Without recovering from that error, the player is left frozen on the last decoded frame,
 * which is exactly what "streams look like static images" turned out to be — confirmed via
 * `adb logcat` showing `ExoPlayerImplInternal: Playback error ... Response code: 404` shortly
 * after playback started, with no further requests to the server afterward.
 *
 * Restarting playback against the same [streamUrl] makes ExoPlayer re-fetch the top-level
 * playlist, and go2rtc mints a fresh session in response — so recovering from any playback
 * error just means re-preparing with the same [MediaItem].
 */
@Composable
actual fun CameraStreamPlayer(streamUrl: String, modifier: Modifier) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val exoPlayer = remember(streamUrl) {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = ExoPlayer.REPEAT_MODE_OFF
        }
    }

    DisposableEffect(exoPlayer, streamUrl) {
        fun startPlayback() {
            exoPlayer.setMediaItem(MediaItem.fromUri(streamUrl))
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }

        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // Give go2rtc a brief moment before minting a new session, then restart.
                coroutineScope.launch {
                    delay(1_000)
                    startPlayback()
                }
            }
        }
        exoPlayer.addListener(listener)
        startPlayback()

        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            }
        },
    )
}
