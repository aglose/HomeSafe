package com.meticulouscreations.homesafe.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun CameraStreamPlayer(streamUrl: String, modifier: Modifier) {
    LiveViewUnavailablePlaceholder(modifier)
}
