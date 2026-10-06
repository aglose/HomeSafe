package com.meticulouscreations.homesafe.network

import com.meticulouscreations.homesafe.PlatformContext

/** iOS asks by itself, the first time the app opens a connection to a local address; there is no request to make ahead of it. */
actual fun createLocalNetworkAccess(context: PlatformContext): LocalNetworkAccess = UnrestrictedLocalNetworkAccess
