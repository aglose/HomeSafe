package com.meticulouscreations.homesafe.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * How long the keyboard has to stay put before what is under the field is brought up: it moves
 * every frame while it slides in, so this only runs out once it has come to rest.
 */
private const val KEYBOARD_SETTLE_MS = 120L

/**
 * For a field with things that belong to it underneath (a search's matches, the button that
 * submits it): put the requester this returns on the whole of it, with
 * `Modifier.bringIntoViewRequester`. While [typing] (the field has focus), the scrolling page
 * brings all of it up above the keyboard, not only the field, which is as far as the page would
 * scroll by itself (see "The keyboard" in AGENTS.md). Again once the keyboard has come to rest,
 * and again whenever [keys] change, such as the matches growing under the query.
 */
@Composable
fun rememberKeyboardClearance(typing: Boolean, vararg keys: Any?): BringIntoViewRequester {
    val requester = remember { BringIntoViewRequester() }
    val keyboard = WindowInsets.ime
    val density = LocalDensity.current
    LaunchedEffect(typing, *keys) {
        if (!typing) return@LaunchedEffect
        snapshotFlow { keyboard.getBottom(density) }.collectLatest {
            delay(KEYBOARD_SETTLE_MS)
            requester.bringIntoView()
        }
    }
    return requester
}
