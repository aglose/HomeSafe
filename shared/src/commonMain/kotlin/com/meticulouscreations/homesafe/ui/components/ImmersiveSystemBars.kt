package com.meticulouscreations.homesafe.ui.components

import androidx.compose.runtime.Composable

/**
 * Hides the status and navigation bars for as long as this is in the composition, and brings
 * them back when it leaves: a full-screen editor gets every pixel, and a swipe in from the edge
 * shows the bars for a moment without leaving it. A no-op on platforms without system bars to
 * hide (desktop, the web) or where the app doesn't own them (iOS keeps its status bar).
 */
@Composable
expect fun ImmersiveSystemBars()
