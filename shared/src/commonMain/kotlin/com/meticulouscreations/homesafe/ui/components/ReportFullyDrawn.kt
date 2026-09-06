package com.meticulouscreations.homesafe.ui.components

import androidx.compose.runtime.Composable

/**
 * Tells the platform the screen is meaningfully complete once [predicate] first returns true —
 * what Android's time-to-fully-drawn startup metric (Macrobenchmark `StartupTimingMetric`,
 * Play Vitals) is measured against. Without it TTFD falls back to the first frame, which for
 * this app is an empty scaffold. Reported at most once per Activity; later calls are ignored.
 * A no-op on platforms without the concept.
 */
@Composable
expect fun ReportFullyDrawnWhen(predicate: () -> Boolean)
