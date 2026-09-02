package com.meticulouscreations.homesafe.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Plays a camera's live HLS stream. While no video frame has been decoded yet (cold start, or a
 * reconnect after an error), platforms that support it show [posterUrl] — Frigate's cached
 * latest-snapshot image — instead of a black or white box.
 */
@Composable
expect fun CameraStreamPlayer(streamUrl: String, modifier: Modifier = Modifier, posterUrl: String? = null)

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
