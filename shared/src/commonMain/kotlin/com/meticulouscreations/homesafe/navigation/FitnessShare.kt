package com.meticulouscreations.homesafe.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Workout notes shared into the app from another one (a note sent from a notes app's Share
 * menu), on their way to the fitness app's import page. A state, as [WeatherDeepLinks] is and
 * for the same reason: a share that cold-starts the app arrives before there is a shell to act
 * on it, and has to wait out the sign-in screen.
 *
 * A copy of a whole log sent from another install of the app comes the same way (`LogCopyText`);
 * the import page tells the two apart.
 */
object FitnessShares {
    /** More than any notes app's note, and room for a copy of a log of several thousand sets; anything longer is neither. */
    const val MAX_LENGTH = 200_000

    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()

    /**
     * The note's [text] under its [title], as the notes app handed them over. The title comes
     * first because it is usually the heading the exercises belong under ("Legs"), unless the
     * text already opens with it.
     */
    fun offer(text: String?, title: String? = null) {
        val body = text?.trim().orEmpty()
        if (body.isEmpty() || body.length > MAX_LENGTH) return
        val heading = title?.trim().orEmpty()
        _pending.value = if (heading.isEmpty() || body.startsWith(heading, ignoreCase = true)) body else heading + "\n" + body
    }

    fun consume() {
        _pending.value = null
    }
}
