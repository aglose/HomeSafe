package com.meticulouscreations.homesafe

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "HomeSafe",
    ) {
        App()
    }
}