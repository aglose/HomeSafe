package com.meticulouscreations.homesafe

import androidx.compose.ui.window.ComposeUIViewController
import com.meticulouscreations.homesafe.di.createAppGraph
import com.meticulouscreations.homesafe.ui.screens.DebugAutofillCredentials

fun MainViewController(debugAutofillCredentials: DebugAutofillCredentials? = null) = ComposeUIViewController {
    val appGraph = createAppGraph(platformContext = PlatformContext())
    App(appGraph, debugAutofillCredentials = debugAutofillCredentials)
}
