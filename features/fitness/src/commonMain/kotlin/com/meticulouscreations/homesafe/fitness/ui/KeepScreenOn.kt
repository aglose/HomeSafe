package com.meticulouscreations.homesafe.fitness.ui

import androidx.compose.runtime.Composable

/**
 * Keeps the screen from timing out for as long as this is in the composition, and lets it time
 * out again when it leaves: a workout is read between sets with the phone lying on the bench,
 * and a screen gone dark would also let the heart-rate sensor go. See `KeepScreenOn.<platform>.kt`.
 */
@Composable
internal expect fun KeepScreenOn()
