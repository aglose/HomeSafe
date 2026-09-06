package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.domain.platform.PushTokenProvider

/** Builds the platform's [PushTokenProvider] — the data layer's implementation of the domain's push-identity port. */
expect fun createPushTokenProvider(platformContext: PlatformContext): PushTokenProvider
