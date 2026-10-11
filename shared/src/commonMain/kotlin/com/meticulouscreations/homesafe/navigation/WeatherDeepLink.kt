package com.meticulouscreations.homesafe.navigation

import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where a weather notification's tap lands: the weather app, opened over whatever was up, on
 * the part of it the notification was about. Rain on the way opens Today, tomorrow's outlook
 * the Forecast, the "Radar" button the radar, and a government warning the warning itself.
 *
 * `homesafe://weather?view=radar&place=current&alert=KPQR.TO.W.0012`, which the Android manifest
 * routes to the app as it does a moment's link (see [MomentDeepLink]). Every part is optional:
 * plain `homesafe://weather` opens the app on Today, as it always has.
 */
data class WeatherDeepLink(
    val view: View = View.TODAY,
    /** The place the notification was for (a `Place.id`), to turn to if the app has it. */
    val placeId: String? = null,
    /** A government warning's key (`WeatherAlert.key`), to open in full if it is still in force. */
    val alertKey: String? = null,
) {
    /** The weather app's tabs, by name: this side of the hand-off knows nothing of its screens. */
    enum class View { TODAY, FORECAST, RADAR }

    /** As string pairs, for iOS `userInfo`; [KEY] marks them as a weather notification's. */
    fun toMap(): Map<String, String> = buildMap {
        put(KEY, "1")
        put(KEY_VIEW, view.name.lowercase())
        placeId?.let { put(KEY_PLACE, it) }
        alertKey?.let { put(KEY_ALERT, it) }
    }

    fun toUri(): String = "$URI?" + (toMap() - KEY).entries.joinToString("&") { (k, v) -> "$k=${v.encodeURLParameter()}" }

    companion object {
        const val HOST = "weather"
        const val URI = "${MomentDeepLink.SCHEME}://$HOST"

        /** `userInfo` key marking an iOS notification as the weather app's. */
        const val KEY = "homesafe_weather"
        const val KEY_VIEW = "view"
        const val KEY_PLACE = "place"
        const val KEY_ALERT = "alert"

        /** From string pairs; a view this build doesn't know opens Today. */
        fun from(values: (String) -> String?): WeatherDeepLink = WeatherDeepLink(
            view = View.entries.firstOrNull { it.name.equals(values(KEY_VIEW), ignoreCase = true) } ?: View.TODAY,
            placeId = values(KEY_PLACE)?.takeIf { it.isNotBlank() },
            alertKey = values(KEY_ALERT)?.takeIf { it.isNotBlank() },
        )

        /** From a `homesafe://weather…` URI; null for anything else. */
        fun fromUri(uri: String?): WeatherDeepLink? {
            val url = uri?.let { runCatching { Url(it) }.getOrNull() } ?: return null
            if (url.protocol.name != MomentDeepLink.SCHEME || url.host != HOST) return null
            return from { url.parameters[it] }
        }
    }
}

/**
 * The hand-off between the platform that heard a weather notification's tap and the shell that
 * opens the weather app. A state, as [MomentDeepLinks] is and for the same reason: a tap that
 * cold-starts the app arrives before there is a shell to act on it.
 */
object WeatherDeepLinks {
    private val _pending = MutableStateFlow<WeatherDeepLink?>(null)
    val pending: StateFlow<WeatherDeepLink?> = _pending.asStateFlow()

    fun open(link: WeatherDeepLink = WeatherDeepLink()) {
        _pending.value = link
    }

    /** Clears [link] once it has been acted on; a newer one that arrived meanwhile is left for the next look. */
    fun consume(link: WeatherDeepLink) {
        _pending.compareAndSet(link, null)
    }
}
