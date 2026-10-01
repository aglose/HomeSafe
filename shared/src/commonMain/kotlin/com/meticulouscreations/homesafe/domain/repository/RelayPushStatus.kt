package com.meticulouscreations.homesafe.domain.repository

import kotlinx.coroutines.flow.StateFlow

/**
 * Whether the HomeSafe relay pushes alerts to this install. While it does, the relay decides what
 * this phone hears — its own policy for when someone is home — and the in-app poller's zone rules
 * decide nothing, so Settings stops offering them.
 */
interface RelayPushStatus {
    /** True once the relay has taken this install's push token; see `DeviceRegistrar.pushRegistered`. */
    val pushRegistered: StateFlow<Boolean>
}
