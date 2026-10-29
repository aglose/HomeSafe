package com.meticulouscreations.homesafe.fitness.data

import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.fitness.domain.RunLocation
import com.meticulouscreations.homesafe.fitness.domain.UnsupportedRunLocation

/** Nobody runs with a laptop: runs are recorded on the phone and shown here from what it kept. */
actual fun createRunLocation(platformContext: PlatformContext): RunLocation = UnsupportedRunLocation
