package com.meticulouscreations.homesafe.car3d

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.car_model_tilt_down
import homesafe.shared.generated.resources.car_model_tilt_up
import homesafe.shared.generated.resources.car_model_turn_left
import homesafe.shared.generated.resources.car_model_turn_right
import homesafe.shared.generated.resources.car_model_zoom_in
import homesafe.shared.generated.resources.car_model_zoom_out
import org.jetbrains.compose.resources.stringResource

/**
 * The car in 3D: drag to turn it, pinch or scroll to zoom, or use the keyboard or a screen reader's
 * actions, which make the same moves. Its picture stands in until the first
 * frame is up, and for good where the GPU can't be had (previews, a desktop that isn't a Mac).
 */
@Composable
fun CarViewer(model: CarModel, modifier: Modifier = Modifier, state: CarOrbitState = rememberCarOrbitState()) {
    var drawing by remember(model) { mutableStateOf(false) }
    val live = !LocalInspectionMode.current && carDrawsLive
    val keys = remember { FocusRequester() }
    LaunchedEffect(keys) { keys.requestFocus() }
    val turnLeft = stringResource(Res.string.car_model_turn_left)
    val turnRight = stringResource(Res.string.car_model_turn_right)
    val tiltUp = stringResource(Res.string.car_model_tilt_up)
    val tiltDown = stringResource(Res.string.car_model_tilt_down)
    val zoomIn = stringResource(Res.string.car_model_zoom_in)
    val zoomOut = stringResource(Res.string.car_model_zoom_out)
    Box(
        modifier.semantics {
            customActions = listOf(
                CustomAccessibilityAction(turnLeft) { state.turn(CAR_TURN_STEP, 0f).let { true } },
                CustomAccessibilityAction(turnRight) { state.turn(-CAR_TURN_STEP, 0f).let { true } },
                CustomAccessibilityAction(tiltUp) { state.turn(0f, CAR_TILT_STEP).let { true } },
                CustomAccessibilityAction(tiltDown) { state.turn(0f, -CAR_TILT_STEP).let { true } },
                CustomAccessibilityAction(zoomIn) { state.zoomBy(CAR_ZOOM_STEP).let { true } },
                CustomAccessibilityAction(zoomOut) { state.zoomBy(1f / CAR_ZOOM_STEP).let { true } },
            )
        },
    ) {
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
        Box(
            Modifier
                .fillMaxSize()
                .clearAndSetSemantics {} // a screen reader has the viewer itself, and its actions
                .focusRequester(keys)
                .onKeyEvent { it.type == KeyEventType.KeyDown && state.onKey(it.key) }
                .focusable()
                .carOrbitGestures(state),
        )
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
