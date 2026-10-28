package com.meticulouscreations.homesafe.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * How long the keyboard has to stay put before what is under the field is brought up: it moves
 * every frame while it slides in, so this only runs out once it has come to rest.
 */
private const val KEYBOARD_SETTLE_MS = 120L

/** What [rememberKeyboardClearance] brings up: put [modifier] on the whole of it. */
@Stable
class KeyboardClearance internal constructor() {
    internal val requester = BringIntoViewRequester()
    internal var size = IntSize.Zero

    val modifier: Modifier = Modifier.bringIntoViewRequester(requester).onSizeChanged { size = it }
}

/**
 * For a field with things that belong to it underneath (a search's matches, the button that
 * submits it): put the clearance's [KeyboardClearance.modifier] on the whole of it. While [typing]
 * (the field has focus), the scrolling page brings all of it up above the keyboard, not only the
 * field, which is as far as the page would scroll by itself (see "The keyboard" in AGENTS.md).
 * Again once the keyboard has come to rest, and again whenever [keys] change, such as the matches
 * growing under the query.
 *
 * [above] is how far a bar floating over the top of the page reaches into it (the page's top
 * content padding). With it, something too tall for the room the keyboard leaves comes to rest
 * just under that bar, the field near its top still in sight, rather than with its top (and the
 * field) under the bar; the rest is a scroll away.
 */
@Composable
fun rememberKeyboardClearance(typing: Boolean, vararg keys: Any?, above: Dp = 0.dp): KeyboardClearance {
    val clearance = remember { KeyboardClearance() }
    val keyboard = WindowInsets.ime
    val density = LocalDensity.current
    val abovePx = with(density) { above.toPx() }
    LaunchedEffect(typing, abovePx, *keys) {
        if (!typing) return@LaunchedEffect
        snapshotFlow { keyboard.getBottom(density) }.collectLatest {
            delay(KEYBOARD_SETTLE_MS)
            val size = clearance.size
            clearance.requester.bringIntoView(Rect(0f, -abovePx, size.width.toFloat(), size.height.toFloat()))
        }
    }
    return clearance
}
