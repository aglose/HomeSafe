package com.meticulouscreations.homesafe.fitness.data

import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.fitness.domain.HeartRateLink

/**
 * Each platform's Bluetooth for a heart-rate sensor; see `HeartRateLink.<platform>.kt`. Android
 * has one. iOS, the desktop and the browser answer "unsupported" for now, and the fitness app
 * shows no heart rate there.
 */
expect fun createHeartRateLink(platformContext: PlatformContext): HeartRateLink
