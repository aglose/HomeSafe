package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emptyFlow

/**
 * Material's navigation bar behaviour for re-selecting the destination already up: taps on the
 * bottom nav's item for a tab while it was already at its root screen (see
 * [ShellNavigation.reselections]), each of which scrolls that root screen back to the top.
 *
 * A class rather than a bare Flow so the screens that take it stay skippable.
 */
@Immutable
class ScrollToTopRequests internal constructor(private val taps: Flow<Unit>) {
    /** Runs [scrollToTop] for each request, cancelling one still animating when the next arrives. */
    suspend fun collect(scrollToTop: suspend () -> Unit) {
        taps.collectLatest { scrollToTop() }
    }

    companion object {
        /** No requests: for a screen shown outside the shell, such as a preview. */
        val NONE = ScrollToTopRequests(emptyFlow())
    }
}
