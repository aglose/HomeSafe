package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.runtime.snapshots.SnapshotStateList
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

    // The Home tab's nested back stack lives here, not in HomeTabNav, so the shell can tell when
    // Home has drilled into a camera. Those nested screens draw their own header row, and
    // stacking the shell bar above it would cost ~70dp of vertical space for no information.
    val homeBackStack = remember { mutableStateListOf<Any>(CameraListRoute) }
    val showTopBar = topLevelBackStack.topLevelKey != TopLevelRoute.Home || homeBackStack.size <= 1

    // Edge-to-edge: the background paints under the system bars, and each piece that must stay
    // tappable steps in from its own bar — the top bar from the status bar, the floating nav from
    // the navigation bar, and everything from a display cutout at the sides (landscape notch).
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // A short shrink + fade rather than a hard cut, so the content below slides up into
            // the freed space instead of jumping when a nested screen opens or closes.
            AnimatedVisibility(
                visible = showTopBar,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                FrigateTopBar(activeConnection = activeConnection)
            }
            NavDisplay(
                modifier = Modifier.weight(1f),
                backStack = topLevelBackStack.backStack,
                onBack = { topLevelBackStack.removeLast() },
                entryProvider = entryProvider {
                    entry<TopLevelRoute.Home> { HomeTabNav(appGraph, homeBackStack) }
                    entry<TopLevelRoute.Moments> {
                        MomentsTabContent(
                            observeMomentsUseCase = appGraph.observeMomentsUseCase,
                            getMomentClipStreamUseCase = appGraph.getMomentClipStreamUseCase,
                            downloadMomentClipUseCase = appGraph.downloadMomentClipUseCase,
                            momentsRepository = appGraph.momentsRepository,
                            connectionRepository = appGraph.connectionRepository,
                        )
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
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        )
    }
}

@Composable
private fun FrigateTopBar(activeConnection: ActiveConnection?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = {}, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.Menu, contentDescription = "Menu", tint = MaterialTheme.colorScheme.primary)
        }
        Text(
            text = "PERCYSAFE",
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

/**
 * "Local network" vs "Tailscale" at a glance — the former is the fast, direct video path.
 * Internal so nested screens that replace the shell bar with their own header can keep it.
 */
@Composable
internal fun ConnectionRouteBadge(route: ConnectionRoute) {
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
private data class DetectionZonesRoute(val cameraName: String)

/**
 * The Home tab's own nested navigation: the camera list, and drilling into a camera's detail
 * screen. The [SharedTransitionLayout] lets the tapped camera's video area animate from its grid
 * card into the detail screen's player (and back), keyed by camera name on both sides.
 *
 * [backStack] is owned by [FrigateAppShell] (see there for why) and starts at [CameraListRoute].
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun HomeTabNav(appGraph: AppGraph, backStack: SnapshotStateList<Any>) {
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
                        observeMomentsUseCase = appGraph.observeMomentsUseCase,
                        sharedTransitionScope = this@SharedTransitionLayout,
                        onBack = { backStack.removeLastOrNull() },
                        onEditDetectionZones = { backStack.add(DetectionZonesRoute(route.cameraName)) },
                    )
                }
                entry<DetectionZonesRoute> { route ->
                    DetectionZonesScreen(
                        cameraName = route.cameraName,
                        connectionRepository = appGraph.connectionRepository,
                        getDetectionConfigUseCase = appGraph.getDetectionConfigUseCase,
                        saveDetectionMasksUseCase = appGraph.saveDetectionMasksUseCase,
                        saveDetectionZonesUseCase = appGraph.saveDetectionZonesUseCase,
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
