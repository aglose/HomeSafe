package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.meticulouscreations.homesafe.di.AppGraph
import com.meticulouscreations.homesafe.domain.model.ActiveConnection
import com.meticulouscreations.homesafe.domain.model.ConnectionRoute
import com.meticulouscreations.homesafe.navigation.TOP_LEVEL_ROUTES
import com.meticulouscreations.homesafe.navigation.TopLevelBackStack
import com.meticulouscreations.homesafe.navigation.TopLevelRoute
import com.meticulouscreations.homesafe.ui.components.PulsingDot
import com.meticulouscreations.homesafe.ui.theme.LocalFrigateExtraColors

/** The main app shell: a persistent header, tab content driven by Navigation 3, and a floating bottom nav. */
@Composable
fun FrigateAppShell(appGraph: AppGraph) {
    val topLevelBackStack = remember { TopLevelBackStack<TopLevelRoute>(TopLevelRoute.Home) }
    val activeConnection by appGraph.connectionRepository.activeConnection.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            FrigateTopBar(activeConnection = activeConnection)
            NavDisplay(
                modifier = Modifier.weight(1f),
                backStack = topLevelBackStack.backStack,
                onBack = { topLevelBackStack.removeLast() },
                entryProvider = entryProvider {
                    entry<TopLevelRoute.Home> { HomeTabNav(appGraph) }
                    entry<TopLevelRoute.Moments> {
                        MomentsTabContent(observeMomentsUseCase = appGraph.observeMomentsUseCase)
                    }
                    entry<TopLevelRoute.Settings> {
                        SettingsTabContent(
                            observeSettingsUseCase = appGraph.observeSettingsUseCase,
                            updateSettingsUseCase = appGraph.updateSettingsUseCase,
                            connectionRepository = appGraph.connectionRepository,
                        )
                    }
                },
            )
        }

        BottomNavBar(
            selected = topLevelBackStack.topLevelKey,
            onSelect = { topLevelBackStack.addTopLevel(it) },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
        )
    }
}

@Composable
private fun FrigateTopBar(activeConnection: ActiveConnection?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = {}, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.Menu, contentDescription = "Menu", tint = MaterialTheme.colorScheme.primary)
        }
        Text(
            text = "FRIGATE",
            style = MaterialTheme.typography.headlineMedium.copy(letterSpacing = 0.03.em),
            color = MaterialTheme.colorScheme.onSurface,
        )
        val route = activeConnection?.route
        if (route == null) {
            IconButton(onClick = {}, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Sensors, contentDescription = "Status", tint = MaterialTheme.colorScheme.primary)
            }
        } else {
            ConnectionRouteBadge(route)
        }
    }
}

/** "Local network" vs "Tailscale" at a glance — the former is the fast, direct video path. */
@Composable
private fun ConnectionRouteBadge(route: ConnectionRoute) {
    val tint = when (route) {
        ConnectionRoute.LOCAL_NETWORK -> MaterialTheme.colorScheme.secondary
        ConnectionRoute.TAILSCALE -> MaterialTheme.colorScheme.primary
    }
    Row(
        modifier = Modifier
            .background(LocalFrigateExtraColors.current.glassFill, CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f), CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (route == ConnectionRoute.LOCAL_NETWORK) {
            Icon(Icons.Filled.Wifi, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        } else {
            PulsingDot(color = tint, size = 6.dp, pulsing = false)
        }
        Text(text = route.label, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

private data object CameraListRoute
private data class CameraDetailRoute(val cameraName: String)

/**
 * The Home tab's own nested navigation: the camera list, and drilling into a camera's detail
 * screen. The [SharedTransitionLayout] lets the tapped camera's video area animate from its grid
 * card into the detail screen's player (and back), keyed by camera name on both sides.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun HomeTabNav(appGraph: AppGraph) {
    val backStack = remember { mutableStateListOf<Any>(CameraListRoute) }
    SharedTransitionLayout {
        NavDisplay(
            backStack = backStack,
            onBack = { backStack.removeLastOrNull() },
            entryProvider = entryProvider {
                entry<CameraListRoute> {
                    HomeTabContent(
                        observeCamerasUseCase = appGraph.observeCamerasUseCase,
                        connectionRepository = appGraph.connectionRepository,
                        sharedTransitionScope = this@SharedTransitionLayout,
                        onCameraClick = { cameraName -> backStack.add(CameraDetailRoute(cameraName)) },
                    )
                }
                entry<CameraDetailRoute> { route ->
                    CameraDetailScreen(
                        cameraName = route.cameraName,
                        observeCamerasUseCase = appGraph.observeCamerasUseCase,
                        connectionRepository = appGraph.connectionRepository,
                        getRecordingHistoryUseCase = appGraph.getRecordingHistoryUseCase,
                        getRecordingStreamUseCase = appGraph.getRecordingStreamUseCase,
                        sharedTransitionScope = this@SharedTransitionLayout,
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
            },
        )
    }
}

@Composable
private fun BottomNavBar(
    selected: TopLevelRoute,
    onSelect: (TopLevelRoute) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .widthIn(max = 400.dp)
            .fillMaxWidth(0.9f)
            .background(LocalFrigateExtraColors.current.glassFill, CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f), CircleShape)
            .padding(8.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TOP_LEVEL_ROUTES.forEach { route ->
            BottomNavItemView(
                route = route,
                isSelected = route == selected,
                onClick = { onSelect(route) },
            )
        }
    }
}

@Composable
private fun BottomNavItemView(route: TopLevelRoute, isSelected: Boolean, onClick: () -> Unit) {
    val contentColor = if (isSelected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .clip(CircleShape)
            .let { if (isSelected) it.background(MaterialTheme.colorScheme.primaryContainer) else it }
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(route.icon, contentDescription = route.label, tint = contentColor, modifier = Modifier.size(22.dp))
        Text(text = route.label, style = MaterialTheme.typography.labelMedium, color = contentColor)
    }
}
