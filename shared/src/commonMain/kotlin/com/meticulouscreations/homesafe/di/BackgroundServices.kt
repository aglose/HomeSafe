package com.meticulouscreations.homesafe.di

import com.meticulouscreations.homesafe.data.DeviceRegistrar
import com.meticulouscreations.homesafe.data.PushedAlertActions
import com.meticulouscreations.homesafe.data.PushedAlertMedia
import com.meticulouscreations.homesafe.domain.repository.PresenceAutomation

/**
 * What code the OS wakes with no screen (Android's receivers and workers, a push arriving)
 * needs from the app graph. Declared here, beside that code, because the graph itself is in
 * :app, which builds on this module; AppGraph implements it. See BackgroundGraph.
 */
interface BackgroundServices {
    val deviceRegistrar: DeviceRegistrar
    val pushedAlertMedia: PushedAlertMedia
    val pushedAlertActions: PushedAlertActions
    val presenceAutomation: PresenceAutomation
}
