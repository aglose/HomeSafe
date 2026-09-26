package com.meticulouscreations.homesafe.navigation

import androidx.compose.runtime.mutableStateListOf

/**
 * The top-level destinations (tabs) as the single back stack [androidx.navigation3.ui.NavDisplay]
 * renders, following Material's bottom navigation behaviour on Android: [startKey] is a fixed
 * start destination at the bottom, and any other tab sits alone above it. Switching tabs replaces
 * that one rather than stacking a history of every tab visited, so Back from any tab returns to
 * the start destination, and Back from there leaves the app.
 */
class TopLevelBackStack<T : Any>(private val startKey: T) {
    val backStack = mutableStateListOf(startKey)

    val topLevelKey: T get() = backStack.last()

    /** Brings [key] up above the start destination, in place of whichever tab was there. */
    fun switchTo(key: T) {
        if (key == topLevelKey) return
        while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
        if (key != startKey) backStack.add(key)
    }

    /** Back from a tab other than the start destination: to the start destination. */
    fun removeLast() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }
}
