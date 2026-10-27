package com.meticulouscreations.homesafe.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/** No AGSL here: the plain [outlineRunner] rather than Android's Matrix snake, which [phase] alone moves, so [still] has nothing more to hold. */
internal actual fun Modifier.loadingRunner(
    phase: () -> Float,
    phaseOffset: Float,
    cornerRadius: Dp,
    color: Color,
    still: Boolean,
): Modifier = outlineRunner(phase = phase, phaseOffset = phaseOffset, cornerRadius = cornerRadius, color = color)
