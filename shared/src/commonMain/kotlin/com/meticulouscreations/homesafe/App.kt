package com.meticulouscreations.homesafe

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.network.ktor3.KtorNetworkFetcherFactory
import com.meticulouscreations.homesafe.di.AppGraph
import com.meticulouscreations.homesafe.ui.screens.FrigateAppShell
import com.meticulouscreations.homesafe.ui.screens.SecureConnectionScreen
import com.meticulouscreations.homesafe.ui.theme.FrigateTheme

private data object SecureConnectionRoute
private data object AppShellRoute

@OptIn(ExperimentalCoilApi::class)
@Composable
fun App(appGraph: AppGraph) {
    // Route Coil through the app's one shared Ktor client so image requests carry Frigate's
    // session cookie. Camera snapshots (`/api/<camera>/latest.jpg`) live on the authenticated
    // API port; with Coil's own cookie-less default client they 401 and never render.
    remember(appGraph) {
        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context)
                .components { add(KtorNetworkFetcherFactory(appGraph.httpClient)) }
                .build()
        }
    }

    FrigateTheme {
        val backStack = remember { mutableStateListOf<Any>(SecureConnectionRoute) }

        NavDisplay(
            backStack = backStack,
            onBack = { backStack.removeLastOrNull() },
            entryProvider = entryProvider {
                entry<SecureConnectionRoute> {
                    SecureConnectionScreen(
                        connectToServerUseCase = appGraph.connectToServerUseCase,
                        signInWithBiometricsUseCase = appGraph.signInWithBiometricsUseCase,
                        saveBiometricCredentialsUseCase = appGraph.saveBiometricCredentialsUseCase,
                        forgetBiometricCredentialsUseCase = appGraph.forgetBiometricCredentialsUseCase,
                        observeMostRecentConnectionUseCase = appGraph.observeMostRecentConnectionUseCase,
                        connectionRepository = appGraph.connectionRepository,
                        onConnected = {
                            // Connecting replaces the back stack: the system back button
                            // should exit the app from the shell, not return to this screen.
                            backStack.clear()
                            backStack.add(AppShellRoute)
                        },
                    )
                }
                entry<AppShellRoute> { FrigateAppShell(appGraph = appGraph) }
            },
        )
    }
}
