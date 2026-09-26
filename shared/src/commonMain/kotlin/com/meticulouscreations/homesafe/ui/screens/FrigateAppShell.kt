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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.NavDisplay
import com.meticulouscreations.homesafe.domain.model.ActiveConnection
import com.meticulouscreations.homesafe.domain.model.ConnectionRoute
import com.meticulouscreations.homesafe.domain.model.MomentEvent
import com.meticulouscreations.homesafe.navigation.MomentDeepLink
import com.meticulouscreations.homesafe.navigation.MomentDeepLinks
import com.meticulouscreations.homesafe.navigation.TOP_LEVEL_ROUTES
import com.meticulouscreations.homesafe.navigation.TopLevelBackStack
import com.meticulouscreations.homesafe.navigation.TopLevelRoute
import com.meticulouscreations.homesafe.ui.components.PulsingDot
import com.meticulouscreations.homesafe.ui.theme.LocalFrigateExtraColors
import com.meticulouscreations.homesafe.viewmodel.AppShellViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.coroutines.flow.filterNotNull

/**
 * The shell's navigation state: which tab is up, and the nested back stack of each tab that has
 * one. Owned here rather than inside the tab composables so the shell can tell when a tab has
 * drilled into a screen that brings its own header (see [showsTopBar]), and so the Moments tab
 * can land a detection on the Home stack (see [openDetection]).
 *
 * One instance outlives the tab composables: [FrigateAppShell] remembers it for the run of the
 * shell, and the iOS 26 host (`IosShell.kt`), where each tab is its own Compose view controller
 * under a native tab bar, shares one across all three. [onTabSelected] is how that host hears
 * about a tab switch Compose asked for; the Compose-drawn nav needs nothing more than the state.
 */
@Stable
internal class ShellNavigation(private val onTabSelected: (TopLevelRoute) -> Unit = {}) {
    val topLevel = TopLevelBackStack<TopLevelRoute>(TopLevelRoute.Home)

    // The Home tab's nested back stack lives here, not in HomeTabNav, so the shell can tell when
    // Home has drilled into a camera. Those nested screens draw their own header row, and
    // stacking the shell bar above it would cost ~70dp of vertical space for no information.
    val homeBackStack: SnapshotStateList<Any> = mutableStateListOf(CameraListRoute)

    // Same arrangement for Settings, which drills into a classifier's labelling screen.
    val settingsBackStack: SnapshotStateList<Any> = mutableStateListOf(SettingsHomeRoute)

    /** The tab that is up, as far as Compose knows; under a native tab bar the bar itself is the truth. */
    val selectedTab: TopLevelRoute get() = topLevel.topLevelKey

    /** Whether [tab] is showing its root screen, which is when the shell's own top bar belongs above it. */
    fun showsTopBar(tab: TopLevelRoute): Boolean = when (tab) {
        TopLevelRoute.Home -> homeBackStack.size <= 1
        TopLevelRoute.Settings -> settingsBackStack.size <= 1
        else -> true
    }

    /** Whether the floating bottom nav belongs over [tab]: not over the car-tagging screen, which wants every pixel for the frame. */
    fun showsBottomNav(tab: TopLevelRoute): Boolean = !(tab == TopLevelRoute.Home && homeBackStack.lastOrNull() is CarTaggingRoute)

    fun selectTab(tab: TopLevelRoute) {
        topLevel.addTopLevel(tab)
        onTabSelected(tab)
    }

    /**
     * Full screen for a detection is the Home tab's camera screen, opened at that instant: one
     * full-width player for the camera, not a second one on the Moments tab. It lands on the
     * Home stack, so Back returns to the camera list — and the Moments tab is one tap away,
     * still where it was left.
     */
    fun openDetection(event: MomentEvent) = openDetection(event.cameraName, event.startEpochSeconds)

    /** The same destination for a notification tap: see [MomentDeepLinks]. */
    fun openMoment(link: MomentDeepLink) = openDetection(link.cameraName, link.startEpochSeconds)

    private fun openDetection(cameraName: String, startEpochSeconds: Double) {
        val route = CameraDetailRoute(
            cameraName = cameraName,
            warmStreamUrl = null,
            warmPosterUrl = null,
            openAtEpochSeconds = startEpochSeconds,
        )
        // A second tap on the same notification (or a relaunch re-delivering it) is already up.
        if (homeBackStack.lastOrNull() != route) homeBackStack.add(route)
        selectTab(TopLevelRoute.Home)
    }

    /**
     * Opens every moment a notification tap asks for, for as long as the caller runs. The link
     * is consumed only here, once the shell exists, which is what lets a tap that cold-starts
     * the app wait out the sign-in screen and still land on the moment.
     */
    suspend fun openMomentsFromNotifications() {
        MomentDeepLinks.pending.filterNotNull().collect { link ->
            openMoment(link)
            MomentDeepLinks.consume(link)
        }
    }
}

