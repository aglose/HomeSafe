package com.meticulouscreations.homesafe.ui.components

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
        }
    }
}
