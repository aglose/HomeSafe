package com.meticulouscreations.homesafe.fitness.data

import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.fitness.domain.HealthSource
import com.meticulouscreations.homesafe.fitness.domain.UnsupportedHealthSource

/** No health store in a browser: what a band records stays on the phone it syncs to. */
actual fun createHealthSource(platformContext: PlatformContext): HealthSource = UnsupportedHealthSource
