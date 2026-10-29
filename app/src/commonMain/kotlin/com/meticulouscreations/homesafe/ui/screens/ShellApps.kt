package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import com.meticulouscreations.homesafe.atlas.AtlasViewModel
import com.meticulouscreations.homesafe.atlas.ui.AtlasApp
import com.meticulouscreations.homesafe.atlas.ui.AtlasDrawerCard
import com.meticulouscreations.homesafe.finance.FinanceLockViewModel
import com.meticulouscreations.homesafe.finance.FinanceViewModel
import com.meticulouscreations.homesafe.finance.ui.FinanceApp
import com.meticulouscreations.homesafe.finance.ui.FinanceDrawerCard
import com.meticulouscreations.homesafe.fitness.FitnessViewModel
import com.meticulouscreations.homesafe.fitness.HealthViewModel
import com.meticulouscreations.homesafe.fitness.ui.FitnessApp
import com.meticulouscreations.homesafe.fitness.ui.FitnessDrawerCard
import com.meticulouscreations.homesafe.navigation.ParkingDeepLinks
import com.meticulouscreations.homesafe.navigation.TopLevelRoute
import com.meticulouscreations.homesafe.weather.WeatherViewModel
import com.meticulouscreations.homesafe.weather.ui.WeatherApp
import com.meticulouscreations.homesafe.weather.ui.WeatherDrawerCard
import dev.zacsweers.metrox.viewmodel.metroViewModel

/**
 * The drawer, the app opened from it (finance, weather or fitness) over everything else, and
 * over that the map a parking notification opens ([ParkingMapOverlay]).
 * Their state is only fetched while this composition is on screen — under the iOS 26 host each
 * tab has its own, and only the visible one may run it.
 *
 * None of them needs the camera server (weather and the markets are public, fitness is all on
 * the device, and each, the atlas among them, opens on what the device kept), so the sign-in screen
 * draws this too, with a [ShellNavigation] of its own. That is how they are reached while
 * Tailscale or the server isn't.
 */
// Overlays, not a laid-out element: each piece fills its parent's layer itself.
@Suppress("ktlint:compose:modifier-missing-check")
@Composable
fun ShellApps(nav: ShellNavigation, tab: TopLevelRoute) {
    val finance: FinanceViewModel = metroViewModel()
    val financeState by finance.uiState.collectAsStateWithLifecycle()
    val financeLock: FinanceLockViewModel = metroViewModel()
    val financeLockState by financeLock.uiState.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val visible = lifecycle.isAtLeast(Lifecycle.State.STARTED)
    val financeUp = nav.isOpen(InnerApp.FINANCE)
    // The cards fetch as a swipe starts to draw the drawer out, not only once it is out.
    val drawerShowing = nav.drawerShowing
    LaunchedEffect(visible, drawerShowing, financeUp) {
        finance.setActive(visible && (drawerShowing || financeUp), full = financeUp)
    }
    val weather: WeatherViewModel = metroViewModel()
    val weatherState by weather.uiState.collectAsStateWithLifecycle()
    val weatherUp = nav.isOpen(InnerApp.WEATHER)
    LaunchedEffect(visible, drawerShowing, weatherUp) {
        weather.setActive(visible && (drawerShowing || weatherUp), full = weatherUp)
    }
    val fitness: FitnessViewModel = metroViewModel()
    val fitnessState by fitness.uiState.collectAsStateWithLifecycle()
    val fitnessUp = nav.isOpen(InnerApp.FITNESS)
    LaunchedEffect(visible, drawerShowing, fitnessUp) {
        fitness.setActive(visible && (drawerShowing || fitnessUp), full = fitnessUp)
    }
    // The health store is read for the fitness app, and for its card in the drawer, which shows today's readiness.
    val health: HealthViewModel = metroViewModel()
    val healthState by health.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(visible, drawerShowing, fitnessUp) {
        health.setActive(visible && (drawerShowing || fitnessUp))
    }
    val atlas: AtlasViewModel = metroViewModel()
    val atlasState by atlas.uiState.collectAsStateWithLifecycle()
    val atlasUp = nav.isOpen(InnerApp.ATLAS)
    LaunchedEffect(visible, drawerShowing, atlasUp) {
        atlas.setActive(visible && (drawerShowing || atlasUp), full = atlasUp)
    }
    // Only the tab in front listens, so that under the iOS 26 host one composition takes a share, not three.
    // In a sidebar the fitness app is a tab with a view model of its own, so the notes wait on [nav] for it.
    if (nav.selectedTab == tab) {
        LaunchedEffect(nav, fitness) { nav.openFitnessFromShares { notes -> if (nav.appsInSidebar) nav.fitnessNotes = notes else fitness.setImportText(notes) } }
    }
    ShellDrawer(
        open = nav.drawerOpen,
        pull = nav.drawerPull,
        onClose = { nav.drawerOpen = false },
    ) { animated ->
        WeatherDrawerCard(weatherState, animated = animated, onOpen = { origin -> nav.openApp(InnerApp.WEATHER, origin, tab) })
        Spacer(Modifier.height(12.dp))
        // The same lock: where someone has been is kept shut exactly when their money is.
        AtlasDrawerCard(atlasState, financeLockState.locked, animated = animated, onOpen = { origin -> nav.openApp(InnerApp.ATLAS, origin, tab) })
        Spacer(Modifier.height(12.dp))
        FitnessDrawerCard(fitnessState, healthState.body.readiness, animated = animated, onOpen = { origin -> nav.openApp(InnerApp.FITNESS, origin, tab) })
        Spacer(Modifier.height(12.dp))
        FinanceDrawerCard(financeState, financeLockState.locked, onOpen = { origin -> nav.openApp(InnerApp.FINANCE, origin, tab) })
    }
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

                InnerApp.WEATHER -> WeatherApp(onClose = nav::closeApp, active = nav.appOpen, link = nav.weatherLink, onShowLink = nav::onWeatherLinkShown)

                InnerApp.FITNESS -> FitnessApp(onClose = nav::closeApp, active = nav.appOpen)

                InnerApp.ATLAS -> AtlasApp(onClose = nav::closeApp, active = nav.appOpen)
            }
        }
    }
    // A parking notification's tap: the car's spot on a map, over everything, the open app too.
    // Only the tab in front draws it, as only it listens for shared notes.
    if (nav.selectedTab == tab) {
        val parking by ParkingDeepLinks.pending.collectAsStateWithLifecycle()
        ParkingMapOverlay(parking, weather.tiles, onClose = ParkingDeepLinks::consume)
    }
}

