package com.meticulouscreations.homesafe

import androidx.compose.ui.window.ComposeUIViewController
import com.meticulouscreations.homesafe.di.createAppGraph

fun MainViewController() = ComposeUIViewController {
    val appGraph = createAppGraph(platformContext = PlatformContext())
    App(appGraph)
}
