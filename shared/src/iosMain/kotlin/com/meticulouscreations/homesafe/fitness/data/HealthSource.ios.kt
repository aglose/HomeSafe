package com.meticulouscreations.homesafe.fitness.data

import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.fitness.domain.HealthSource
import com.meticulouscreations.homesafe.fitness.domain.UnsupportedHealthSource

/** No health store here. HealthKit would do it (an HKHealthStore, which Google Health can also write to); not built yet. */
actual fun createHealthSource(platformContext: PlatformContext): HealthSource = UnsupportedHealthSource
