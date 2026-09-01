package com.meticulouscreations.homesafe.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** Plays a camera's live HLS stream. */
@Composable
expect fun CameraStreamPlayer(streamUrl: String, modifier: Modifier = Modifier)

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
