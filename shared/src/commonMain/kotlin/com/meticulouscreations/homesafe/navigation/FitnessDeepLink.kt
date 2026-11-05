package com.meticulouscreations.homesafe.navigation

import io.ktor.http.Url
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where a tap on the workout in progress lands from outside the app: Android's ongoing
 * notification and the alert that a rest is over, the iPhone's Live Activity, the Mac's island
 * and its menu-bar item. The fitness app opens over whatever was up, on the workout's own page.
 *
 * `homesafe://fitness?page=workout`; plain `homesafe://fitness` opens the app where it was left.
 */
data class FitnessDeepLink(val page: Page = Page.HOME) {
    /** Where in the fitness app, by name: this side of the hand-off knows nothing of its screens. */
    enum class Page { HOME, WORKOUT }

    fun toUri(): String = if (page == Page.HOME) URI else "$URI?$KEY_PAGE=${page.name.lowercase()}"

    companion object {
        const val HOST = "fitness"
        const val URI = "${MomentDeepLink.SCHEME}://$HOST"
        const val KEY_PAGE = "page"

        /** iOS `userInfo` key marking a notification as the fitness app's: its tap opens the workout in progress. */
        const val KEY = "homesafe_fitness"

        /** The workout in progress, full screen. */
        val Workout = FitnessDeepLink(Page.WORKOUT)

        /** From a `homesafe://fitness?…` URI; null for anything else. A page this build doesn't know opens the app where it was left. */
        fun fromUri(uri: String?): FitnessDeepLink? {
            val url = uri?.let { runCatching { Url(it) }.getOrNull() } ?: return null
            if (url.protocol.name != MomentDeepLink.SCHEME || url.host != HOST) return null
            val named = url.parameters[KEY_PAGE]
            return FitnessDeepLink(Page.entries.firstOrNull { it.name.equals(named, ignoreCase = true) } ?: Page.HOME)
        }
    }
}

/**
 * The hand-off between a platform that heard a tap on the workout in progress and the Compose
 * shell that opens it: a state rather than an event, for the reason [MomentDeepLinks] is one (a
 * tap that cold-starts the app arrives before the shell exists).
 */
object FitnessDeepLinks {
    private val _pending = MutableStateFlow<FitnessDeepLink?>(null)
    val pending: StateFlow<FitnessDeepLink?> = _pending.asStateFlow()

    fun open(link: FitnessDeepLink) {
        _pending.value = link
    }

    /** Clears [link] once it has been acted on; a newer one that arrived meanwhile is left for the next look. */
    fun consume(link: FitnessDeepLink) {
        _pending.compareAndSet(link, null)
    }

    /**
     * Whether the fitness app is in front with the workout on it, so an alert about that workout
     * (its rest running out) needn't come down over the page that already says so. Answered by the
     * fitness app, which this side can't see; false until it does.
     */
    var workoutOnScreen: () -> Boolean = { false }
}
