package com.meticulouscreations.homesafe.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.ui.graphics.vector.ImageVector

/** The app's top-level destinations, shown as tabs in the bottom navigation bar. */
sealed interface TopLevelRoute {
    val label: String
    val icon: ImageVector

    data object Home : TopLevelRoute {
        override val label = "Home"
        override val icon = Icons.Filled.Home
    }

    data object Moments : TopLevelRoute {
        override val label = "Moments"
        override val icon = Icons.Filled.VideoLibrary
    }

    data object Settings : TopLevelRoute {
        override val label = "Settings"
        override val icon = Icons.Filled.Settings
    }
}

val TOP_LEVEL_ROUTES: List<TopLevelRoute> = listOf(TopLevelRoute.Home, TopLevelRoute.Moments, TopLevelRoute.Settings)
