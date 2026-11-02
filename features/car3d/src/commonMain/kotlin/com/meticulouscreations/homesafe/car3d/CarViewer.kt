package com.meticulouscreations.homesafe.car3d

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode

/**
 * The car in 3D: drag to turn it, pinch or scroll to zoom. Its picture stands in until the first
 * frame is up, and for good where the GPU can't be had (previews, a desktop that isn't a Mac).
 */
@Composable
fun CarViewer(model: CarModel, modifier: Modifier = Modifier, state: CarOrbitState = rememberCarOrbitState()) {
    var drawing by remember(model) { mutableStateOf(false) }
    val live = !LocalInspectionMode.current && carDrawsLive
    Box(modifier) {
        if (live) {
            // Keyed by model: a new model gets a new surface, so its first frame is reported again.
            key(model) {
                CarSurface(model, state, onFirstFrame = { drawing = true }, Modifier.fillMaxSize())
            }
        }
        if (!drawing) {
            CarPoster(model, Modifier.fillMaxSize())
        }
        // Over the platform's view rather than round it, so its touches come to Compose first everywhere.
        Box(Modifier.fillMaxSize().carOrbitGestures(state))
    }
}

/** What the viewer's page is drawn on, the dark of a showroom past its pool of light. */
internal const val CAR_BACKDROP_RED = 0.055f
internal const val CAR_BACKDROP_GREEN = 0.06f
internal const val CAR_BACKDROP_BLUE = 0.07f
val CarBackdrop = Color(CAR_BACKDROP_RED, CAR_BACKDROP_GREEN, CAR_BACKDROP_BLUE)

/** Whether this machine can draw the car live at all. */
internal expect val carDrawsLive: Boolean

/** Draws the car on the platform's own GL surface, redrawing when [orbit] changes and only then. */
@Composable
internal expect fun CarSurface(model: CarModel, orbit: CarOrbitState, onFirstFrame: () -> Unit, modifier: Modifier = Modifier)
