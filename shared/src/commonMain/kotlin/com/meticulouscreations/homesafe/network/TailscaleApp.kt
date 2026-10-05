package com.meticulouscreations.homesafe.network

import com.meticulouscreations.homesafe.PlatformContext

/**
 * The Tailscale app on this device, for the one thing the app asks of it: to come to the front
 * when it is what's needed (see `ConnectionProblem.TailscaleOff`). Connecting is still the
 * person's to do there; nothing here can switch the VPN on for them.
 */
interface TailscaleApp {
    /** Whether [open] can do anything here: Tailscale is installed and this platform lets one app open another by name. */
    val canOpen: Boolean

    /** Brings Tailscale to the front. False when it couldn't. */
    fun open(): Boolean
}

/** Builds the platform's [TailscaleApp]. Only Android can open it; elsewhere [TailscaleApp.canOpen] is false and the button isn't offered. */
expect fun createTailscaleApp(context: PlatformContext): TailscaleApp

/** For the platforms with no way to open another app by name. */
internal object NoTailscaleApp : TailscaleApp {
    override val canOpen: Boolean = false
    override fun open(): Boolean = false
}
