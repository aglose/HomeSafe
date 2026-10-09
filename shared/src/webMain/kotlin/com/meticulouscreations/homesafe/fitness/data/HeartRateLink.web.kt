package com.meticulouscreations.homesafe.fitness.data

import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.fitness.domain.HeartRateLink
import com.meticulouscreations.homesafe.fitness.domain.UnsupportedHeartRateLink

/** No heart-rate sensor here. Web Bluetooth needs a secure page and a browser that has it (not Safari, not Firefox); not built. */
actual fun createHeartRateLink(platformContext: PlatformContext): HeartRateLink = UnsupportedHeartRateLink
