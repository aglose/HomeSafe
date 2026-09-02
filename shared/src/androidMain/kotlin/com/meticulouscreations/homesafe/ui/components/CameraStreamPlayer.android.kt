package com.meticulouscreations.homesafe.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * One [ExoPlayer] lives for as long as this composable does; changing [PlayerRequest.source]
 * swaps the media source in place rather than rebuilding the player, so switching between live
 * and a recording (or between two recording playlists) never re-attaches the video surface.
 *
 * Live-stream recovery: go2rtc mints a new, short-lived HLS session (and session id embedded in
 * the playlist/segment URLs) on every request to the top-level `stream.m3u8` playlist. Once that
 * session ages out, in-flight requests for its playlist or segments start returning HTTP 404,
 * which ExoPlayer surfaces as a fatal [PlaybackException] ("Source error" /
 * `InvalidResponseCodeException`). Without recovering from that error, the player is left frozen
 * on the last decoded frame — confirmed via `adb logcat` showing
 * `ExoPlayerImplInternal: Playback error ... Response code: 404` shortly after playback started.
 * Re-preparing the same live URL makes ExoPlayer re-fetch the top-level playlist, and go2rtc
 * mints a fresh session in response. Recordings are plain VOD playlists and get no such retry:
 * an error there is reported to the caller instead.
 */
@Composable
actual fun CameraStreamPlayer(
    request: PlayerRequest,
    modifier: Modifier,
    onPositionChanged: (positionMs: Long) -> Unit,
    onBufferingChanged: (isBuffering: Boolean) -> Unit,
    onPlaybackEnded: () -> Unit,
    onPlaybackError: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val exoPlayer = remember { ExoPlayer.Builder(context).build() }

    val source = request.source
    val currentSource by rememberUpdatedState(source)
    val currentOnPositionChanged by rememberUpdatedState(onPositionChanged)
    val currentOnBufferingChanged by rememberUpdatedState(onBufferingChanged)
    val currentOnPlaybackEnded by rememberUpdatedState(onPlaybackEnded)
    val currentOnPlaybackError by rememberUpdatedState(onPlaybackError)

    DisposableEffect(exoPlayer) {
        fun restartLive(failedSource: VideoSource) {
            coroutineScope.launch {
                // Give go2rtc a brief moment before minting a new session, then restart —
                // unless the user has moved on to a different source in the meantime.
                delay(1_000)
                if (currentSource == failedSource) exoPlayer.load(failedSource)
            }
        }

        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                currentOnBufferingChanged(playbackState == Player.STATE_BUFFERING)
                if (playbackState == Player.STATE_ENDED) {
                    when (val ended = currentSource) {
                        is VideoSource.Live -> restartLive(ended)
                        is VideoSource.Recording -> currentOnPlaybackEnded()
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                when (val failed = currentSource) {
                    is VideoSource.Live -> restartLive(failed)
                    is VideoSource.Recording -> currentOnPlaybackError()
                }
            }
        }
        exoPlayer.addListener(listener)

        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    LaunchedEffect(source) { exoPlayer.load(source) }

    LaunchedEffect(request.seek) {
        val seek = request.seek ?: return@LaunchedEffect
        if (currentSource is VideoSource.Recording) exoPlayer.seekTo(seek.positionMs)
    }

    LaunchedEffect(request.playWhenReady) { exoPlayer.playWhenReady = request.playWhenReady }

    LaunchedEffect(exoPlayer) {
        while (isActive) {
            if (currentSource is VideoSource.Recording && exoPlayer.playbackState == Player.STATE_READY) {
                currentOnPositionChanged(exoPlayer.currentPosition)
            }
            delay(POSITION_POLL_INTERVAL_MS)
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

private const val POSITION_POLL_INTERVAL_MS = 250L

private fun ExoPlayer.load(source: VideoSource) {
    val dataSourceFactory = DefaultHttpDataSource.Factory().setDefaultRequestProperties(source.headers)
    val mediaSource = HlsMediaSource.Factory(dataSourceFactory).createMediaSource(MediaItem.fromUri(source.url))
    when (source) {
        is VideoSource.Live -> setMediaSource(mediaSource)
        is VideoSource.Recording -> setMediaSource(mediaSource, source.startPositionMs)
    }
    prepare()
}
