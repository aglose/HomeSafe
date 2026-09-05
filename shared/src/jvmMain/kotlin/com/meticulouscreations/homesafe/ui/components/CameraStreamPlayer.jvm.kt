package com.meticulouscreations.homesafe.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun CameraStreamPlayer(
    request: PlayerRequest,
    modifier: Modifier,
    playerKey: String?,
    onPositionChanged: (positionMs: Long) -> Unit,
    onBufferingChanged: (isBuffering: Boolean) -> Unit,
    onPlaybackEnded: () -> Unit,
    onPlaybackError: () -> Unit,
    onAudioAvailabilityChanged: (hasAudio: Boolean) -> Unit,
) {
    LiveViewUnavailablePlaceholder(modifier)
}

/** No live player here yet, so no audio either. */
actual val liveAudioCodecs: List<String> = emptyList()
