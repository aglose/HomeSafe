package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.ui.isCompactLandscape
import com.meticulouscreations.homesafe.ui.windowWidth

/** The horizontal gutter every tab's content sits in, at the least: see [contentGutter]. */
internal val TAB_CONTENT_HORIZONTAL_PADDING = 24.dp

/**
 * How wide a column of rows, cards and settings gets before it stops growing and centres
 * instead. A list row stretched across a tablet, or a phone on its side, puts its label and its
 * switch a hand's width apart.
 */
internal val READABLE_CONTENT_WIDTH = 640.dp

/**
 * The shell's top bar, below the status bar: 12dp of padding either side of a 48dp button. The
 * nested screens' own headers (16dp around a 40dp icon button) come to the same height, which is
 * what lets one fade over the other without anything below shifting.
 */
private val SHELL_TOP_BAR_HEIGHT = 72.dp

/**
 * What both give back in a window with no height to spare (a phone on its side, see
 * [isCompactLandscape]): 8dp off the padding above and below, in the bar and in the nested
 * headers alike, so they still come to one height.
 */
private val COMPACT_HEADER_TRIM = 8.dp

/**
 * Room for the floating bottom nav (its own height plus the gap beneath it) so the last item of
 * a tab can scroll fully clear of it — before the system navigation bar is added on top.
 */
private val BOTTOM_NAV_CLEARANCE = 112.dp

/**
 * The gap between a tab's last item and a platform-drawn tab bar. The bar itself is already in
 * the system inset (see [LocalNativeTabBar]), so this is only the breathing room above it.
 */
private val NATIVE_TAB_BAR_GAP = 16.dp

/** The gap under a tab's last item when the nav is at the side ([showsNavRail]) and nothing floats over the foot of the page. */
private val NAV_RAIL_BOTTOM_GAP = 24.dp

/** The side nav's own width; its three labels are one short word each. */
internal val NAV_RAIL_WIDTH = 88.dp

/** How far the side nav sits in from the window's edge (or the cutout beside it). */
internal val NAV_RAIL_MARGIN = 16.dp

/**
 * How far the shell moves its content over to make room for the side nav. The content's own
 * gutter ([contentGutter]) is the gap between the two.
 */
internal val NAV_RAIL_CLEARANCE = NAV_RAIL_MARGIN + NAV_RAIL_WIDTH

/**
 * True when the platform draws the tab bar rather than Compose — iOS 26's Liquid Glass
 * `TabView`, with each tab hosting its own Compose view controller (see `IosShell.kt`). The
 * shell then draws no bottom nav of its own, and [bottomNavClearance] trusts the bottom system
 * inset, which on iOS already includes the bar (it is part of the hosting view's safe area),
 * instead of reserving room for the Compose one.
 */
internal val LocalNativeTabBar = staticCompositionLocalOf { false }

/**
 * Whether the shell's nav is a rail down the start edge rather than a pill along the bottom:
 * a phone on its side, where a bar across the foot of the window (and the clearance every list
 * keeps for it) would take a third of the height there is. Never under a native tab bar, which
 * is the platform's to place.
 */
@Composable
internal fun showsNavRail(): Boolean = !LocalNativeTabBar.current && isCompactLandscape()

/** The padding above and below the shell's top bar's 48dp buttons. */
@Composable
internal fun shellTopBarVerticalPadding(): Dp = if (isCompactLandscape()) 12.dp - COMPACT_HEADER_TRIM else 12.dp

/** The padding above and below a nested screen's own header row, which stands where the shell's bar was. */
@Composable
internal fun nestedHeaderVerticalPadding(): Dp = if (isCompactLandscape()) 16.dp - COMPACT_HEADER_TRIM else 16.dp

/**
 * How far a tab's content starts from the top: the shell's top bar plus the status bar it sits
 * under. The bar floats over the content (so it can fade rather than collapse when a nested
 * screen opens), which is why this is content padding and not layout.
 */
@Composable
internal fun shellTopBarClearance(): Dp {
    val bar = if (isCompactLandscape()) SHELL_TOP_BAR_HEIGHT - COMPACT_HEADER_TRIM * 2 else SHELL_TOP_BAR_HEIGHT
    return bar + WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
}

/**
 * How far a tab's scrolling content must keep clear at the bottom: the floating nav plus the
 * system navigation bar it floats above — or, under a native tab bar, the bar's own inset plus
 * a small gap, and with the nav at the side ([showsNavRail]) only a gap. Read on every
 * composition so a gesture ↔ 3-button nav switch, or a rotation, re-measures.
 */
@Composable
internal fun bottomNavClearance(): Dp {
    val systemBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val chrome = when {
        LocalNativeTabBar.current -> NATIVE_TAB_BAR_GAP
        isCompactLandscape() -> NAV_RAIL_BOTTOM_GAP
        else -> BOTTOM_NAV_CLEARANCE
    }
    return chrome + systemBottom
}

/**
 * The gutter either side of a screen's content: [TAB_CONTENT_HORIZONTAL_PADDING] on a phone
 * held upright, and once the shell's content area is wider than [maxWidth], whatever centres a
 * column that wide in it — never less than [min]. A gutter rather than a narrower list, so the
 * whole width still scrolls — on a phone on its side the thumbs are at the edges.
 *
 * Worked out from the window rather than measured: the shell's content area is the window less
 * what the system draws at its sides and less the side nav, when that is up (see ShellScaffold).
 */
@Composable
internal fun contentGutter(maxWidth: Dp = READABLE_CONTENT_WIDTH, min: Dp = TAB_CONTENT_HORIZONTAL_PADDING): Dp {
    val direction = LocalLayoutDirection.current
    val safe = WindowInsets.safeDrawing.asPaddingValues()
    val rail = if (showsNavRail()) NAV_RAIL_CLEARANCE else 0.dp
    val available = windowWidth() - safe.calculateStartPadding(direction) - safe.calculateEndPadding(direction) - rail
    return ((available - maxWidth) / 2).coerceAtLeast(min)
}

/**
 * Content padding for a tab's scrolling list. Applied as *content* padding (not a modifier on
 * the list) so items scroll under the top bar, the floating nav and the system bars rather than
 * being clipped at a hard edge. [top] is the gap between the top bar and the first item;
 * [maxWidth] is how wide the list's items get before the gutters take up the rest.
 */
@Composable
internal fun tabContentPadding(top: Dp = 8.dp, maxWidth: Dp = READABLE_CONTENT_WIDTH): PaddingValues {
    val gutter = contentGutter(maxWidth)
    return PaddingValues(
        start = gutter,
        end = gutter,
        top = shellTopBarClearance() + top,
        bottom = bottomNavClearance(),
    )
}
