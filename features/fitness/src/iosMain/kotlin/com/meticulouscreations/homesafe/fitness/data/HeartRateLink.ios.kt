package com.meticulouscreations.homesafe.fitness.data

import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.fitness.domain.HeartRateLink
import com.meticulouscreations.homesafe.fitness.domain.UnsupportedHeartRateLink

/** No heart-rate sensor here. CoreBluetooth would do it (a CBCentralManager scanning for 180D); not built yet. */
actual fun createHeartRateLink(platformContext: PlatformContext): HeartRateLink = UnsupportedHeartRateLink
