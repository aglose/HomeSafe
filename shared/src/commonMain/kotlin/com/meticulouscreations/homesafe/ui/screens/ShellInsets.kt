package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The horizontal gutter every tab's content sits in. */
internal val TAB_CONTENT_HORIZONTAL_PADDING = 24.dp

/**
 * The shell's top bar, below the status bar: 12dp of padding either side of a 48dp button. The
 * nested screens' own headers (16dp around a 40dp icon button) come to the same height, which is
 * what lets one fade over the other without anything below shifting.
 */
private val SHELL_TOP_BAR_HEIGHT = 72.dp

/**
 * Room for the floating bottom nav (its own height plus the gap beneath it) so the last item of
 * a tab can scroll fully clear of it — before the system navigation bar is added on top.
 */
private val BOTTOM_NAV_CLEARANCE = 112.dp

/**
 * How far a tab's content starts from the top: the shell's top bar plus the status bar it sits
 * under. The bar floats over the content (so it can fade rather than collapse when a nested
 * screen opens), which is why this is content padding and not layout.
 */
@Composable
internal fun shellTopBarClearance(): Dp =
    SHELL_TOP_BAR_HEIGHT + WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

/**
 * How far a tab's scrolling content must keep clear at the bottom: the floating nav plus the
 * system navigation bar it floats above. Read on every composition so a gesture ↔ 3-button
 * nav switch, or a rotation, re-measures.
 */
@Composable
internal fun bottomNavClearance(): Dp =
    BOTTOM_NAV_CLEARANCE + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

/**
 * Content padding for a tab's scrolling list. Applied as *content* padding (not a modifier on
 * the list) so items scroll under the top bar, the floating nav and the system bars rather than
 * being clipped at a hard edge. [top] is the gap between the top bar and the first item.
 */
@Composable
internal fun tabContentPadding(top: Dp = 8.dp): PaddingValues = PaddingValues(
    start = TAB_CONTENT_HORIZONTAL_PADDING,
    end = TAB_CONTENT_HORIZONTAL_PADDING,
    top = shellTopBarClearance() + top,
    bottom = bottomNavClearance(),
)
