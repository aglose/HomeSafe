package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import com.meticulouscreations.homesafe.atlas.AtlasUiState
import com.meticulouscreations.homesafe.finance.FinanceUiState
import com.meticulouscreations.homesafe.fitness.FitnessUiState
import com.meticulouscreations.homesafe.ui.preview.FrigatePreview
import com.meticulouscreations.homesafe.weather.WeatherUiState

/*
 * The drawer out, over nothing, with each app's card before its data is in. Only the JVM renderer
 * and Studio draw this, so it stays private (see HomePreviews.kt for the ones Layoutlib draws too).
 */

@Preview(name = "Drawer", widthDp = 412, heightDp = 915)
@Composable
private fun ShellDrawerPreview() {
    FrigatePreview {
        ShellDrawer(
            open = true,
            finance = FinanceUiState(),
            weather = WeatherUiState(),
            fitness = FitnessUiState(),
            atlas = AtlasUiState(),
            pull = remember { DrawerPull(onOpen = {}) },
            onOpenFinance = {},
            onOpenWeather = {},
            onOpenFitness = {},
            onOpenAtlas = {},
            onClose = {},
        )
    }
}
