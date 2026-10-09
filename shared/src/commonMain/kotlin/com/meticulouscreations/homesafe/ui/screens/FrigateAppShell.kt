package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.NavDisplay
import com.meticulouscreations.homesafe.domain.model.ActiveConnection
import com.meticulouscreations.homesafe.domain.model.ConnectionRoute
import com.meticulouscreations.homesafe.domain.model.MomentEvent
import com.meticulouscreations.homesafe.finance.FinanceViewModel
import com.meticulouscreations.homesafe.finance.ui.FinanceApp
import com.meticulouscreations.homesafe.finance.ui.FinanceTab
import com.meticulouscreations.homesafe.fitness.FitnessViewModel
import com.meticulouscreations.homesafe.fitness.ui.FitnessApp
import com.meticulouscreations.homesafe.navigation.FinanceDeepLinks
import com.meticulouscreations.homesafe.navigation.FitnessShares
import com.meticulouscreations.homesafe.navigation.MomentDeepLink
import com.meticulouscreations.homesafe.navigation.MomentDeepLinks
import com.meticulouscreations.homesafe.navigation.TOP_LEVEL_ROUTES
import com.meticulouscreations.homesafe.navigation.TopLevelBackStack
import com.meticulouscreations.homesafe.navigation.TopLevelRoute
import com.meticulouscreations.homesafe.navigation.WeatherDeepLinks
import com.meticulouscreations.homesafe.ui.BackScope
import com.meticulouscreations.homesafe.ui.components.PulsingDot
import com.meticulouscreations.homesafe.ui.isCompactLandscape
import com.meticulouscreations.homesafe.ui.theme.LocalFrigateExtraColors
import com.meticulouscreations.homesafe.viewmodel.AppShellViewModel
import com.meticulouscreations.homesafe.weather.WeatherViewModel
import com.meticulouscreations.homesafe.weather.ui.WeatherApp
import dev.zacsweers.metrox.viewmodel.metroViewModel
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.shell_app_title
import homesafe.shared.generated.resources.shell_app_version
import homesafe.shared.generated.resources.shell_menu
import homesafe.shared.generated.resources.shell_offline
import homesafe.shared.generated.resources.shell_show_version
import homesafe.shared.generated.resources.shell_status
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.stringResource

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
/** The apps that live inside PercySafe beside the cameras, each opened from the drawer over the whole shell. */
internal enum class InnerApp { FINANCE, WEATHER, FITNESS }

@Stable
internal class ShellNavigation(private val onTabSelected: (TopLevelRoute) -> Unit = {}) {
    val topLevel = TopLevelBackStack<TopLevelRoute>(TopLevelRoute.Home)

    // The Home tab's nested back stack lives here, not in HomeTabNav, so the shell can tell when
    // Home has drilled into a camera. Those nested screens draw their own header row, and
    // stacking the shell bar above it would cost ~70dp of vertical space for no information.
    val homeBackStack: SnapshotStateList<Any> = mutableStateListOf(CameraListRoute)

    // Same arrangement for Settings, whose rows open pages of their own (alerts, a classifier's labelling screen, the server).
    val settingsBackStack: SnapshotStateList<Any> = mutableStateListOf(SettingsHomeRoute)

    /** Whether the drawer the top bar's menu button opens is out. */
    var drawerOpen by mutableStateOf(false)

    /** Which of the apps inside PercySafe the overlay holds: the one that is up, or the one on its way out. */
    var app by mutableStateOf(InnerApp.FINANCE)
        private set

    /** Whether one of those apps ([app]) is up over the shell (opened from the drawer, or by a notification). */
    var appOpen by mutableStateOf(false)
        private set

    /** Where the drawer's card for it was, in the shell's coordinates: where the app grows from. */
    var appOrigin by mutableStateOf(Offset.Zero)
        private set

    /** True once the app fully covers the shell, which then stops drawing (and streaming) what's under it. */
    var appCovering by mutableStateOf(false)

    /**
     * The tab whose composition opened the app. Under the iOS 26 host every tab draws the
     * shell's overlays, and only this one should build it.
     */
    var appHost by mutableStateOf<TopLevelRoute?>(null)
        private set

    /**
     * The tab a notification asked the finance app to open on (see [openFinanceFromNotifications]),
     * until the app has turned to it; null when it opens wherever it was left.
     */
    var financeTab by mutableStateOf<FinanceTab?>(null)
        private set

