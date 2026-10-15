package com.meticulouscreations.homesafe

import androidx.compose.ui.window.ComposeUIViewController
import com.meticulouscreations.homesafe.ui.screens.DebugAutofillCredentials

// PascalCase on purpose: this is the entry point Swift calls (iOSApp.swift), and Kotlin/Native
// exports the name verbatim, so renaming it would rename the Swift API.
@Suppress("ktlint:standard:function-naming")
fun MainViewController(debugAutofillCredentials: DebugAutofillCredentials? = null) = ComposeUIViewController {
    // The process-wide graph from IosApp: built at launch so background wakes have it too.
    App(IosApp.graph, debugAutofillCredentials = debugAutofillCredentials)
}
