package com.meticulouscreations.homesafe.navigation

import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where a door notification's tap lands: the Doors page, on the Home tab, above the camera list
 * (see `ShellNavigation.openDoorsFromNotifications`).
 *
 * `homesafe://doors?door=<id>`, beside [MomentDeepLink]'s `homesafe://moment?…`. [doorId] is the
 * door the push was about (the push's own `door` key); the page lists every door, so it opens the
 * same either way, and the id keeps one door's link apart from another's. Plain `homesafe://doors`
 * opens the page too.
 */
data class DoorDeepLink(val doorId: String? = null) {

    fun toUri(): String = "$SCHEME://$HOST" + doorId?.let { "?$KEY_DOOR=${it.encodeURLParameter()}" }.orEmpty()

    companion object {
        const val SCHEME = MomentDeepLink.SCHEME
        const val HOST = "doors"

        /** The push's own name for the door's id (`DoorAlert.KEY_DOOR`). */
        const val KEY_DOOR = "door"

        /** From a push's data or iOS `userInfo`, as string pairs; null when they aren't a door's (no `door` key). */
        fun from(values: (String) -> String?): DoorDeepLink? = values(KEY_DOOR)?.takeIf { it.isNotBlank() }?.let(::DoorDeepLink)

        /** From a `homesafe://doors…` URI; null for anything else. */
        fun fromUri(uri: String?): DoorDeepLink? {
            val url = uri?.let { runCatching { Url(it) }.getOrNull() } ?: return null
            if (url.protocol.name != SCHEME || url.host != HOST) return null
            return DoorDeepLink(url.parameters[KEY_DOOR]?.takeIf { it.isNotBlank() })
        }
    }
}

/**
 * The hand-off between a platform that heard a tap on a door notification and the shell that
 * opens the Doors page: a state rather than an event, for the reason [MomentDeepLinks] is one (a
 * tap that cold-starts the app arrives before the shell exists).
 */
object DoorDeepLinks {
    private val _pending = MutableStateFlow<DoorDeepLink?>(null)
    val pending: StateFlow<DoorDeepLink?> = _pending.asStateFlow()

    fun open(link: DoorDeepLink) {
        _pending.value = link
    }

    /** Clears [link] once it has been acted on; a newer one that arrived meanwhile is left for the next look. */
    fun consume(link: DoorDeepLink) {
        _pending.compareAndSet(link, null)
    }
}
