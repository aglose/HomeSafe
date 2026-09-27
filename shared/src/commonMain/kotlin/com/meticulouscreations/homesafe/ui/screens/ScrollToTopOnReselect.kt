package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

/**
 * Material's navigation bar behaviour for re-selecting the destination already up: the tab's
 * root screen scrolls back to the top. [requests] are those taps, from
 * [ShellNavigation.reselections] for the tab this list is the root of; call it beside the list
 * [state] drives.
 */
@Composable
internal fun ScrollToTopOnReselect(state: LazyListState, requests: Flow<Unit>) {
    LaunchedEffect(state, requests) { requests.collectLatest { state.animateScrollToItem(0) } }
}

/** [ScrollToTopOnReselect] for a page that scrolls as a whole rather than as a lazy list. */
@Composable
internal fun ScrollToTopOnReselect(state: ScrollState, requests: Flow<Unit>) {
    LaunchedEffect(state, requests) { requests.collectLatest { state.animateScrollTo(0) } }
}