    fun openApp(which: InnerApp, origin: Offset, from: TopLevelRoute, financeTab: FinanceTab? = null) {
        app = which
        appOrigin = origin
        appHost = from
        this.financeTab = financeTab.takeIf { which == InnerApp.FINANCE }
        appOpen = true
        drawerOpen = false
    }

    /** The finance app has turned to the tab it was asked for. */
    fun onFinanceTabShown() {
        financeTab = null
    }

    fun closeApp() {
        appOpen = false
        financeTab = null
        // The shell comes back as the app starts closing — and if a tab switch takes the
        // overlay away before it can say so itself (a notification tap), it still comes back.
        appCovering = false
    }

    fun isOpen(which: InnerApp): Boolean = appOpen && app == which

    /** The tab that is up, as far as Compose knows; under a native tab bar the bar itself is the truth. */
    val selectedTab: TopLevelRoute get() = topLevel.topLevelKey

    /**
     * Whether the window is a phone on its side (see [isCompactLandscape]), as the shell's
     * composition last saw it. Kept here, as state, because what it decides — whether a camera has
     * the whole window — is also asked from outside any composition: the iOS 26 host hides its
     * native tab bar on the same answer (see [showsBottomNav]).
     */
    var compactLandscape by mutableStateOf(false)

    /**
     * Whether [tab] is showing a camera full screen: its own screen, with the phone on its side.
     * The video then has the whole window (see CameraDetailScreen), so the shell takes its nav
     * away and stops keeping the content clear of the cutout at the window's sides.
     */
    fun showsFullScreenVideo(tab: TopLevelRoute): Boolean =
        compactLandscape && tab == TopLevelRoute.Home && homeBackStack.lastOrNull() is CameraDetailRoute

    private val reselected = MutableSharedFlow<TopLevelRoute>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Whether [tab] is showing its root screen, which is when the shell's own top bar belongs above it. */
    fun showsTopBar(tab: TopLevelRoute): Boolean = isAtRoot(tab)

    private fun isAtRoot(tab: TopLevelRoute): Boolean = when (tab) {
        TopLevelRoute.Home -> homeBackStack.size <= 1
        TopLevelRoute.Settings -> settingsBackStack.size <= 1
        TopLevelRoute.Moments -> true
    }

    /** Taps on [tab] in the bottom nav while it was already up at its root: its list scrolls to the top. */
    fun reselections(tab: TopLevelRoute): ScrollToTopRequests = ScrollToTopRequests(reselected.filter { it == tab }.map { })

    /**
     * Whether the shell's nav (the floating pill, or the rail beside a phone on its side) belongs
     * over [tab]: not over the car-tagging screen, which wants every pixel for the frame, nor the
     * clip editor, which is full screen, nor a camera that has the window to itself
     * ([showsFullScreenVideo]), nor while the finance or weather app (which have their own) is up, nor under
     * the drawer — under iOS 26's native bar that would stay on top of the drawer's scrim and
     * switch tabs behind it.
     */
    fun showsBottomNav(tab: TopLevelRoute): Boolean =
        !appOpen &&
            !drawerOpen &&
            !showsFullScreenVideo(tab) &&
            !(tab == TopLevelRoute.Home && homeBackStack.lastOrNull().let { it is CarTaggingRoute || it is ClipEditorRoute })

    /**
     * A tap on [tab] in the bottom nav, which follows Material's bottom navigation behaviour on
     * Android: the tab comes up at its root screen, with whatever had been drilled into there
     * cleared away. On the tab that is already up, the tap pops its nested stack back to the root,
     * or, if it is at its root already, scrolls that screen back to the top.
     *
     * A tab being left keeps its nested stack while it slides away, so it shows what was on it
     * rather than snapping to its root first; it is cleared when the tab next comes up (Home, which
     * stays under the other tabs, as soon as it is off screen — see [FrigateAppShell]).
     */
    fun selectTab(tab: TopLevelRoute) {
        if (tab == selectedTab && isAtRoot(tab)) reselected.tryEmit(tab)
        popToRoot(tab)
        showTab(tab)
    }

