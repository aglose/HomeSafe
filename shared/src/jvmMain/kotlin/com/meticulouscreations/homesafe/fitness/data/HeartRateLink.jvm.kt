package com.meticulouscreations.homesafe.fitness.data

import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.fitness.domain.HeartRateLink
import com.meticulouscreations.homesafe.fitness.domain.UnsupportedHeartRateLink

/** No heart-rate sensor here. The desktop has no Bluetooth API the app can reach without a native library. */
actual fun createHeartRateLink(platformContext: PlatformContext): HeartRateLink = UnsupportedHeartRateLink
