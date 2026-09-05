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
     * What to show over the video surface until it has rendered a frame for this source: a
     * still that is, or approximates, the first frame — see [VideoPosterLayer]. Null shows nothing.
     */
    val posterUrl: String?

    /**
     * go2rtc's live HLS stream. While no video frame has been decoded yet (cold start, or a
     * reconnect after an error), platforms that support it show [posterUrl] — Frigate's
     * latest-snapshot endpoint, fetched fresh and refreshed once a second by [VideoPosterLayer]
     * — instead of a black box or a stale picture.
     */
    @Immutable
    data class Live(override val url: String, override val posterUrl: String? = null) : VideoSource {
        override val headers: Map<String, String> get() = emptyMap()
    }

    /**
     * A seekable Frigate VOD playlist, opened at [startPositionMs] into the playlist. [posterUrl]
     * is typically Frigate's recording snapshot at that moment (see `frigateRecordingSnapshotUrl`):
     * a real frame of what's about to play, on screen long before the playlist and its first
     * segment arrive.
     */
    @Immutable
    data class Recording(
        override val url: String,
        override val headers: Map<String, String>,
        val startPositionMs: Long,
        override val posterUrl: String? = null,
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
 * @param playerKey identifies the underlying native player to bind to. Callers that pass the
 *   same key — the grid card and the detail screen both pass the camera's name — share one
 *   long-lived player that survives navigation between them and keeps its last frame, so moving
 *   between the two, switching tabs and back, or returning to the app is instant instead of a
 *   fresh connect. Null gets a private player that lives exactly as long as this composable.
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
    playerKey: String? = null,
    onPositionChanged: (positionMs: Long) -> Unit = {},
    onBufferingChanged: (isBuffering: Boolean) -> Unit = {},
    onPlaybackEnded: () -> Unit = {},
    onPlaybackError: () -> Unit = {},
)

/** Plays a camera's live HLS stream, nothing more. */
@Composable
fun CameraStreamPlayer(
    streamUrl: String,
    modifier: Modifier = Modifier,
    posterUrl: String? = null,
    playerKey: String? = null,
) {
    val request = remember(streamUrl, posterUrl) { PlayerRequest(VideoSource.Live(streamUrl, posterUrl)) }
    CameraStreamPlayer(request = request, modifier = modifier, playerKey = playerKey)
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
