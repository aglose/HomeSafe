package com.meticulouscreations.homesafe.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where a weather notification's tap lands: the weather app, opened over whatever was up.
 * `homesafe://weather`, which the Android manifest routes to the app, as it does a moment's link
 * (see [MomentDeepLink]).
 */
object WeatherDeepLink {
    const val HOST = "weather"
    const val URI = "${MomentDeepLink.SCHEME}://$HOST"

    /** `userInfo` key marking an iOS notification as the weather app's. */
    const val KEY = "homesafe_weather"

    fun matches(uri: String?): Boolean = uri != null && (uri == URI || uri.startsWith("$URI?") || uri.startsWith("$URI/"))
}

/**
 * The hand-off between the platform that heard a weather notification's tap and the shell that
 * opens the weather app. A state, as [MomentDeepLinks] is and for the same reason: a tap that
 * cold-starts the app arrives before there is a shell to act on it.
 */
object WeatherDeepLinks {
    private val _pending = MutableStateFlow(false)
    val pending: StateFlow<Boolean> = _pending.asStateFlow()

    fun open() {
        _pending.value = true
    }

    fun consume() {
        _pending.value = false
    }
}
