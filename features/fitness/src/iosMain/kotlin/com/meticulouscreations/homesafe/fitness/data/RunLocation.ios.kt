package com.meticulouscreations.homesafe.fitness.data

import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.fitness.domain.RunLocation
import com.meticulouscreations.homesafe.fitness.domain.UnsupportedRunLocation

/** No run recording here yet. Core Location would do it, with the "location" background mode; not built. */
actual fun createRunLocation(platformContext: PlatformContext): RunLocation = UnsupportedRunLocation
