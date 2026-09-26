package com.meticulouscreations.homesafe.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
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
    // The animated value is kept as a State and read only inside graphicsLayer's draw-phase
    // lambda. Reading it here (`by animateFloat(...)`) would recompose this composable — and
    // re-run its whole modifier chain — on every animation frame, for every dot on screen
    // (one per camera card on Home). Deferring the read costs one draw invalidation per frame instead.
    val alpha: State<Float> = if (pulsing) {
        val transition = rememberInfiniteTransition(label = "pulsing-dot")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.4f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1000),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulsing-dot-alpha",
        )
    } else {
        OPAQUE
    }
    Box(
        modifier
            .size(size)
            .graphicsLayer { this.alpha = alpha.value }
            .background(color, CircleShape),
    )
}

private val OPAQUE: State<Float> = mutableStateOf(1f)

/**
 * Three dots with a bright spot travelling along them, in place of a [PulsingDot] while there is
 * no moving video to stand for: connecting, or connected and starved. A pulse says "this is live";
 * three dots taking their turn say "still working on it", which is the difference the camera
 * cards' status pill needs to draw.
 *
 * Like [PulsingDot], the animated value is read only inside `graphicsLayer`'s draw lambda, so a
 * card full of these costs one draw invalidation a frame rather than a recomposition.
 */
@Composable
fun BufferingDots(
    color: Color,
    modifier: Modifier = Modifier,
    dotSize: Dp = 5.dp,
) {
    val transition = rememberInfiniteTransition(label = "buffering-dots")
    // Runs 0..DOT_COUNT so the phase is directly the index the bright spot is over.
    val phase = transition.animateFloat(
        initialValue = 0f,
        targetValue = DOT_COUNT.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = BUFFERING_CYCLE_MS, easing = LinearEasing),
        ),
        label = "buffering-dots-phase",
    )
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(dotSize / 2),
    ) {
        repeat(DOT_COUNT) { index ->
            Box(
                Modifier
                    .size(dotSize)
                    .graphicsLayer { alpha = bufferingDotAlpha(phase.value, index) }
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
