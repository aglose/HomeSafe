package com.meticulouscreations.homesafe.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/** No AGSL here: the plain [outlineRunner] rather than Android's Matrix snake. */
internal actual fun Modifier.loadingRunner(
    phase: () -> Float,
    phaseOffset: Float,
    cornerRadius: Dp,
    color: Color,
): Modifier = outlineRunner(phase = phase, phaseOffset = phaseOffset, cornerRadius = cornerRadius, color = color)
