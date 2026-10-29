package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.PlatformContext
import java.awt.Desktop
import java.net.URI

/** Google Home on the web, in the browser: there is no Google Home app for a computer. */
private const val GOOGLE_HOME_WEB_URL = "https://home.google.com"

private object WebGoogleHomeApp : GoogleHomeApp {
    override fun open(): Boolean = runCatching {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) return false
        Desktop.getDesktop().browse(URI(GOOGLE_HOME_WEB_URL))
        true
    }.getOrDefault(false)
}

actual fun createGoogleHomeApp(context: PlatformContext): GoogleHomeApp = WebGoogleHomeApp
