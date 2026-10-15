package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.atlas.AtlasUiState
import com.meticulouscreations.homesafe.atlas.ui.AtlasDrawerCard
import com.meticulouscreations.homesafe.finance.FinanceUiState
import com.meticulouscreations.homesafe.finance.ui.FinanceDrawerCard
import com.meticulouscreations.homesafe.fitness.FitnessUiState
import com.meticulouscreations.homesafe.fitness.ui.FitnessDrawerCard
import com.meticulouscreations.homesafe.ui.preview.FrigatePreview
import com.meticulouscreations.homesafe.weather.WeatherUiState
import com.meticulouscreations.homesafe.weather.ui.WeatherDrawerCard

/*
 * The drawer out, over nothing, with each app's card before its data is in. Here in :app because the
 * cards are the feature modules'. Only Studio draws this, so it stays private (see HomePreviews.kt
 * for the ones Layoutlib draws too).
 */

@Preview(name = "Drawer", widthDp = 412, heightDp = 915)
@Composable
private fun ShellDrawerPreview() {
    FrigatePreview {
        ShellDrawer(
            open = true,
            pull = remember { DrawerPull(onOpen = {}) },
            onClose = {},
        ) { animated ->
            WeatherDrawerCard(WeatherUiState(), animated = animated, onOpen = {})
            Spacer(Modifier.height(12.dp))
            AtlasDrawerCard(AtlasUiState(), locked = false, animated = animated, onOpen = {})
            Spacer(Modifier.height(12.dp))
            FitnessDrawerCard(FitnessUiState(), animated = animated, onOpen = {})
            Spacer(Modifier.height(12.dp))
            FinanceDrawerCard(FinanceUiState(), locked = false, onOpen = {})
        }
    }
}
