package com.meticulouscreations.homesafe.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.ui.preview.FrigatePreview
import kotlin.math.abs

@Composable
fun PulsingDot(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 8.dp,
    pulsing: Boolean = true,
) {
    // The frame clock is kept as a State and read only inside graphicsLayer's draw-phase lambda.
    // Reading it here would recompose this composable — and re-run its whole modifier chain — on
    // every step, for every dot on screen (one per camera card on Home). Deferring the read costs
    // one draw a step instead, at thirty a second rather than the display's 60 or 120: a slow fade
    // looks the same, and Home with a dozen live cards draws a quarter as often on a 120 Hz phone.
    // Every dot takes its phase from the same clock, so a screenful pulse together.
    val clock = rememberSteppedFrameNanos(running = pulsing)
    Box(
        modifier
            .size(size)
            .graphicsLayer { this.alpha = if (pulsing) pulseAlpha(clock.longValue) else 1f }
            .background(color, CircleShape),
    )
}

/** The dot's opacity at [frameNanos]: full, down to [PULSE_DIM_ALPHA] and back over [PULSE_PERIOD_MS]. */
internal fun pulseAlpha(frameNanos: Long): Float = 1f - (1f - PULSE_DIM_ALPHA) * breathAt(frameNanos, PULSE_PERIOD_MS)

/** One fade out and back in. */
private const val PULSE_PERIOD_MS = 2_000

/** How far the dot fades at the bottom of its pulse. */
private const val PULSE_DIM_ALPHA = 0.4f

/**
 * Three dots with a bright spot travelling along them, in place of a [PulsingDot] while there is
 * no moving video to stand for: connecting, or connected and starved. A pulse says "this is live";
 * three dots taking their turn say "still working on it", which is the difference the camera
 * cards' status pill needs to draw.
 *
 * Like [PulsingDot], the clock is read only inside `graphicsLayer`'s draw lambda, so a card full of
 * these costs one draw a step rather than a recomposition.
 */
@Composable
fun BufferingDots(
    color: Color,
    modifier: Modifier = Modifier,
    dotSize: Dp = 5.dp,
) {
    // Stepped thirty times a second, as PulsingDot is. The phase runs 0..DOT_COUNT so it is directly the index the bright spot is over.
    val clock = rememberSteppedFrameNanos()
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(dotSize / 2),
    ) {
        repeat(DOT_COUNT) { index ->
            Box(
                Modifier
                    .size(dotSize)
                    .graphicsLayer { alpha = bufferingDotAlpha(cycleAt(clock.longValue, BUFFERING_CYCLE_MS) * DOT_COUNT, index) }
                    .background(color, CircleShape),
            )
        }
    }
}

/**
 * The opacity of dot [index] when the bright spot is at [phase] (0..[DOT_COUNT], wrapping): full
 * where the spot sits, falling off linearly to [BUFFERING_DIM_ALPHA] at the far side of the cycle.
 * The wrap is on the shorter way round, so the spot leaves the last dot and arrives at the first
 * without a jump.
 */
internal fun bufferingDotAlpha(phase: Float, index: Int): Float {
    val half = DOT_COUNT / 2f
    val distance = abs((phase - index + half).mod(DOT_COUNT.toFloat()) - half)
    val brightness = 1f - (distance / half).coerceIn(0f, 1f)
    return BUFFERING_DIM_ALPHA + (1f - BUFFERING_DIM_ALPHA) * brightness
}

/** Three, as the name says — the shape of the animation, and the divisor [bufferingDotAlpha] wraps on. */
internal const val DOT_COUNT = 3

/** One trip of the bright spot across all three dots. */
private const val BUFFERING_CYCLE_MS = 1200

/** What a dot fades to when the bright spot is furthest from it; never off, so the group holds its shape. */
private const val BUFFERING_DIM_ALPHA = 0.25f

@Preview
@Composable
private fun PulsingDotPreview() {
    FrigatePreview {
        Row(
            modifier = Modifier.padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PulsingDot(color = MaterialTheme.colorScheme.secondary)
            PulsingDot(color = MaterialTheme.colorScheme.error)
            PulsingDot(color = MaterialTheme.colorScheme.secondary, pulsing = false)
            PulsingDot(color = MaterialTheme.colorScheme.primary, size = 16.dp)
            BufferingDots(color = MaterialTheme.colorScheme.secondary)
        }
    }
}
