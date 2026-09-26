package com.meticulouscreations.homesafe.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset

/** No Android RenderEffect here: the splash's shape without its water (see [circularSplashFallback]). */
actual fun Modifier.splashReveal(progress: () -> Float, originFraction: Offset): Modifier =
    circularSplashFallback(progress, originFraction)
