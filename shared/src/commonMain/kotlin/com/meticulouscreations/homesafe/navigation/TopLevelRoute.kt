package com.meticulouscreations.homesafe.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.ui.graphics.vector.ImageVector
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.shell_tab_home
import homesafe.shared.generated.resources.shell_tab_moments
import homesafe.shared.generated.resources.shell_tab_settings
import org.jetbrains.compose.resources.StringResource

/** The app's top-level destinations, shown as tabs in the bottom navigation bar. */
sealed interface TopLevelRoute {
    /** The tab's name, under its icon and read out for it. */
    val label: StringResource
    val icon: ImageVector

    data object Home : TopLevelRoute {
        override val label = Res.string.shell_tab_home
        override val icon = Icons.Filled.Home
    }

    data object Moments : TopLevelRoute {
        override val label = Res.string.shell_tab_moments
        override val icon = Icons.Filled.VideoLibrary
    }

    data object Settings : TopLevelRoute {
        override val label = Res.string.shell_tab_settings
        override val icon = Icons.Filled.Settings
    }
}

val TOP_LEVEL_ROUTES: List<TopLevelRoute> = listOf(TopLevelRoute.Home, TopLevelRoute.Moments, TopLevelRoute.Settings)
