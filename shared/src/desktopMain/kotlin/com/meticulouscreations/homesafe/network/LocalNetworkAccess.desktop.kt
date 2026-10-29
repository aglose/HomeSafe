package com.meticulouscreations.homesafe.network

import com.meticulouscreations.homesafe.PlatformContext

/**
 * Nothing for the app to ask: macOS 15's local-network prompt, where it applies to an app like
 * this one, is put up by the system on the first connection, as iOS's is.
 */
actual fun createLocalNetworkAccess(context: PlatformContext): LocalNetworkAccess = UnrestrictedLocalNetworkAccess
