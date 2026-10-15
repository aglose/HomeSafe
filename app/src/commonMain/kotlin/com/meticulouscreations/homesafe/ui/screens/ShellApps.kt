package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.meticulouscreations.homesafe.navigation.TopLevelRoute
import com.meticulouscreations.homesafe.weather.WeatherViewModel
import com.meticulouscreations.homesafe.weather.ui.WeatherApp
import com.meticulouscreations.homesafe.weather.ui.WeatherDrawerCard
import dev.zacsweers.metrox.viewmodel.metroViewModel

/**
 * The drawer, and the app opened from it (finance, weather or fitness) over everything else.
 * Their state is only fetched while this composition is on screen — under the iOS 26 host each
 * tab has its own, and only the visible one may run it.
 *
 * None of them needs the camera server (weather and the markets are public, fitness is all on
 * the device, and each, the atlas among them, opens on what the device kept), so the sign-in screen draws this too, with
 * a [ShellNavigation] of its own and [signedIn] false: the drawer then lists the apps alone, there
 * being no cameras to go to yet. That is how they are reached while Tailscale or the server isn't.
 */
// Overlays, not a laid-out element: each piece fills its parent's layer itself.
@Suppress("ktlint:compose:modifier-missing-check")
@Composable
fun ShellApps(nav: ShellNavigation, tab: TopLevelRoute, signedIn: Boolean = true) {
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
    // The health store is read only for the fitness app itself: the drawer's card shows none of it.
    val health: HealthViewModel = metroViewModel()
    LaunchedEffect(visible, fitnessUp) {
        health.setActive(visible && fitnessUp)
    }
    val atlas: AtlasViewModel = metroViewModel()
    val atlasState by atlas.uiState.collectAsStateWithLifecycle()
    val atlasUp = nav.isOpen(InnerApp.ATLAS)
    LaunchedEffect(visible, drawerShowing, atlasUp) {
        atlas.setActive(visible && (drawerShowing || atlasUp), full = atlasUp)
    }
    // Only the tab in front listens, so that under the iOS 26 host one composition takes a share, not three.
    if (nav.selectedTab == tab) LaunchedEffect(nav, fitness) { nav.openFitnessFromShares(fitness::setImportText) }
    ShellDrawer(
        open = nav.drawerOpen,
        selectedTab = tab,
        pull = nav.drawerPull,
        onSelectTab = { route ->
            nav.drawerOpen = false
            nav.selectTab(route)
        },
        onClose = { nav.drawerOpen = false },
        showHomeSecurity = signedIn,
    ) { animated ->
        WeatherDrawerCard(weatherState, animated = animated, onOpen = { origin -> nav.openApp(InnerApp.WEATHER, origin, tab) })
        Spacer(Modifier.height(12.dp))
        // The same lock: where someone has been is kept shut exactly when their money is.
        AtlasDrawerCard(atlasState, financeLockState.locked, animated = animated, onOpen = { origin -> nav.openApp(InnerApp.ATLAS, origin, tab) })
        Spacer(Modifier.height(12.dp))
        FitnessDrawerCard(fitnessState, animated = animated, onOpen = { origin -> nav.openApp(InnerApp.FITNESS, origin, tab) })
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
}