/** The main app shell: a persistent header, tab content driven by Navigation 3, and a floating bottom nav. */
@Composable
fun FrigateAppShell() {
    val viewModel: AppShellViewModel = metroViewModel()
    val nav = remember { ShellNavigation() }
    // The Home list's quick look at one camera. Its layer is drawn by the shell, over the bars,
    // so the zoomed picture gets the whole screen.
    val cardZoom = rememberCameraCardZoomState()
    val activeConnection by viewModel.activeConnection.collectAsStateWithLifecycle()
    LaunchedEffect(nav) { nav.openMomentsFromNotifications() }

    ShellScaffold(
        showTopBar = nav.showsTopBar(nav.selectedTab),
        showBottomNav = nav.showsBottomNav(nav.selectedTab),
        topBar = { FrigateTopBar(activeConnection = activeConnection, appVersion = viewModel.appVersion) },
        selectedTab = nav.selectedTab,
        onSelectTab = nav::selectTab,
        overlay = { CardZoomOverlay(nav, cardZoom) },
    ) {
        NavDisplay(
            modifier = Modifier.fillMaxSize(),
            backStack = nav.topLevel.backStack,
            onBack = { nav.topLevel.removeLast() },
            transitionSpec = { tabHandOver() },
            popTransitionSpec = { tabHandOver() },
            predictivePopTransitionSpec = { tabHandOver() },
            entryProvider = entryProvider {
                entry<TopLevelRoute.Home> { TabContent(TopLevelRoute.Home, nav, cardZoom) }
                entry<TopLevelRoute.Moments> { TabContent(TopLevelRoute.Moments, nav, cardZoom) }
                entry<TopLevelRoute.Settings> { TabContent(TopLevelRoute.Settings, nav, cardZoom) }
            },
        )
    }
}

/**
 * One tab of the shell on its own, for a host whose platform draws the tab bar: the shell's
 * chrome around that tab's content, with no Compose bottom nav (the host provides
 * [LocalNativeTabBar] as true) and no tab switching of its own — the host's bar is the truth
 * about which tab is up, and [nav] only tells it when Compose wants a different one. The iOS 26
 * Liquid Glass `TabView` hosts one of these per tab (see `IosShell.kt`).
 */
@Composable
internal fun ShellTab(nav: ShellNavigation, tab: TopLevelRoute) {
    val viewModel: AppShellViewModel = metroViewModel()
    // Per tab, like the scaffold it lifts into: only Home ever opens it, and its layer covers
    // this tab's Compose view (the native bar beneath is the platform's to draw).
    val cardZoom = rememberCameraCardZoomState()
    val activeConnection by viewModel.activeConnection.collectAsStateWithLifecycle()

    ShellScaffold(
        showTopBar = nav.showsTopBar(tab),
        showBottomNav = nav.showsBottomNav(tab),
        topBar = { FrigateTopBar(activeConnection = activeConnection, appVersion = viewModel.appVersion) },
        selectedTab = tab,
        onSelectTab = nav::selectTab,
        overlay = { CardZoomOverlay(nav, cardZoom) },
    ) {
        TabContent(tab, nav, cardZoom)
    }
}

/** What [tab] shows: its root screen, and the nested stack beyond it where the tab has one. */
@Composable
private fun TabContent(tab: TopLevelRoute, nav: ShellNavigation, cardZoom: CameraCardZoomState) {
    when (tab) {
        TopLevelRoute.Home -> HomeTabNav(nav.homeBackStack, cardZoom, onOpenMoments = { nav.selectTab(TopLevelRoute.Moments) })

        TopLevelRoute.Moments -> MomentsTabContent(onOpenFullScreen = nav::openDetection)

        TopLevelRoute.Settings -> SettingsTabNav(nav.settingsBackStack) { openClassifier, openFaces, openServer ->
            SettingsTabContent(onOpenClassifier = openClassifier, onOpenFaces = openFaces, onOpenServer = openServer)
        }
    }
}

