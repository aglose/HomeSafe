package com.meticulouscreations.homesafe

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.meticulouscreations.homesafe.di.createAppGraph

fun main() = application {
    val appGraph = createAppGraph(platformContext = PlatformContext())
    Window(
        onCloseRequest = ::exitApplication,
        title = "HomeSafe",
    ) {
        App(appGraph)
    }
}
