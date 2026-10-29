package com.meticulouscreations.homesafe.fitness.data

import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.fitness.domain.HeartRateLink
import com.meticulouscreations.homesafe.fitness.domain.UnsupportedHeartRateLink

/** No heart-rate strap pairs with the desktop app: that is the phone's, during a workout. */
actual fun createHeartRateLink(platformContext: PlatformContext): HeartRateLink = UnsupportedHeartRateLink
