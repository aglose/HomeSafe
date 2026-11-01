package com.meticulouscreations.homesafe.charging.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The hand-off from a car on the Cars page to the Charging app, which opens on that car's charges.
 * The shell only opens the app ([ShellCommand.Open][com.meticulouscreations.homesafe.navigation.ShellCommand.Open]);
 * this says what to show once it is up, and is cleared as soon as it has been shown.
 */
object ChargingLink {
    private val asked = MutableStateFlow<String?>(null)

    val car: StateFlow<String?> = asked.asStateFlow()

    fun show(car: String) {
        asked.value = car
    }

    fun shown() {
        asked.value = null
    }
}
