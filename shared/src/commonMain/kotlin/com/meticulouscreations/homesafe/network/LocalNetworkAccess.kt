package com.meticulouscreations.homesafe.network

import com.meticulouscreations.homesafe.PlatformContext

/**
 * The operating system's say over whether this app may talk to addresses on the Wi-Fi it is on,
 * which is what the server's LAN address is. Where the system withholds that until the user
 * allows it, a probe of the LAN address doesn't fail with anything that says so: it times out,
 * exactly as it does away from home, and the app quietly settles on Tailscale. So the question
 * is put before the first probe that matters (see `ConnectionRepositoryImpl.connect`).
 */
fun interface LocalNetworkAccess {
    /**
     * Asks the user for access when the system wants them asked and they haven't answered yet,
     * and waits for the answer. True when the app may use the local network afterwards. Returns
     * at once, without a prompt, wherever there is nothing to ask or no screen to ask from.
     */
    suspend fun request(): Boolean
}

/**
 * Builds the platform's [LocalNetworkAccess]. Android 17 asks for a permission; everywhere else
 * there is nothing for the app to ask (iOS puts its own prompt up on the first connection).
 */
expect fun createLocalNetworkAccess(context: PlatformContext): LocalNetworkAccess
