package com.meticulouscreations.homesafe.ui.components

import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.runtime.Composable

@Composable
actual fun ReportFullyDrawnWhen(predicate: () -> Boolean) = ReportDrawnWhen(predicate)
