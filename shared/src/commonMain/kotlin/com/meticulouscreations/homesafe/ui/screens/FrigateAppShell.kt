package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.NavDisplay
import com.meticulouscreations.homesafe.domain.model.ActiveConnection
import com.meticulouscreations.homesafe.domain.model.ConnectionRoute
import com.meticulouscreations.homesafe.navigation.TOP_LEVEL_ROUTES
import com.meticulouscreations.homesafe.navigation.TopLevelBackStack
import com.meticulouscreations.homesafe.navigation.TopLevelRoute
import com.meticulouscreations.homesafe.ui.components.PulsingDot
import com.meticulouscreations.homesafe.ui.theme.LocalFrigateExtraColors
import com.meticulouscreations.homesafe.viewmodel.AppShellViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel

/** The main app shell: a persistent header, tab content driven by Navigation 3, and a floating bottom nav. */
@Composable
fun FrigateAppShell() {
    val viewModel: AppShellViewModel = metroViewModel()
    val topLevelBackStack = remember { TopLevelBackStack<TopLevelRoute>(TopLevelRoute.Home) }
    val activeConnection by viewModel.activeConnection.collectAsStateWithLifecycle()

    // The Home tab's nested back stack lives here, not in HomeTabNav, so the shell can tell when
    // Home has drilled into a camera. Those nested screens draw their own header row, and
    // stacking the shell bar above it would cost ~70dp of vertical space for no information.
    val homeBackStack = remember { mutableStateListOf<Any>(CameraListRoute) }
    // Same arrangement for Settings, which drills into a classifier's labelling screen.
    val settingsBackStack = remember { mutableStateListOf<Any>(SettingsHomeRoute) }
    val showTopBar = when (topLevelBackStack.topLevelKey) {
        TopLevelRoute.Home -> homeBackStack.size <= 1
        TopLevelRoute.Settings -> settingsBackStack.size <= 1
        else -> true
    }

    ShellScaffold(
        showTopBar = showTopBar,
        topBar = { FrigateTopBar(activeConnection = activeConnection) },
        selectedTab = topLevelBackStack.topLevelKey,
        onSelectTab = { topLevelBackStack.addTopLevel(it) },
    ) {
        NavDisplay(
            modifier = Modifier.fillMaxSize(),
            backStack = topLevelBackStack.backStack,
            onBack = { topLevelBackStack.removeLast() },
            transitionSpec = { tabHandOver() },
            popTransitionSpec = { tabHandOver() },
            predictivePopTransitionSpec = { tabHandOver() },
            entryProvider = entryProvider {
                entry<TopLevelRoute.Home> { HomeTabNav(homeBackStack) }
                entry<TopLevelRoute.Moments> { MomentsTabContent() }
                entry<TopLevelRoute.Settings> {
                    SettingsTabNav(settingsBackStack) { openClassifier, openFaces ->
                        SettingsTabContent(onOpenClassifier = openClassifier, onOpenFaces = openFaces)
                    }
                }
            },
        )
    }
}

/**
 * Tabs hand over along the bottom nav's own axis: moving to a tab further right slides left,
 * and back the other way, so the motion agrees with where the finger just went.
 */
private fun AnimatedContentTransitionScope<Scene<TopLevelRoute>>.tabHandOver(): ContentTransform {
    // A scene's key is its top entry's content key, which for these data objects is their
    // toString() (Navigation 3's default content key); the entry's own key is not exposed.
    fun Scene<TopLevelRoute>.tabIndex(): Int = TOP_LEVEL_ROUTES.indexOfFirst { it.toString() == key }
    return sharedAxis(forward = targetState.tabIndex() >= initialState.tabIndex())
}

/**
 * The shell's chrome around [content]: the top bar floating over it (so it can fade away when a
 * nested screen brings its own header, instead of collapsing and shoving the content up), and
 * the bottom nav floating at the foot. Tab content pads itself under both — see
 * [shellTopBarClearance] and [bottomNavClearance].
 *
 * Edge-to-edge: the background paints under the system bars, and each piece that must stay
 * tappable steps in from its own bar — the top bar from the status bar, the floating nav from
 * the navigation bar, and everything from a display cutout at the sides (landscape notch).
 */
@Composable
internal fun ShellScaffold(
    showTopBar: Boolean,
    topBar: @Composable () -> Unit,
    selectedTab: TopLevelRoute,
    onSelectTab: (TopLevelRoute) -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)),
    ) {
        content()

        // Fades over the nested screen's own header, which is the same height in the same
        // place, so the hand-over is a dissolve between two rows and nothing else moves.
        AnimatedVisibility(
            visible = showTopBar,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(tween(NAV_TRANSITION_MS, easing = LinearEasing)),
            exit = fadeOut(tween(NAV_TRANSITION_MS / 2, easing = LinearEasing)),
        ) {
            topBar()
        }

        BottomNavBar(
            selected = selectedTab,
            onSelect = onSelectTab,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        )
    }
}

