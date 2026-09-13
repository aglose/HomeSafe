package com.meticulouscreations.homesafe

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the app is on screen. Driven by the root composable's lifecycle (see `App`): started
 * means the user can see the app, stopped means it went to the background (or the window was
 * hidden). Read by the live players to decide how long an unwatched stream stays connected —
 * a camera nobody is looking at because the user is on another tab is worth keeping warm for a
 * while, one nobody is looking at because the phone is in a pocket is not.
 *
 * Starts true: composition begins in the foreground, and a player that runs before the first
 * lifecycle event should assume the generous window rather than the stingy one.
 */
object AppVisibility {
    private val _inForeground = MutableStateFlow(true)
    val inForeground: StateFlow<Boolean> = _inForeground.asStateFlow()

    fun update(inForeground: Boolean) {
        _inForeground.value = inForeground
    }
}