    /**
     * Back from the root of a tab other than Home: to Home, at its root, as a tap on Home would
     * land. Home is the fixed start destination (see [TopLevelBackStack]), so Back from there
     * leaves the app.
     */
    fun back() {
        topLevel.removeLast()
        popToRoot(selectedTab)
    }

    private fun showTab(tab: TopLevelRoute) {
        topLevel.switchTo(tab)
        onTabSelected(tab)
    }

    fun popToRoot(tab: TopLevelRoute) {
        val stack = when (tab) {
            TopLevelRoute.Home -> homeBackStack
            TopLevelRoute.Settings -> settingsBackStack
            TopLevelRoute.Moments -> return
        }
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    /**
     * Full screen for a detection is the Home tab's camera screen, opened at that instant: one
     * full-width player for the camera, not a second one on the Moments tab. It lands directly
     * above the camera list, whatever Home had open before, so Back returns to the list — and
     * the Moments tab is one tap away.
     */
    fun openDetection(event: MomentEvent) = openDetection(event.cameraName, event.startEpochSeconds, event.id, tagCar = false)

    /** The same destination for a notification tap: see [MomentDeepLinks]. */
    fun openMoment(link: MomentDeepLink) = openDetection(link.cameraName, link.startEpochSeconds, link.eventId, link.tagCar)

    private fun openDetection(cameraName: String, startEpochSeconds: Double, eventId: String, tagCar: Boolean) {
        // A notification tap lands on the camera whatever was over the shell.
        drawerOpen = false
        closeApp()
        val route = CameraDetailRoute(
            cameraName = cameraName,
            warmStreamUrl = null,
            warmPosterUrl = null,
            openAtEpochSeconds = startEpochSeconds,
            // An older relay's push carries no event id; the screen then just plays the moment.
            openedEventId = eventId.takeIf { it.isNotBlank() },
            tagCarOnOpen = tagCar,
        )
        // A second tap on the same notification (or a relaunch re-delivering it) is already up.
        if (homeBackStack.lastOrNull() != route) {
            popToRoot(TopLevelRoute.Home)
            homeBackStack.add(route)
        }
        showTab(TopLevelRoute.Home)
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

    /**
     * Opens the weather app whenever a weather notification's tap asks for it, for as long as the
     * caller runs: over whatever tab is up, with no card to grow from. Consumed here for the same
     * reason a moment's link is: a tap that cold-starts the app has to wait out the sign-in screen.
     */
    suspend fun openWeatherFromNotifications() {
        WeatherDeepLinks.pending.filter { it }.collect {
            val host = selectedTab
            openApp(InnerApp.WEATHER, Offset.Zero, host)
            // Under a native tab bar the tab Compose last asked for may not be the one on screen;
            // asking for it again puts the tab that holds the app in front.
            onTabSelected(host)
            WeatherDeepLinks.consume()
        }
    }

    /**
     * Opens the fitness app on notes shared into PercySafe from another app, for as long as the
     * caller runs, handing the text to [onNotes] (the import page reads it from there). Consumed
     * only here, once the shell exists, for the reason [openMomentsFromNotifications] does.
     */
    suspend fun openFitnessFromShares(onNotes: (String) -> Unit) {
        FitnessShares.pending.filterNotNull().collect { notes ->
            onNotes(notes)
            openApp(InnerApp.FITNESS, Offset.Zero, selectedTab)
            FitnessShares.consume()
        }
    }

    /**
     * Opens the finance app on the tab a notification tap asks for (a budget alert's: Budget),
     * for as long as the caller runs, over whichever tab is up. Consumed only here, once the
     * shell exists, for the reason [openMomentsFromNotifications] does. With no drawer card to
     * grow from, the app opens from the menu button's corner.
     */
    suspend fun openFinanceFromNotifications() {
        FinanceDeepLinks.pending.filterNotNull().collect { link ->
            openApp(InnerApp.FINANCE, Offset.Zero, selectedTab, link.tab)
            FinanceDeepLinks.consume(link)
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
    val offline by viewModel.offline.collectAsStateWithLifecycle()
    LaunchedEffect(nav) { nav.openMomentsFromNotifications() }
    LaunchedEffect(nav) { nav.openWeatherFromNotifications() }
    LaunchedEffect(nav) { nav.openFinanceFromNotifications() }
    val compactLandscape = isCompactLandscape()
    SideEffect { nav.compactLandscape = compactLandscape }

    ShellScaffold(
        showTopBar = nav.showsTopBar(nav.selectedTab),
        showBottomNav = nav.showsBottomNav(nav.selectedTab),
        immersive = nav.showsFullScreenVideo(nav.selectedTab),
        topBar = { FrigateTopBar(activeConnection = activeConnection, appVersion = viewModel.appVersion, onMenu = { nav.drawerOpen = true }, offline = offline) },
        selectedTab = nav.selectedTab,
        onSelectTab = nav::selectTab,
        overlay = { ShellOverlays(nav, cardZoom, nav.selectedTab) },
        contentCovered = nav.appCovering,
        contentObscured = nav.drawerOpen || nav.appOpen,
    ) {
        NavDisplay(
            modifier = Modifier.fillMaxSize(),
            backStack = nav.topLevel.backStack,
            onBack = nav::back,
            transitionSpec = { tabHandOver() },
            popTransitionSpec = { tabHandOver() },
            // Back from a tab only ever returns to Home, so a swipe needs only its edge.
            predictivePopTransitionSpec = { predictiveSharedAxis(it) },
            entryProvider = entryProvider {
                entry<TopLevelRoute.Home> {
                    // Home stays under the other tabs as the start destination, and Back reveals
                    // it. Clearing what it had drilled into once another tab has fully taken
                    // over means a predictive Back previews the camera list it will land on.
                    DisposableEffect(nav) { onDispose { nav.popToRoot(TopLevelRoute.Home) } }
                    TabContent(TopLevelRoute.Home, nav, cardZoom)
                }
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
    val offline by viewModel.offline.collectAsStateWithLifecycle()
    // Every tab's composition says the same thing here: they share one window.
    val compactLandscape = isCompactLandscape()
    SideEffect { nav.compactLandscape = compactLandscape }

    ShellScaffold(
        showTopBar = nav.showsTopBar(tab),
        showBottomNav = nav.showsBottomNav(tab),
        immersive = nav.showsFullScreenVideo(tab),
        topBar = { FrigateTopBar(activeConnection = activeConnection, appVersion = viewModel.appVersion, onMenu = { nav.drawerOpen = true }, offline = offline) },
        selectedTab = tab,
        onSelectTab = nav::selectTab,
        overlay = { ShellOverlays(nav, cardZoom, tab) },
        contentCovered = nav.appCovering,
        contentObscured = nav.drawerOpen || nav.appOpen,
    ) {
        TabContent(tab, nav, cardZoom)
    }
}

/** What [tab] shows: its root screen, and the nested stack beyond it where the tab has one. */
@Composable
private fun TabContent(tab: TopLevelRoute, nav: ShellNavigation, cardZoom: CameraCardZoomState) {
    val scrollToTop = remember(nav, tab) { nav.reselections(tab) }
    when (tab) {
        TopLevelRoute.Home -> HomeTabNav(nav.homeBackStack, cardZoom, scrollToTop, onOpenMoments = { nav.selectTab(TopLevelRoute.Moments) })

        TopLevelRoute.Moments -> MomentsTabContent(onOpenFullScreen = nav::openDetection, scrollToTopRequests = scrollToTop)

        TopLevelRoute.Settings -> SettingsTabNav(nav.settingsBackStack) { open ->
            SettingsTabContent(onOpen = open, scrollToTopRequests = scrollToTop)
        }
    }
}

/**
 * Everything the shell draws over its tabs and bars: the pinched camera card's layer, the drawer,
 * and the app opened from it (finance, weather or fitness). Their state is only fetched while this
 * composition is on screen — under the iOS 26 host each tab has its own, and only the visible
 * one may run it.
 */
// Overlays, not a laid-out element: each piece fills the scaffold's layer itself.
@Suppress("ktlint:compose:modifier-missing-check")
@Composable
private fun ShellOverlays(nav: ShellNavigation, cardZoom: CameraCardZoomState, tab: TopLevelRoute) {
    CardZoomOverlay(nav, cardZoom)
    val finance: FinanceViewModel = metroViewModel()
    val financeState by finance.uiState.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val visible = lifecycle.isAtLeast(Lifecycle.State.STARTED)
    val financeUp = nav.isOpen(InnerApp.FINANCE)
    LaunchedEffect(visible, nav.drawerOpen, financeUp) {
        finance.setActive(visible && (nav.drawerOpen || financeUp), full = financeUp)
    }
    val weather: WeatherViewModel = metroViewModel()
    val weatherState by weather.uiState.collectAsStateWithLifecycle()
    val weatherUp = nav.isOpen(InnerApp.WEATHER)
    LaunchedEffect(visible, nav.drawerOpen, weatherUp) {
        weather.setActive(visible && (nav.drawerOpen || weatherUp), full = weatherUp)
    }
    val fitness: FitnessViewModel = metroViewModel()
    val fitnessState by fitness.uiState.collectAsStateWithLifecycle()
    val fitnessUp = nav.isOpen(InnerApp.FITNESS)
    LaunchedEffect(visible, nav.drawerOpen, fitnessUp) {
        fitness.setActive(visible && (nav.drawerOpen || fitnessUp), full = fitnessUp)
    }
    // Only the tab in front listens, so that under the iOS 26 host one composition takes a share, not three.
    if (nav.selectedTab == tab) LaunchedEffect(nav, fitness) { nav.openFitnessFromShares(fitness::setImportText) }
    ShellDrawer(
        open = nav.drawerOpen,
        selectedTab = tab,
        finance = financeState,
        weather = weatherState,
        fitness = fitnessState,
        onSelectTab = { route ->
            nav.drawerOpen = false
            nav.selectTab(route)
        },
        onOpenFinance = { origin -> nav.openApp(InnerApp.FINANCE, origin, tab) },
        onOpenWeather = { origin -> nav.openApp(InnerApp.WEATHER, origin, tab) },
        onOpenFitness = { origin -> nav.openApp(InnerApp.FITNESS, origin, tab) },
        onClose = { nav.drawerOpen = false },
    )
    if (nav.appHost == tab) {
        InnerAppOverlay(
            open = nav.appOpen,
            origin = nav.appOrigin,
            onCovering = { nav.appCovering = it },
            onClose = nav::closeApp,
        ) {
            when (nav.app) {
                InnerApp.FINANCE -> FinanceApp(
                    onClose = nav::closeApp,
                    active = nav.appOpen,
                    requestedTab = nav.financeTab,
                    onShowRequestedTab = nav::onFinanceTabShown,
                )

                InnerApp.WEATHER -> WeatherApp(onClose = nav::closeApp, active = nav.appOpen)

                InnerApp.FITNESS -> FitnessApp(onClose = nav::closeApp, active = nav.appOpen)
            }
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
 * the navigation bar, and everything from what the system draws at the window's sides in
 * landscape: a display cutout, and the navigation bar under three-button navigation.
 *
 * Under a native tab bar ([LocalNativeTabBar]) the floating nav is left out: the platform's bar
 * sits where it would, and [bottomNavClearance] already keeps the content clear of it.
 *
 * On a phone on its side ([showsNavRail]) the nav is a rail down the start edge instead, and the
 * content moves over to make room for it: there is no height there to float a bar over the foot
 * of every page. It comes and goes with [showBottomNav] as the pill does.
 *
 * [overlay] is drawn last, over the bars too: the layer a pinched camera card's video lifts into,
 * the drawer, and the app opened from it. [contentCovered] says an overlay hides everything beneath it;
 * [contentObscured] that one is over it (the drawer's scrim, or that app opening), so it
 * leaves the accessibility tree.
 *
 * [immersive] gives the content the whole window, cutout and all: a camera's video, full screen
 * with the phone on its side, which is black to the glass and places its own controls clear of
 * the edges.
 */
@Composable
internal fun ShellScaffold(
    showTopBar: Boolean,
    topBar: @Composable () -> Unit,
    selectedTab: TopLevelRoute,
    onSelectTab: (TopLevelRoute) -> Unit,
    overlay: @Composable () -> Unit = {},
    showBottomNav: Boolean = true,
    contentCovered: Boolean = false,
    contentObscured: Boolean = false,
    immersive: Boolean = false,
    content: @Composable () -> Unit,
) {
    // While an overlay covers the whole shell (the finance or weather app) the tab content isn't drawn at
    // all, so its live players stop; its saveable state is kept here and comes back with it.
    val saveableState = rememberSaveableStateHolder()
    val navRail = showsNavRail()
    // The content gives the rail its strip of the window, and takes it back as the rail leaves.
    val railClearance by animateDpAsState(
        targetValue = if (navRail && showBottomNav) NAV_RAIL_CLEARANCE else 0.dp,
        animationSpec = tween(NAV_TRANSITION_MS, easing = NavEnterEasing),
        label = "nav-rail-clearance",
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // Clear of what the system draws at the window's sides in landscape: a display
            // cutout, and the navigation bar under three-button navigation.
            .then(if (immersive) Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))),
    ) {
        // Under the drawer or an app opened from it the shell is out of reach for screen readers too,
        // so focus can't wander behind the scrim to a camera or the menu button.
        Box(Modifier.fillMaxSize().then(if (contentObscured) Modifier.clearAndSetSemantics {} else Modifier)) {
            Box(Modifier.fillMaxSize().padding(start = railClearance)) {
                // Back is the overlay's while one is up, even where the shell shows through it.
                if (!contentCovered) saveableState.SaveableStateProvider("shell-content") { BackScope(enabled = !contentObscured) { content() } }
            }

            // Fades over the nested screen's own header, which is the same height in the same
            // place, so the hand-over is a dissolve between two rows and nothing else moves.
            AnimatedVisibility(
                visible = showTopBar && !contentCovered,
                modifier = Modifier.align(Alignment.TopCenter),
                enter = fadeIn(tween(NAV_TRANSITION_MS, easing = LinearEasing)),
                exit = fadeOut(tween(NAV_TRANSITION_MS / 2, easing = LinearEasing)),
            ) {
                topBar()
            }

            // Slips away rather than vanishing when a full-screen page (the clip editor, car
            // tagging) takes over, so the tap that opened it isn't answered by a blink.
            if (!LocalNativeTabBar.current) {
                AnimatedVisibility(
                    visible = showBottomNav && !navRail,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 16.dp),
                    enter = fadeIn(tween(NAV_TRANSITION_MS, easing = LinearEasing)) + slideInVertically(tween(NAV_TRANSITION_MS, easing = NavEnterEasing)) { it / 2 },
                    exit = fadeOut(tween(NAV_TRANSITION_MS / 2, easing = LinearEasing)) + slideOutVertically(tween(NAV_TRANSITION_MS / 2)) { it / 2 },
                ) {
                    BottomNavBar(selected = selectedTab, onSelect = onSelectTab)
                }

                // The same nav, stood on end beside the content, centred in the height the top
                // bar leaves. It slips out sideways as the pill slips down.
                AnimatedVisibility(
                    visible = showBottomNav && navRail,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = NAV_RAIL_MARGIN, top = shellTopBarClearance())
                        .navigationBarsPadding(),
                    enter = fadeIn(tween(NAV_TRANSITION_MS, easing = LinearEasing)) + slideInHorizontally(tween(NAV_TRANSITION_MS, easing = NavEnterEasing)) { -it / 2 },
                    exit = fadeOut(tween(NAV_TRANSITION_MS / 2, easing = LinearEasing)) + slideOutHorizontally(tween(NAV_TRANSITION_MS / 2)) { -it / 2 },
                ) {
                    NavRail(selected = selectedTab, onSelect = onSelectTab)
                }
            }
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

/**
 * [appVersion] is what the route badge shows when tapped; unused until there is a route to badge.
 * [onMenu] opens the drawer. With [offline] the badge says so instead of naming the route: the
 * app is on what the device kept, and "Tailscale" there would read as "connected over Tailscale".
 */
@Composable
internal fun FrigateTopBar(activeConnection: ActiveConnection?, appVersion: String, onMenu: () -> Unit = {}, offline: Boolean = false) {
    // A Box, not a Row: the title is centred on the screen regardless of what sits at the ends,
    // so it doesn't shift when the status icon becomes the (wider) route badge — which is also
    // the moment the sign-in skeleton's bar dissolves into the real one.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // Opaque, status bar included: tab content scrolls underneath this bar.
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(horizontal = 24.dp, vertical = shellTopBarVerticalPadding()),
    ) {
        IconButton(onClick = onMenu, modifier = Modifier.size(48.dp).align(Alignment.CenterStart).testTag("shell_menu")) {
            Icon(Icons.Filled.Menu, contentDescription = stringResource(Res.string.shell_menu), tint = MaterialTheme.colorScheme.primary)
        }
        Text(
            text = stringResource(Res.string.shell_app_title),
            style = MaterialTheme.typography.headlineMedium.copy(letterSpacing = 0.03.em),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.align(Alignment.Center),
        )
        val route = activeConnection?.route
        if (route == null) {
            IconButton(onClick = {}, modifier = Modifier.size(48.dp).align(Alignment.CenterEnd)) {
                Icon(Icons.Filled.Sensors, contentDescription = stringResource(Res.string.shell_status), tint = MaterialTheme.colorScheme.primary)
            }
        } else {
            Box(modifier = Modifier.align(Alignment.CenterEnd)) { ConnectionRouteBadge(route, appVersion, offline) }
        }
    }
}

/**
 * "Local" (the local network) vs "Tailscale" at a glance — the former is the fast, direct video
 * path — or "Offline" while the server can't be reached on either. The names are the routes'
 * short ones: the badge shares the bar with the centred title, and "Local network" ran into it.
 * A tap drops down the app's version, which is how to tell which release is on the phone.
 */
@Composable
private fun ConnectionRouteBadge(route: ConnectionRoute, appVersion: String, offline: Boolean) {
    val tint = when {
        offline -> MaterialTheme.colorScheme.error
        route == ConnectionRoute.LOCAL_NETWORK -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.primary
    }
    var showVersion by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    // The pill is ~28dp tall, so the tap lands on a box grown to the 48dp minimum around it; the
    // ripple still draws on the pill alone.
    Box(
        modifier = Modifier
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClickLabel = stringResource(Res.string.shell_show_version),
            ) { showVersion = true }
            .minimumInteractiveComponentSize(),
    ) {
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(LocalFrigateExtraColors.current.glassFill, CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f), CircleShape)
                .indication(interactionSource, ripple())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (offline) {
                Icon(Icons.Filled.CloudOff, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
            } else if (route == ConnectionRoute.LOCAL_NETWORK) {
                Icon(Icons.Filled.Wifi, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
            } else {
                PulsingDot(color = tint, size = 6.dp, pulsing = false)
            }
            Text(text = stringResource(if (offline) Res.string.shell_offline else route.shortLabel), style = MaterialTheme.typography.labelSmall, color = tint)
        }
        DropdownMenu(expanded = showVersion, onDismissRequest = { showVersion = false }) {
            Text(
                text = stringResource(Res.string.shell_app_version, appVersion),
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
 *
 * [openedEventId] is that detection, so the screen can offer to tag its car when the classifier
 * didn't name it, and [tagCarOnOpen] opens the picker at once (a notification's "Tag car" button).
 */
private data class CameraDetailRoute(
    val cameraName: String,
    val warmStreamUrl: String?,
    val warmPosterUrl: String?,
    val openAtEpochSeconds: Double? = null,
    val openedEventId: String? = null,
    val tagCarOnOpen: Boolean = false,
)
private data class DetectionZonesRoute(val cameraName: String)
private data class CarTaggingRoute(val cameraName: String)

/**
 * The full-screen clip editor for [cameraName], around [anchorEpochSeconds] (what the camera
 * page was showing, or "now" at the live edge). [originX] / [originY] are where the scissors
 * were, as fractions of the window: where the splash that opens it lands, and where it drains
 * back to. Floats rather than an Offset so the route stays a plain, saveable value.
 */
private data class ClipEditorRoute(
    val cameraName: String,
    val anchorEpochSeconds: Double,
    val originX: Float,
    val originY: Float,
)

/**
 * The Home tab's own nested navigation: the camera list, and drilling into a camera's detail
 * screen. The [SharedTransitionLayout] lets the tapped camera's video animate from its grid
 * card into the detail screen's player (and back), keyed by camera name on both sides.
 *
 * The detail screen's own transition is fade-only ([SharedElementPush] / [SharedElementPop]),
 * so the video is the one thing that moves; the clip editor splashes in over it
 * ([SplashPush] / [SplashPop]); the zone editor beyond it uses the ordinary shared-axis slide. Navigation 3 takes the specs from the entry nearer the top of the stack,
 * which is why they are attached to the detail entry rather than to the display.
 *
 * [backStack] is owned by [ShellNavigation] (see there for why) and starts at [CameraListRoute].
 * [onOpenMoments] switches to the Moments tab, for the summary at the top of the camera list;
 * [scrollToTop] are the bottom nav's re-taps on Home while the list is up.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun HomeTabNav(backStack: SnapshotStateList<Any>, cardZoom: CameraCardZoomState, scrollToTop: ScrollToTopRequests, onOpenMoments: () -> Unit) {
    SharedTransitionLayout {
        NavDisplay(
            backStack = backStack,
            onBack = { backStack.removeLastOrNull() },
            transitionSpec = { sharedAxis(forward = true) },
            popTransitionSpec = { sharedAxis(forward = false) },
            predictivePopTransitionSpec = { predictiveSharedAxis(it) },
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
                        scrollToTopRequests = scrollToTop,
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
                        onClip = { anchor, origin ->
                            // A second tap while the splash is still landing is the same editor.
                            if (backStack.lastOrNull() !is ClipEditorRoute) {
                                backStack.add(ClipEditorRoute(route.cameraName, anchor, origin.x, origin.y))
                            }
                        },
                        openAtEpochSeconds = route.openAtEpochSeconds,
                        openedEventId = route.openedEventId,
                        tagCarOnOpen = route.tagCarOnOpen,
                    )
                }
                // Splashes in from the scissors rather than sliding: see SplashPush / SplashPop,
                // and ClipEditorScreen for the water itself.
                entry<ClipEditorRoute>(
                    metadata = NavDisplay.transitionSpec { SplashPush } +
                        NavDisplay.popTransitionSpec { SplashPop } +
                        NavDisplay.predictivePopTransitionSpec { SplashPop },
                ) { route ->
                    ClipEditorScreen(
                        cameraName = route.cameraName,
                        anchorEpochSeconds = route.anchorEpochSeconds,
                        splashOrigin = Offset(route.originX, route.originY),
                        onClose = { backStack.removeLastOrNull() },
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

/**
 * [BottomNavBar] for a phone on its side (see [showsNavRail]): the same three tabs in the same
 * glass, one above the other. The items carry the same test tags, so a test — or a finger —
 * finds a tab wherever the nav happens to be.
 */
@Composable
private fun NavRail(
    selected: TopLevelRoute,
    onSelect: (TopLevelRoute) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(NAV_RAIL_CORNER)
    Column(
        modifier = modifier
            .width(NAV_RAIL_WIDTH)
            .background(LocalFrigateExtraColors.current.glassFill, shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f), shape)
            .padding(8.dp)
            .testTag(NAV_RAIL_TEST_TAG),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TOP_LEVEL_ROUTES.forEach { route ->
            BottomNavItemView(
                route = route,
                isSelected = route == selected,
                onClick = { onSelect(route) },
                // Every item as wide as the rail, so the selected one's highlight doesn't change
                // width with the length of its label.
                modifier = Modifier.fillMaxWidth(),
                horizontalPadding = 4.dp,
            )
        }
    }
}

private val NAV_RAIL_CORNER = 28.dp

/** The side nav itself, for tests: there when the shell is laid out for a phone on its side. */
const val NAV_RAIL_TEST_TAG = "nav_rail"

@Composable
private fun BottomNavItemView(
    route: TopLevelRoute,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 24.dp,
) {
    val contentColor = if (isSelected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .clip(CircleShape)
            .let { if (isSelected) it.background(MaterialTheme.colorScheme.primaryContainer) else it }
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            // Which tab is up, for accessibility services and tests alike.
            .semantics { selected = isSelected }
            .testTag(bottomNavTestTag(route))
            .padding(horizontal = horizontalPadding, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val label = stringResource(route.label)
        Icon(route.icon, contentDescription = label, tint = contentColor, modifier = Modifier.size(22.dp))
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = contentColor, maxLines = 1)
    }
}

/**
 * The bottom nav item for [route], for tests (and, on Android, UiAutomator as a resource id). Its
 * label alone is ambiguous: "Home" and "Settings" also appear as text inside the tabs.
 */
fun bottomNavTestTag(route: TopLevelRoute): String = "bottom_nav_" + when (route) {
    TopLevelRoute.Home -> "home"
    TopLevelRoute.Moments -> "moments"
    TopLevelRoute.Settings -> "settings"
}