/**
 * One of the apps from the drawer as a destination of its own, for a host that lists them beside
 * the shell's tabs — an iPad's sidebar, where each is a tab hosting this (see `IosShell.kt`).
 * Nothing grows from a card here: the app is simply there while its tab is up, and fetches what it
 * shows for as long as it is on screen. Its own close button goes back to Home.
 *
 * The money and the places kept behind the lock lock again as the tab is left, as they do when the
 * overlay closes: a tab stays composed while another is up, so the lock can't wait for it to go.
 *
 * The app keeps clear of what the system draws at the window's sides — on iPadOS 26 that includes
 * the sidebar, which floats over the tab's view rather than beside it — as the shell does: the
 * apps were made to cover the whole screen, and would otherwise lay their pages out under it.
 */
// Fills its host's view, like the overlay it stands in for.
@Suppress("ktlint:compose:modifier-missing-check")
@Composable
fun ShellAppTab(nav: ShellNavigation, app: InnerApp) {
    val financeLock: FinanceLockViewModel = metroViewModel()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val visible = lifecycle.isAtLeast(Lifecycle.State.STARTED)
    if (app == InnerApp.FINANCE || app == InnerApp.ATLAS) {
        LaunchedEffect(visible) { if (!visible) financeLock.lock() }
    }
    val sides = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
    Box(Modifier.fillMaxSize().windowInsetsPadding(sides).consumeWindowInsets(sides)) {
        AppTabContent(nav, app, visible)
        // A parking notification's tap while this app's tab is the one up: the map comes up over it,
        // as it does over the shell (each sidebar tab is a composition of its own, so it needs its own).
        if (visible) {
            val weather: WeatherViewModel = metroViewModel()
            val parking by ParkingDeepLinks.pending.collectAsStateWithLifecycle()
            ParkingMapOverlay(parking, weather.tiles, onClose = ParkingDeepLinks::consume)
        }
    }
}

@Composable
private fun AppTabContent(nav: ShellNavigation, app: InnerApp, visible: Boolean) {
    val goHome = { nav.selectTab(TopLevelRoute.Home) }
    when (app) {
        InnerApp.FINANCE -> {
            val finance: FinanceViewModel = metroViewModel()
            ActiveWhile(visible) { finance.setActive(it, full = true) }
            FinanceApp(onClose = goHome, active = visible, requestedTab = nav.financeTab, onShowRequestedTab = nav::onFinanceTabShown)
        }

        InnerApp.WEATHER -> {
            val weather: WeatherViewModel = metroViewModel()
            ActiveWhile(visible) { weather.setActive(it, full = true) }
            WeatherApp(onClose = goHome, active = visible, link = nav.weatherLink, onShowLink = nav::onWeatherLinkShown)
        }

        InnerApp.FITNESS -> {
            val fitness: FitnessViewModel = metroViewModel()
            val health: HealthViewModel = metroViewModel()
            ActiveWhile(visible) {
                fitness.setActive(it, full = true)
                health.setActive(it)
            }
            // Notes shared into the app (see ShellApps, which hears of them) land on its import page.
            val notes = nav.fitnessNotes
            LaunchedEffect(notes) {
                if (notes != null) {
                    fitness.setImportText(notes)
                    nav.fitnessNotes = null
                }
            }
            FitnessApp(onClose = goHome, active = visible)
        }

        InnerApp.ATLAS -> {
            val atlas: AtlasViewModel = metroViewModel()
            ActiveWhile(visible) { atlas.setActive(it, full = true) }
            AtlasApp(onClose = goHome, active = visible)
        }
    }
}

/**
 * Keeps an app's view model working exactly while its tab is [visible], and stops it once the
 * tab goes (another app taken up beside the sidebar), so nothing of it polls from offscreen.
 */
@Composable
private fun ActiveWhile(visible: Boolean, setActive: (Boolean) -> Unit) {
    val current by rememberUpdatedState(setActive)
    LaunchedEffect(visible) { current(visible) }
    DisposableEffect(Unit) { onDispose { current(false) } }
}
