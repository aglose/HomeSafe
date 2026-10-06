package com.meticulouscreations.homesafe.network

import com.meticulouscreations.homesafe.PlatformContext

/** Desktop puts nothing between an app and the local network. */
actual fun createLocalNetworkAccess(context: PlatformContext): LocalNetworkAccess = UnrestrictedLocalNetworkAccess
