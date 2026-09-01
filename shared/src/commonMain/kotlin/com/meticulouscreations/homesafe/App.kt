package com.meticulouscreations.homesafe

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.meticulouscreations.homesafe.di.AppGraph
import com.meticulouscreations.homesafe.ui.screens.FrigateAppShell
import com.meticulouscreations.homesafe.ui.screens.SecureConnectionScreen
import com.meticulouscreations.homesafe.ui.theme.FrigateTheme

private data object SecureConnectionRoute
private data object AppShellRoute

@Composable
fun App(appGraph: AppGraph) {
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
