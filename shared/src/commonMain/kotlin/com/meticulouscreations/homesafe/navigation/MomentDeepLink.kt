package com.meticulouscreations.homesafe.navigation

import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where a notification tap lands: one detection, opened full screen on its camera at the
 * instant it started — the same destination as the Moments tab's full-screen button (see
 * `ShellNavigation.openDetection`).
 *
 * One encoding everywhere, so the in-app alerts, the relay's pushes, iOS `userInfo` and the
 * `homesafe://moment?…` URI all agree: [KEY_EVENT_ID], [KEY_CAMERA] and [KEY_START_TIME], which
 * are also the names the relay already uses in a push's data (see `relay/relay.py`).
 */
data class MomentDeepLink(
    val eventId: String,
    val cameraName: String,
    val startEpochSeconds: Double,
    /**
     * Open the car picker on arrival: the link behind a notification's "Tag car" button, for a
     * detection whose car the classifier didn't name. Only ever written when true, so every other
     * link reads as it always has.
     */
    val tagCar: Boolean = false,
) {
    /** As string pairs, for Android intent extras, iOS `userInfo` and a push's data. */
    fun toMap(): Map<String, String> = buildMap {
        put(KEY_EVENT_ID, eventId)
        put(KEY_CAMERA, cameraName)
        put(KEY_START_TIME, startEpochSeconds.toString())
        if (tagCar) put(KEY_TAG_CAR, TAG_CAR_YES)
    }

    /** `homesafe://moment?event_id=…&camera=…&start_time=…`, which the Android manifest routes to the app. */
    fun toUri(): String = "$SCHEME://$HOST?" + toMap().entries.joinToString("&") { (k, v) -> "$k=${v.encodeURLParameter()}" }

    companion object {
        const val SCHEME = "homesafe"
        const val HOST = "moment"
        const val KEY_EVENT_ID = "event_id"
        const val KEY_CAMERA = "camera"
        const val KEY_START_TIME = "start_time"
        const val KEY_TAG_CAR = "tag_car"
        private const val TAG_CAR_YES = "1"

        /**
         * From string pairs, or null when any part is missing. A push from an older relay may
         * carry no `event_id`; the camera and time are what the screen actually needs, so the id
         * falls back to empty rather than dropping the link.
         */
        fun from(values: (String) -> String?): MomentDeepLink? {
            val camera = values(KEY_CAMERA)?.takeIf { it.isNotBlank() } ?: return null
            val start = values(KEY_START_TIME)?.toDoubleOrNull()?.takeIf { it > 0 } ?: return null
            return MomentDeepLink(
                eventId = values(KEY_EVENT_ID).orEmpty(),
                cameraName = camera,
                startEpochSeconds = start,
                tagCar = values(KEY_TAG_CAR) == TAG_CAR_YES,
            )
        }

        /** From a `homesafe://moment?…` URI; null for anything else. */
        fun fromUri(uri: String): MomentDeepLink? {
            val url = runCatching { Url(uri) }.getOrNull() ?: return null
            if (url.protocol.name != SCHEME || url.host != HOST) return null
            return from { url.parameters[it] }
        }
    }
}

/**
 * The one hand-off between a platform that heard a notification tap (Android's `MainActivity`,
 * iOS's notification-center delegate) and the Compose shell that can act on it.
 *
 * A state, not an event stream, on purpose: a tap on a cold start arrives long before the shell
 * exists — the sign-in screen is up first — so the link has to wait here until the shell is
 * composed and [consume]s it. A second tap before then simply replaces the first.
 */
object MomentDeepLinks {
    private val _pending = MutableStateFlow<MomentDeepLink?>(null)
    val pending: StateFlow<MomentDeepLink?> = _pending.asStateFlow()

    fun open(link: MomentDeepLink) {
        _pending.value = link
    }

    /** Clears [link] once it has been acted on; a newer one that arrived meanwhile is left for the next look. */
    fun consume(link: MomentDeepLink) {
        _pending.compareAndSet(link, null)
    }
}
