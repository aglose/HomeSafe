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
     * A camera's live stream. [url] is go2rtc's HLS playlist for it — the identity of the
     * source (two `Live`s with the same [url] are the same stream, whatever else differs) and
     * what plays when WebRTC isn't available. [webRtc], when set, is the lower-latency way to
     * the same video: platforms with a WebRTC engine try it first and fall back to [url] (see
     * `LiveTransportMemory`); the others ignore it. While no video frame has been decoded yet
     * (cold start, or a reconnect after an error), platforms that support it show [posterUrl] —
     * Frigate's latest-snapshot endpoint, fetched fresh and refreshed once a second by
     * [VideoPosterLayer] — instead of a black box or a stale picture.
     */
    @Immutable
    data class Live(
        override val url: String,
        override val posterUrl: String? = null,
        val webRtc: WebRtcEndpoint? = null,
    ) : VideoSource {
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
 * How to negotiate a WebRTC session for a live stream: where the SDP offer goes ([signalingUrl],
 * see `frigateWebRtcSignalingUrl`) and whether to ask for the camera's audio track at all. The
 * grid passes `audio = false` — several silent players at once should cost as little as
 * possible — and the single-camera view asks for sound.
 */
@Immutable
data class WebRtcEndpoint(val signalingUrl: String, val audio: Boolean)

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
    /**
     * Silence the source's audio track, if it has one. Muted by default: the grid runs several
     * cameras at once, and a clip that starts talking unasked is a jump scare — the detail
     * screen's speaker button is where the user opts in.
     */
    val muted: Boolean = true,
)

/**
 * How far a live source has got towards showing moving video — what a "Live" indicator keys off.
 *
 * The three states are the same on every platform because both real players already track the two
 * bits they are made of: whether this surface has decoded a frame for the player's current cold
 * start (the bit that drives the poster overlay), and whether the player is currently starved.
 */
enum class LiveStreamStatus {
    /** Nothing on screen yet for this connection: a cold connect, or recovering from an error. */
    Connecting,

    /** Video arrived and then the player ran dry — connected, but stalled waiting for data. */
    Buffering,

    /** Frames are arriving and playing. */
    Live,
}

/**
 * The audio codecs this platform's live player can decode out of go2rtc's fMP4 HLS, most
 * preferred first — what [com.meticulouscreations.homesafe.network.frigateLiveStreamUrl] asks
 * go2rtc to include. Empty means video only. Android's MediaCodec has shipped an Opus decoder
 * since 5.0; AVFoundation plays only AAC (and friends) inside HLS, so an Opus-only camera is
 * silent on iOS until go2rtc is told to publish an AAC track as well (`#audio=aac`).
 */
expect val liveAudioCodecs: List<String>

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
 * @param onStreamStatusChanged how far this source has got towards playing — see [LiveStreamStatus].
 *   Reported only when it changes.
 * @param onPlaybackEnded the current [VideoSource.Recording] reached its end.
 * @param onPlaybackError the current [VideoSource.Recording] failed and will not recover on its own.
 *   Live sources recover from errors internally (see the platform implementations) and never report here.
 * @param onAudioAvailabilityChanged whether what's playing carries an audio track this platform
 *   can decode — false for go2rtc's video-only sub-streams, for recordings Frigate saved without
 *   audio, and for codecs the platform can't play. What a mute button should key its enabled state on.
 */
@Composable
expect fun CameraStreamPlayer(
    request: PlayerRequest,
    modifier: Modifier = Modifier,
    playerKey: String? = null,
    onPositionChanged: (positionMs: Long) -> Unit = {},
    onBufferingChanged: (isBuffering: Boolean) -> Unit = {},
    onStreamStatusChanged: (status: LiveStreamStatus) -> Unit = {},
    onPlaybackEnded: () -> Unit = {},
    onPlaybackError: () -> Unit = {},
    onAudioAvailabilityChanged: (hasAudio: Boolean) -> Unit = {},
)

/**
 * Plays a camera's live stream without sound, reporting only how far it has got — see
 * [LiveStreamStatus]. [webRtcSignalingUrl], when given, lets platforms with a WebRTC engine
 * join over that instead of [streamUrl]'s HLS; the grid's silent cards are the caller.
 */
@Composable
fun CameraStreamPlayer(
    streamUrl: String,
    modifier: Modifier = Modifier,
    posterUrl: String? = null,
    webRtcSignalingUrl: String? = null,
    playerKey: String? = null,
    onStreamStatusChanged: (status: LiveStreamStatus) -> Unit = {},
) {
    val request = remember(streamUrl, posterUrl, webRtcSignalingUrl) {
        val webRtc = webRtcSignalingUrl?.let { WebRtcEndpoint(it, audio = false) }
        PlayerRequest(VideoSource.Live(streamUrl, posterUrl, webRtc))
    }
    CameraStreamPlayer(
        request = request,
        modifier = modifier,
        playerKey = playerKey,
        onStreamStatusChanged = onStreamStatusChanged,
    )
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
