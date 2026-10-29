package com.meticulouscreations.homesafe

import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.network.ktor3.KtorNetworkFetcherFactory
import com.meticulouscreations.homesafe.di.AppGraph
import com.meticulouscreations.homesafe.navigation.TopLevelRoute
import com.meticulouscreations.homesafe.tracing.traceSection
import com.meticulouscreations.homesafe.ui.assistant.AssistantHost
import com.meticulouscreations.homesafe.ui.assistant.AssistantRoot
import com.meticulouscreations.homesafe.ui.components.LastSeenStillFetcher
import com.meticulouscreations.homesafe.ui.components.warmUpLivePlayback
import com.meticulouscreations.homesafe.ui.screens.CarsScreen
import com.meticulouscreations.homesafe.ui.screens.DebugAutofillCredentials
import com.meticulouscreations.homesafe.ui.screens.FrigateAppShell
import com.meticulouscreations.homesafe.ui.screens.LocalCarsPage
import com.meticulouscreations.homesafe.ui.screens.LocalShellApps
import com.meticulouscreations.homesafe.ui.screens.RootCrossfade
import com.meticulouscreations.homesafe.ui.screens.SecureConnectionScreen
import com.meticulouscreations.homesafe.ui.screens.ShellApps
import com.meticulouscreations.homesafe.ui.screens.ShellNavigation
import com.meticulouscreations.homesafe.ui.theme.FrigateTheme
import dev.zacsweers.metrox.viewmodel.LocalMetroViewModelFactory

private data object SecureConnectionRoute
private data object AppShellRoute

/**
 * The root composable. The only place the [AppGraph] is touched from UI code: it installs the
 * graph's view-model factory as [LocalMetroViewModelFactory], after which every screen obtains
 * its view model with `metroViewModel()` / `assistedMetroViewModel()` and never sees the graph.
 *
 * The iOS 26 host does not compose this: its sign-in screen and its tabs are separate Compose
 * view controllers under a native tab bar (see `IosShell.kt`), each wrapped in [AppChrome] with
 * [startAppServices] called once and the app's visibility read from UIKit instead of a lifecycle.
 */
@Composable
fun App(appGraph: AppGraph, debugAutofillCredentials: DebugAutofillCredentials? = null) {
    remember(appGraph) { startAppServices(appGraph) }

    // The root lifecycle is the app's: started while it is on screen, stopped when it goes to
    // the background. The live players use it to decide how long an unwatched stream stays
    // connected, and the connection repository to re-check the route and session after a long
    // absence — before the returning screens make their first requests.
    LifecycleStartEffect(appGraph) {
        AppVisibility.update(true)
        appGraph.connectionRepository.onAppVisibilityChanged(visible = true)
        onStopOrDispose {
            AppVisibility.update(false)
            appGraph.connectionRepository.onAppVisibilityChanged(visible = false)
        }
    }

    AppChrome(appGraph) {
        val backStack = remember { mutableStateListOf<Any>(SecureConnectionRoute) }
        NavDisplay(
            backStack = backStack,
            onBack = { backStack.removeLastOrNull() },
            // The sign-in screen already shows the shell's chrome while it authenticates
            // (see ShellSkeleton), so this dissolve only ever changes the content inside it.
            transitionSpec = { RootCrossfade },
            popTransitionSpec = { RootCrossfade },
            predictivePopTransitionSpec = { RootCrossfade },
            entryProvider = entryProvider {
                entry<SecureConnectionRoute> {
                    SecureConnectionScreen(
                        debugAutofillCredentials = debugAutofillCredentials,
                        onConnected = {
                            // Connecting replaces the back stack: the system back button
                            // should exit the app from the shell, not return to this screen.
                            backStack.clear()
                            backStack.add(AppShellRoute)
                        },
                    )
                }
                entry<AppShellRoute> { FrigateAppShell() }
            },
        )
    }
}

/**
 * What runs for as long as the app does, started once by whichever root hosts the UI. Every
 * start here is idempotent, so a second root (the iOS 26 host has several) costs nothing.
 */
@OptIn(ExperimentalCoilApi::class)
internal fun startAppServices(appGraph: AppGraph) = traceSection("startAppServices") {
    // Route Coil through the app's one shared Ktor client so image requests carry Frigate's
    // session cookie. Camera snapshots (`/api/<camera>/latest.jpg`) live on the authenticated
    // API port; with Coil's own cookie-less default client they 401 and never render.
    SingletonImageLoader.setSafe { context ->
        ImageLoader.Builder(context)
            .components {
                add(KtorNetworkFetcherFactory(appGraph.httpClient))
                // A Nest card's poster: only what this device kept, never a request (see LastSeenStill).
                add(LastSeenStillFetcher.Factory())
            }
            .build()
    }
    // Idle until the user turns notifications on in Settings; started here so it outlives any tab.
    appGraph.detectionAlertService.start()
    // Tell the relay who this install is once a server is active, and let the LAN and the
    // home geofence flip this phone's presence. Both idempotent; both outlive any tab.
    appGraph.deviceRegistrar.start()
    appGraph.presenceAutomation.start()
    // Home's players, started as soon as a sign-in knows where it is going (see LiveStreamPrefetcher).
    appGraph.liveStreamPrefetcher.start()
    // The weather app's background look at the forecast, for its notifications; scheduled to
    // match its switch (on unless turned off there). Idempotent.
    appGraph.weatherAlertCheck.start()
    // libwebrtc's one-off native start-up, done now behind the sign-in screen rather than
    // inside the first camera's join.
    warmUpLivePlayback(appGraph.platformContext)
}

/**
 * Everything a screen needs around it: the graph's view-model factory (see [App]), the theme,
 * an opaque ground of the app's own colour under every screen, so no transition can ever
 * expose the platform window behind Compose (a light theme on Android), and the assistant over
 * all of it, which a hold on any bottom bar opens (see [AssistantRoot]). A host whose bar is
 * native (the iOS 26 shell) hands in the [assistant] its bar opens.
 */
@Composable
internal fun AppChrome(appGraph: AppGraph, assistant: AssistantHost? = null, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalMetroViewModelFactory provides appGraph.metroViewModelFactory,
        LocalShellApps provides shellApps,
        LocalCarsPage provides carsPage,
    ) {
        FrigateTheme {
            AssistantRoot(modifier = Modifier.background(MaterialTheme.colorScheme.background), host = assistant ?: remember { AssistantHost() }) {
                content()
            }
        }
    }
}

/** The drawer and its apps, for the shell in :shared to draw (see LocalShellApps). One instance, so the local never changes. */
private val shellApps: @Composable (ShellNavigation, TopLevelRoute) -> Unit = { nav, tab -> ShellApps(nav, tab) }

/** The cars' map, for Home's back stack in :shared to draw (see LocalCarsPage). One instance, like [shellApps]. */
private val carsPage: @Composable (onBack: () -> Unit) -> Unit = { onBack -> CarsScreen(onBack) }
