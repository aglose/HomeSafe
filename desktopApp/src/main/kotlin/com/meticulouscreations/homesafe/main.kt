package com.meticulouscreations.homesafe

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.meticulouscreations.homesafe.di.createAppGraph
import com.meticulouscreations.homesafe.ui.components.warmUpVideoDecoder

fun main() = application {
    // Before anything asks for video: unpacking FFmpeg's natives takes seconds on a cold machine,
    // and this hides all of it behind the connect screen.
    warmUpVideoDecoder()
    val appGraph = createAppGraph(platformContext = PlatformContext())
    Window(
        onCloseRequest = ::exitApplication,
        title = "HomeSafe",
    ) {
        App(appGraph)
    }
}
