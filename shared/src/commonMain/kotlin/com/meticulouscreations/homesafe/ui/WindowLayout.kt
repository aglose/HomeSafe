package com.meticulouscreations.homesafe.ui

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Stands in for the window's own shape in [isCompactLandscape]: null, the default, asks the
 * window. A test or a preview provides true or false to lay a screen out for a phone on its side
 * (or upright) whatever size it is actually rendered at.
 *
 * A local because the window's shape is ambient in exactly the way [LocalWindowInfo] is: every
 * screen that asks reads the same answer, and nothing in the app itself ever provides this.
 */
internal val LocalCompactLandscape = compositionLocalOf<Boolean?> { null }

/** Material's "compact" window height: shorter than this, stacked chrome leaves no room for content. */
private val COMPACT_HEIGHT = 480.dp

/**
 * Material's "medium" window width, which every phone on its side reaches (the smallest are 640dp
 * long). Narrower than this there is no room to stand two panes side by side, however short the
 * window is.
 */
private val SIDE_BY_SIDE_WIDTH = 600.dp

/**
 * Whether the window is a phone on its side: wider than tall, too short to stack a top bar, a
 * page and a bottom nav, and wide enough to put things side by side instead. The layouts that
 * change with rotation key off this one answer — the shell moves its nav to the side, the camera
 * screen gives the whole window to the video, the editors stand their picture beside their tools
 * — so they change together. A tablet or a desktop window in landscape is tall enough to keep
 * the upright arrangement and is not "compact"; half a phone's screen in split-screen is short
 * but not wide, and keeps it too.
 *
 * Read from the window rather than a device orientation: a resized desktop window that is short
 * and wide wants the same layout.
 */
@Composable
@ReadOnlyComposable
internal fun isCompactLandscape(): Boolean {
    LocalCompactLandscape.current?.let { return it }
    val size = LocalWindowInfo.current.containerSize
    if (size.width <= size.height) return false
    return with(LocalDensity.current) { size.height.toDp() < COMPACT_HEIGHT && size.width.toDp() >= SIDE_BY_SIDE_WIDTH }
}

/** The window's width, system bars and cutouts included. */
@Composable
@ReadOnlyComposable
internal fun windowWidth(): Dp = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }

/** The window's height, system bars and cutouts included. */
@Composable
@ReadOnlyComposable
internal fun windowHeight(): Dp = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }

/**
 * The largest 16:9 box that fits the space on offer, whichever way round the space is: full
 * width under a tall space (a phone upright), full height in a wide one (a phone on its side,
 * whose screen is wider than 16:9). The parent must not force a size on it — a [androidx.compose.foundation.layout.Box]
 * child, not a `fillMaxWidth()` one.
 */
internal fun Modifier.fitVideo(): Modifier = aspectRatio(VIDEO_ASPECT)

/** Every camera's picture is shown 16:9 (see CameraStreamPlayer: the players stretch to fill). */
internal const val VIDEO_ASPECT = 16f / 9f
