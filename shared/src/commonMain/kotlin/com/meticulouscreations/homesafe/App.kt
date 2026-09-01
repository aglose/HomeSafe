package com.meticulouscreations.homesafe

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import com.meticulouscreations.homesafe.ui.screens.HomeLiveViewScreen
import com.meticulouscreations.homesafe.ui.screens.SecureConnectionScreen
import com.meticulouscreations.homesafe.ui.theme.FrigateTheme

private enum class FrigateDestination { SecureConnection, Home }

@Composable
@Preview
fun App() {
    FrigateTheme {
        var destination by remember { mutableStateOf(FrigateDestination.SecureConnection) }
        when (destination) {
            FrigateDestination.SecureConnection -> {
                SecureConnectionScreen(
                    onConnect = { destination = FrigateDestination.Home },
                )
            }

            FrigateDestination.Home -> {
                HomeLiveViewScreen()
            }
        }
    }
}
