package com.meticulouscreations.homesafe.network

import com.meticulouscreations.homesafe.PlatformContext

/** A browser tab has no permission the page can ask for here; the request goes out and the browser decides. */
actual fun createLocalNetworkAccess(context: PlatformContext): LocalNetworkAccess = LocalNetworkAccess { true }
