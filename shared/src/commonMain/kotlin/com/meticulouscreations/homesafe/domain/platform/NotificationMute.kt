package com.meticulouscreations.homesafe.domain.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * This phone's one switch over everything HomeSafe tells it (Settings → Notifications, "Mute all
 * notifications"). Muted, the relay is asked to push nothing here — cameras, doors, Away alerts,
 * the budget — and nothing the app checks for itself is posted: the cameras' poller and the
 * weather. Each app's own choices stay as they were, for when it is unmuted. A notification the
 * user asks for (the test notification, the weather sample) still comes.
 *
 * Kept by the platform rather than the database, so a push that wakes the app with nothing else
 * running can read it at once.
 */
interface NotificationMute {
    val muted: StateFlow<Boolean>

    fun setMuted(muted: Boolean)

    /** Never muted: for tests, and for a caller with nowhere to keep the switch. */
    object Never : NotificationMute {
        override val muted: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

        override fun setMuted(muted: Boolean) = Unit
    }
}
