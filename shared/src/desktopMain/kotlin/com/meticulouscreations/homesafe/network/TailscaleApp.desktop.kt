package com.meticulouscreations.homesafe.network

import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.isMacOs
import java.io.File

/** Tailscale's Mac app, from the App Store or Tailscale's own download, brought to the front with `open`. */
private object MacTailscaleApp : TailscaleApp {
    private val installed = listOf("/Applications/Tailscale.app", "${System.getProperty("user.home")}/Applications/Tailscale.app")

    override val canOpen: Boolean get() = installed.any { File(it).exists() }

    override fun open(): Boolean = runCatching { ProcessBuilder("open", "-a", "Tailscale").start().waitFor() == 0 }.getOrDefault(false)
}

/** Only a Mac has an app to open; elsewhere Tailscale is a daemon with nothing to bring forward. */
actual fun createTailscaleApp(context: PlatformContext): TailscaleApp = if (isMacOs) MacTailscaleApp else NoTailscaleApp