/**
 * The shell as it will look the moment sign-in succeeds, with the home page's loading skeleton
 * where the cameras will be. The sign-in screen shows this while the server is authenticating,
 * so the cross-fade into the real shell changes only the content, never the chrome.
 */
@Composable
internal fun ShellSkeleton() {
    ShellScaffold(
        showTopBar = true,
        topBar = { FrigateTopBar(activeConnection = null) },
        selectedTab = TopLevelRoute.Home,
        onSelectTab = {},
    ) {
        // The real home list in its loading state — the same composable Home draws — so the
        // hand-over into the live page changes nothing but the cards' contents.
        HomeFeed(everyoneAway = false, cameras = null, onAwayBack = {}) {}
    }
}

@Composable
private fun FrigateTopBar(activeConnection: ActiveConnection?) {
    // A Box, not a Row: the title is centred on the screen regardless of what sits at the ends,
    // so it doesn't shift when the status icon becomes the (wider) route badge — which is also
    // the moment the sign-in skeleton's bar dissolves into the real one.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // Opaque, status bar included: tab content scrolls underneath this bar.
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        IconButton(onClick = {}, modifier = Modifier.size(48.dp).align(Alignment.CenterStart)) {
            Icon(Icons.Filled.Menu, contentDescription = "Menu", tint = MaterialTheme.colorScheme.primary)
        }
        Text(
            text = "PERCYSAFE",
            style = MaterialTheme.typography.headlineMedium.copy(letterSpacing = 0.03.em),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.align(Alignment.Center),
        )
        val route = activeConnection?.route
        if (route == null) {
            IconButton(onClick = {}, modifier = Modifier.size(48.dp).align(Alignment.CenterEnd)) {
                Icon(Icons.Filled.Sensors, contentDescription = "Status", tint = MaterialTheme.colorScheme.primary)
            }
        } else {
            Box(modifier = Modifier.align(Alignment.CenterEnd)) { ConnectionRouteBadge(route) }
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
/**
 * [warmStreamUrl] / [warmPosterUrl] are what the tapped card was already playing: the detail
 * screen binds to that same pooled player straight away, so the video is on screen from the
 * first frame instead of a placeholder while the view model works out the stream to join.
 */
private data class CameraDetailRoute(val cameraName: String, val warmStreamUrl: String?, val warmPosterUrl: String?)
private data class DetectionZonesRoute(val cameraName: String)

/**
 * The Home tab's own nested navigation: the camera list, and drilling into a camera's detail
 * screen. The [SharedTransitionLayout] lets the tapped camera's video animate from its grid
 * card into the detail screen's player (and back), keyed by camera name on both sides.
 *
 * The detail screen's own transition is fade-only ([SharedElementPush] / [SharedElementPop]),
 * so the video is the one thing that moves; the zone editor beyond it uses the ordinary
 * shared-axis slide. Navigation 3 takes the specs from the entry nearer the top of the stack,
 * which is why they are attached to the detail entry rather than to the display.
 *
 * [backStack] is owned by [FrigateAppShell] (see there for why) and starts at [CameraListRoute].
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun HomeTabNav(backStack: SnapshotStateList<Any>) {
    SharedTransitionLayout {
        NavDisplay(
            backStack = backStack,
            onBack = { backStack.removeLastOrNull() },
            transitionSpec = { sharedAxis(forward = true) },
            popTransitionSpec = { sharedAxis(forward = false) },
            predictivePopTransitionSpec = { sharedAxis(forward = false) },
            entryProvider = entryProvider {
                entry<CameraListRoute> {
                    HomeTabContent(
                        sharedTransitionScope = this@SharedTransitionLayout,
                        onCameraClick = { tile ->
                            backStack.add(CameraDetailRoute(tile.camera.name, tile.streamUrl, tile.posterUrl))
                        },
                    )
                }
                entry<CameraDetailRoute>(
                    metadata = NavDisplay.transitionSpec { SharedElementPush } +
                        NavDisplay.popTransitionSpec { SharedElementPop } +
                        NavDisplay.predictivePopTransitionSpec { SharedElementPop },
                ) { route ->
                    CameraDetailScreen(
                        cameraName = route.cameraName,
                        warmStreamUrl = route.warmStreamUrl,
                        warmPosterUrl = route.warmPosterUrl,
                        sharedTransitionScope = this@SharedTransitionLayout,
                        onBack = { backStack.removeLastOrNull() },
                        onEditDetectionZones = { backStack.add(DetectionZonesRoute(route.cameraName)) },
                    )
                }
                entry<DetectionZonesRoute> { route ->
                    DetectionZonesScreen(
                        cameraName = route.cameraName,
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
