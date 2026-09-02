package com.meticulouscreations.homesafe.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** What the player should be playing. */
@Immutable
sealed interface VideoSource {
    val url: String

    /** HTTP headers to send with every request for this source (playlists and segments alike). */
    val headers: Map<String, String>

    /**
     * go2rtc's live HLS stream. While no video frame has been decoded yet (cold start, or a
     * reconnect after an error), platforms that support it show [posterUrl] — Frigate's cached
     * latest-snapshot image — instead of a black or white box.
     */
    @Immutable
    data class Live(override val url: String, val posterUrl: String? = null) : VideoSource {
        override val headers: Map<String, String> get() = emptyMap()
    }

    /** A seekable Frigate VOD playlist, opened at [startPositionMs] into the playlist. */
    @Immutable
    data class Recording(
        override val url: String,
        override val headers: Map<String, String>,
        val startPositionMs: Long,
    ) : VideoSource
}

/**
 * An in-place seek within the current [VideoSource.Recording]. [id] makes every request distinct,
 * so seeking twice to the same position still fires twice.
 */
@Immutable
data class SeekCommand(val id: Long, val positionMs: Long)

/** Everything the player needs to know, as one immutable value the state holder replaces wholesale. */
@Immutable
data class PlayerRequest(
    val source: VideoSource,
    val seek: SeekCommand? = null,
    val playWhenReady: Boolean = true,
)

/**
 * Plays a camera's live stream or a recording, reporting playback progress back to its caller.
 *
 * @param onPositionChanged playback position (ms into the current source) while playing a recording.
 * @param onBufferingChanged true while the player is stalled waiting for data.
 * @param onPlaybackEnded the current [VideoSource.Recording] reached its end.
 * @param onPlaybackError the current [VideoSource.Recording] failed and will not recover on its own.
 *   Live sources recover from errors internally (see the platform implementations) and never report here.
 */
@Composable
expect fun CameraStreamPlayer(
    request: PlayerRequest,
    modifier: Modifier = Modifier,
    onPositionChanged: (positionMs: Long) -> Unit = {},
    onBufferingChanged: (isBuffering: Boolean) -> Unit = {},
    onPlaybackEnded: () -> Unit = {},
    onPlaybackError: () -> Unit = {},
)

/** Plays a camera's live HLS stream, nothing more. */
@Composable
fun CameraStreamPlayer(streamUrl: String, modifier: Modifier = Modifier, posterUrl: String? = null) {
    val request = remember(streamUrl, posterUrl) { PlayerRequest(VideoSource.Live(streamUrl, posterUrl)) }
    CameraStreamPlayer(request = request, modifier = modifier)
}

@Composable
internal fun LiveViewUnavailablePlaceholder(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = "Live view not yet available on this platform",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
