package com.meticulouscreations.homesafe.fitness.data

import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.fitness.domain.HealthSource
import com.meticulouscreations.homesafe.fitness.domain.UnsupportedHealthSource

/** No health store on a computer: Health Connect and HealthKit live on the phones. */
actual fun createHealthSource(platformContext: PlatformContext): HealthSource = UnsupportedHealthSource
