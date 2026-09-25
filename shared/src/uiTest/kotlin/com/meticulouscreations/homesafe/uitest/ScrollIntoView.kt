package com.meticulouscreations.homesafe.uitest

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.unit.toSize
import kotlin.math.abs
import kotlin.math.sign

/**
 * Brings the node [node] finds inside the viewport of its nearest scrolling ancestor, a step at a
 * time, and returns [node].
 *
 * Not `performScrollTo()`: that keeps scrolling until the node is in view, but a semantic scroll
 * is an animation, and under a frozen clock (`autoAdvance = false`) the animation never plays, so
 * it never returns. On a phone-sized emulator it hung the device suite for good. This asks the
 * scroller to move by the distance still to go, lets [settle] play the animation out, and looks
 * again.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.scrollIntoView(
    node: SemanticsNodeInteraction,
    settle: () -> Unit = { mainClock.advanceTimeBy(SCROLL_SETTLE_MILLIS) },
): SemanticsNodeInteraction {
    repeat(MAX_SCROLL_STEPS) {
        val target = node.fetchSemanticsNode()
        val scroller = generateSequence(target.parent) { it.parent }
            .firstOrNull { SemanticsActions.ScrollBy in it.config } ?: return node
        val (dx, dy) = distanceIntoView(target, scroller)
        if (abs(dx) < 1f && abs(dy) < 1f) return node
        runOnUiThread { scroller.config[SemanticsActions.ScrollBy].action?.invoke(dx, dy) }
        settle()
    }
    throw AssertionError("Still not in view after $MAX_SCROLL_STEPS scrolls: ${node.fetchSemanticsNode()}")
}

/** How far [scroller] has to move, along the axes it scrolls, for [target] to sit in its viewport. */
private fun distanceIntoView(target: SemanticsNode, scroller: SemanticsNode): Pair<Float, Float> {
    val viewport = scroller.boundsInRoot
    val bounds = Rect(target.positionInRoot, target.size.toSize())

    // Past one edge: move by the smaller overshoot, so a node taller than the viewport lines up
    // with the near edge rather than jumping over it. Straddling both edges: leave it.
    fun along(start: Float, end: Float): Float = when {
        sign(start) != sign(end) -> 0f
        abs(start) < abs(end) -> start
        else -> end
    }

    val dx = if (scroller.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null) {
        along(bounds.left - viewport.left, bounds.right - viewport.right)
    } else {
        0f
    }
    val dy = if (scroller.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null) {
        along(bounds.top - viewport.top, bounds.bottom - viewport.bottom)
    } else {
        0f
    }
    return dx to dy
}

private const val MAX_SCROLL_STEPS = 12
private const val SCROLL_SETTLE_MILLIS = 600L
