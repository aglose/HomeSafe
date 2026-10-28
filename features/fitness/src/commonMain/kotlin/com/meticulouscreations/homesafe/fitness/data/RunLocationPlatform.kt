package com.meticulouscreations.homesafe.fitness.data

import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.fitness.domain.RunLocation

/**
 * Each platform's GPS for recording a run; see `RunLocation.<platform>.kt`. Android has one, with
 * a foreground service to keep recording with the screen off. iOS answers "unsupported" for now,
 * and the fitness app offers no way to record a run there.
 */
expect fun createRunLocation(platformContext: PlatformContext): RunLocation