/** The layer a pinched Home camera card's video lifts into; a tap on it opens that camera's own screen. */
@Composable
private fun CardZoomOverlay(nav: ShellNavigation, cardZoom: CameraCardZoomState) {
    CameraCardZoomOverlay(state = cardZoom) { tile ->
        nav.homeBackStack.add(CameraDetailRoute(tile.camera.name, tile.streamUrl, tile.posterUrl))
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
 *
 * Under a native tab bar ([LocalNativeTabBar]) the floating nav is left out: the platform's bar
 * sits where it would, and [bottomNavClearance] already keeps the content clear of it.
 *
 * [overlay] is drawn last, over the bars too: the layer a pinched camera card's video lifts into.
 */
@Composable
internal fun ShellScaffold(
    showTopBar: Boolean,
    topBar: @Composable () -> Unit,
    selectedTab: TopLevelRoute,
    onSelectTab: (TopLevelRoute) -> Unit,
    overlay: @Composable () -> Unit = {},
    showBottomNav: Boolean = true,
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

        if (!LocalNativeTabBar.current && showBottomNav) {
            BottomNavBar(
                selected = selectedTab,
                onSelect = onSelectTab,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp),
            )
        }

        overlay()
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
        topBar = { FrigateTopBar(activeConnection = null, appVersion = "") },
        selectedTab = TopLevelRoute.Home,
        onSelectTab = {},
    ) {
        // The real home list in its loading state — the same composable Home draws — so the
        // hand-over into the live page changes nothing but the cards' contents.
        HomeFeed(everyoneAway = false, cameras = null, onAwayBack = {}) {}
    }
}

/** [appVersion] is what the route badge shows when tapped; unused until there is a route to badge. */
@Composable
internal fun FrigateTopBar(activeConnection: ActiveConnection?, appVersion: String) {
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
            Box(modifier = Modifier.align(Alignment.CenterEnd)) { ConnectionRouteBadge(route, appVersion) }
        }
    }
}

/**
 * "Local network" vs "Tailscale" at a glance — the former is the fast, direct video path. A tap
 * drops down the app's version, which is how to tell which release is on the phone.
 */
@Composable
private fun ConnectionRouteBadge(route: ConnectionRoute, appVersion: String) {
    val tint = when (route) {
        ConnectionRoute.LOCAL_NETWORK -> MaterialTheme.colorScheme.secondary
        ConnectionRoute.TAILSCALE -> MaterialTheme.colorScheme.primary
    }
    var showVersion by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(LocalFrigateExtraColors.current.glassFill, CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f), CircleShape)
                .clickable(role = Role.Button, onClickLabel = "Show the app version") { showVersion = true }
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
        DropdownMenu(expanded = showVersion, onDismissRequest = { showVersion = false }) {
            Text(
                text = "Version $appVersion",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

private data object CameraListRoute

/**
 * [warmStreamUrl] / [warmPosterUrl] are what the tapped card was already playing: the detail
 * screen binds to that same pooled player straight away, so the video is on screen from the
 * first frame instead of a placeholder while the view model works out the stream to join.
 *
 * [openAtEpochSeconds] is set only when the route came from a detection (the Moments tab's
 * full-screen button, or a notification tap), and opens the screen on the recording at that instant rather than live.
 * It is part of the route's identity, so opening a second detection on the same camera is a new
 * destination rather than a no-op on the one already up.
 */
private data class CameraDetailRoute(
    val cameraName: String,
    val warmStreamUrl: String?,
    val warmPosterUrl: String?,
    val openAtEpochSeconds: Double? = null,
)
private data class DetectionZonesRoute(val cameraName: String)
private data class CarTaggingRoute(val cameraName: String)

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
 * [backStack] is owned by [ShellNavigation] (see there for why) and starts at [CameraListRoute].
 * [onOpenMoments] switches to the Moments tab, for the summary at the top of the camera list.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun HomeTabNav(backStack: SnapshotStateList<Any>, cardZoom: CameraCardZoomState, onOpenMoments: () -> Unit) {
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
                        zoomState = cardZoom,
                        onCameraClick = { tile ->
                            backStack.add(CameraDetailRoute(tile.camera.name, tile.streamUrl, tile.posterUrl))
                        },
                        onOpenMoments = onOpenMoments,
                        onTagCars = { cameraName -> backStack.add(CarTaggingRoute(cameraName)) },
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
                        onTagCars = { backStack.add(CarTaggingRoute(route.cameraName)) },
                        openAtEpochSeconds = route.openAtEpochSeconds,
                    )
                }
                entry<DetectionZonesRoute> { route ->
                    DetectionZonesScreen(
                        cameraName = route.cameraName,
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                entry<CarTaggingRoute> { route ->
                    CarTaggingScreen(
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
            // Which tab is up, for accessibility services and tests alike.
            .semantics { selected = isSelected }
            .testTag(bottomNavTestTag(route))
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(route.icon, contentDescription = route.label, tint = contentColor, modifier = Modifier.size(22.dp))
        Text(text = route.label, style = MaterialTheme.typography.labelMedium, color = contentColor)
    }
}

/**
 * The bottom nav item for [route], for tests (and, on Android, UiAutomator as a resource id). Its
 * label alone is ambiguous: "Home" and "Settings" also appear as text inside the tabs.
 */
fun bottomNavTestTag(route: TopLevelRoute): String = "bottom_nav_${route.label.lowercase()}"
