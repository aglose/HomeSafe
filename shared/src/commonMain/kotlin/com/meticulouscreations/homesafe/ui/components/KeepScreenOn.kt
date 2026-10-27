package com.meticulouscreations.homesafe.ui.components

import androidx.compose.runtime.Composable

/**
 * Keeps the screen from dimming and locking for as long as this is in the composition, and lets
 * it sleep again when it leaves: a camera someone is watching shouldn't go dark under them.
 * Call it only while the video is actually playing (`if (playing) KeepScreenOn()`), so a paused
 * picture lets the phone sleep as usual. Counted, so two players asking at once (a camera page
 * under the clip editor) don't release each other's hold.
 */
@Composable
expect fun KeepScreenOn()
