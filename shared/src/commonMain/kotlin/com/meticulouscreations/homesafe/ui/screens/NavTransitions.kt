package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.unit.IntOffset
import androidx.navigation3.scene.Scene

/**
 * The app's screen-change vocabulary. Navigation 3 ships a 700 ms cross-fade for every push
 * and pop and a shrink-the-whole-screen predictive back; both read as sluggish and, on the
 * camera detail screen, the shrink fights the shared-element video flying back to its card.
 * Everything here is one beat long, so a tap feels answered rather than acknowledged later.
 */

/** How long a screen change takes, start to finish. */
internal const val NAV_TRANSITION_MS = 320

/** Material 3 "emphasized decelerate": quick off the mark, soft landing. */
internal val NavEnterEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/** Screens travel a tenth of the container while they hand over; any more looks like a carousel. */
private const val SLIDE_DIVISOR = 10

/**
 * Material's shared-axis (X) hand-over: the outgoing screen fades in the first third of the beat
 * while both slide together, and the incoming one fades up over the rest. [forward] slides
 * towards the start edge (drilling in, next tab); `false` reverses it (back, previous tab).
 */
internal fun <T : Any> AnimatedContentTransitionScope<Scene<T>>.sharedAxis(forward: Boolean): ContentTransform {
    val towards = if (forward) SlideDirection.Start else SlideDirection.End
    val slide = tween<IntOffset>(NAV_TRANSITION_MS, easing = NavEnterEasing)
    return ContentTransform(
        targetContentEnter = fadeIn(tween(NAV_TRANSITION_MS * 2 / 3, delayMillis = NAV_TRANSITION_MS / 3, easing = LinearEasing)) +
            slideIntoContainer(towards = towards, animationSpec = slide) { it / SLIDE_DIVISOR },
        initialContentExit = fadeOut(tween(NAV_TRANSITION_MS / 3, easing = LinearEasing)) +
            slideOutOfContainer(towards = towards, animationSpec = slide) { it / SLIDE_DIVISOR },
    )
}

/**
 * For a screen that opens with a shared element (the camera detail's video): a fade-through.
 * The parent drops away in the first third of the beat and the new screen fades up over the
 * rest, so the shared element crosses a calm background rather than two half-visible pages.
 * Nothing slides or scales: the video is the one thing that moves.
 */
internal val SharedElementPush: ContentTransform = ContentTransform(
    targetContentEnter = fadeIn(tween(NAV_TRANSITION_MS * 2 / 3, delayMillis = NAV_TRANSITION_MS / 3, easing = LinearEasing)),
    initialContentExit = fadeOut(tween(NAV_TRANSITION_MS / 3, easing = LinearEasing)),
)

/**
 * The reverse. The parent comes up quickly so the card is on screen while the video flies home
 * to it, and the popped screen dissolves over the beat — no scaling of the background. With
 * predictive back the same spec is scrubbed by the finger.
 */
internal val SharedElementPop: ContentTransform = ContentTransform(
    targetContentEnter = fadeIn(tween(NAV_TRANSITION_MS / 2, easing = LinearEasing)),
    initialContentExit = fadeOut(tween(NAV_TRANSITION_MS, easing = LinearEasing)),
)

/** A plain, symmetric cross-fade for the root switch between sign-in and the app shell. */
internal val RootCrossfade: ContentTransform = ContentTransform(
    targetContentEnter = fadeIn(tween(NAV_TRANSITION_MS, easing = LinearEasing)),
    initialContentExit = fadeOut(tween(NAV_TRANSITION_MS, easing = LinearEasing)),
)
