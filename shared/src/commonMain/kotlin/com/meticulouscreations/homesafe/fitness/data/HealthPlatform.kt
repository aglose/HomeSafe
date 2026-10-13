package com.meticulouscreations.homesafe.fitness.data

import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.fitness.domain.HealthSource

/**
 * Each platform's health store; see `HealthSource.<platform>.kt`. Android has Health Connect.
 * iOS, the desktop and the browser answer "unsupported" for now, and the fitness app shows no
 * steps, sleep, readiness or runs there.
 */
expect fun createHealthSource(platformContext: PlatformContext): HealthSource
