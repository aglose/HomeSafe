package com.meticulouscreations.homesafe

import androidx.compose.ui.window.ComposeUIViewController
import com.meticulouscreations.homesafe.di.createAppGraph
import com.meticulouscreations.homesafe.ui.screens.DebugAutofillCredentials

// PascalCase on purpose: this is the entry point Swift calls (iOSApp.swift), and Kotlin/Native
// exports the name verbatim, so renaming it would rename the Swift API.
@Suppress("ktlint:standard:function-naming")
fun MainViewController(debugAutofillCredentials: DebugAutofillCredentials? = null) = ComposeUIViewController {
    val appGraph = createAppGraph(platformContext = PlatformContext())
    App(appGraph, debugAutofillCredentials = debugAutofillCredentials)
}
