package com.meticulouscreations.homesafe.data

import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.domain.platform.AlertNotifier

/** Builds the platform's [AlertNotifier] — the data layer's implementation of the domain's notification port. */
expect fun createAlertNotifier(platformContext: PlatformContext): AlertNotifier
