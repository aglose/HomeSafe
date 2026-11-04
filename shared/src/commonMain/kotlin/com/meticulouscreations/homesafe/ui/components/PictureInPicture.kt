package com.meticulouscreations.homesafe.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier

/**
 * Whether the window is the floating mini player (Android's picture-in-picture): the app shrunk to
 * the video a camera's screen handed it, over whatever else the person has gone on to use. The
 * platform's host provides it; nothing else in the app does.
 *
 * In it the window is the video and nothing more: [com.meticulouscreations.homesafe.ui.isCompactLandscape]
 * answers true, so the camera screen lays itself out full screen (and the shell takes its nav away),
 * and the camera screens leave their controls off the picture, which the system's own window
 * controls stand in for. Nothing in the window takes a touch there.
 */
val LocalPictureInPicture = compositionLocalOf { false }

/**
 * Whether a camera screen showing now may hand its video to the mini player: the shell says no
 * while the drawer, or an app from it, is over the page, so leaving the app from there doesn't
 * float a video the person had put away under it.
 */
val LocalPictureInPictureAllowed = compositionLocalOf { true }

/**
 * A screen's way into the mini player, from [rememberPictureInPicture].
 *
 * While the screen that asked is started and allowed, leaving the app (Home, the recents, a
 * notification's link elsewhere) floats its video rather than stopping it. [enter] does it there
 * and then, for a button.
 */
@Stable
interface PictureInPicture {
    /** Whether this device floats a video at all: Android, where the system offers it. */
    val available: Boolean

    /**
     * For the video's own box: where the mini player grows from as it leaves, and lands back on as
     * it returns. Unchanged where there is no mini player.
     */
    val sourceModifier: Modifier

    /** Floats the video now. Nothing happens where there is no mini player. */
    fun enter()
}

/**
 * The mini player for the video on this screen, at [aspectRatio] (width over height; the system
 * keeps it between 1:2.39 and 2.39:1). [enabled] while there is a video to float. The screen
 * claims it while it is started, so the screen on top is the one that floats; one under it (a
 * camera under the clip editor) has stopped, and given it up.
 */
@Composable
expect fun rememberPictureInPicture(enabled: Boolean, aspectRatio: Float): PictureInPicture

/** Where there is no mini player: nothing to show for it, and nothing it does. */
internal object NoPictureInPicture : PictureInPicture {
    override val available: Boolean = false
    override val sourceModifier: Modifier = Modifier

    override fun enter() = Unit
}
