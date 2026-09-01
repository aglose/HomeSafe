package com.meticulouscreations.homesafe

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.meticulouscreations.homesafe.di.createAppGraph

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val appGraph = createAppGraph(platformContext = PlatformContext())
    ComposeViewport {
        App(appGraph)
    }
}
