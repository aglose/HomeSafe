package com.meticulouscreations.homesafe.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow

/**
 * Reveals the content this modifies as a splash of liquid landing at [originFraction] (0..1 of
 * the content's width and height — where the finger was) and spreading until it covers
 * everything. [progress] runs 0 → 1 to splash in and 1 → 0 to drain back to the origin; it is
 * read at draw time only, so driving it every frame never recomposes the content.
 *
 * At 1 the modifier steps aside completely (no offscreen layer, no effect), so a finished reveal
 * costs nothing and the content is pixel-for-pixel what it would be without it.
 *
 * Android renders a real water surface with an AGSL runtime shader over the content: a ridge of
 * refracting water on the wavefront with chromatic fringing, capillary ripples trailing behind it,
 * a crown whose lobes settle as it spreads, specular glints, a contact shadow on the screen being
 * covered, and droplets thrown ahead of the front. Platforms without Android's RenderEffect get
 * [circularSplashFallback].
 */
expect fun Modifier.splashReveal(progress: () -> Float, originFraction: Offset): Modifier

/**
 * The splash's shape without its water: a circle of the content opening from the origin on the
 * same curve, with a fading rim. What the non-Android platforms show.
 */
internal fun Modifier.circularSplashFallback(progress: () -> Float, originFraction: Offset): Modifier = drawWithContent {
    val p = progress()
    if (p >= 1f) {
        drawContent()
        return@drawWithContent
    }
    if (p <= 0f) return@drawWithContent
    val origin = Offset(originFraction.x * size.width, originFraction.y * size.height)
    val radius = splashFrontRadius(p, splashMaxRadius(origin, size.width, size.height))
    val circle = Path().apply { addOval(Rect(center = origin, radius = radius)) }
    clipPath(circle) { this@drawWithContent.drawContent() }
    drawCircle(
        color = Color.White.copy(alpha = 0.45f * (1f - p)),
        radius = radius,
        center = origin,
        style = Stroke(width = 3.dp.toPx()),
    )
}

/** The distance from [origin] to the farthest corner: how far the front must travel to cover a [width]×[height] surface. */
internal fun splashMaxRadius(origin: Offset, width: Float, height: Float): Float {
    val dx = max(origin.x, width - origin.x)
    val dy = max(origin.y, height - origin.y)
    return hypot(dx, dy)
}

/**
 * Where the splash front is at [progress]: a cubic ease-out, like liquid thrown onto glass
 * (fast off the impact, slowing as it thins), overshooting [maxRadius] a little so the crown's
 * lobes are off screen by the end. The shader follows the same curve.
 */
internal fun splashFrontRadius(progress: Float, maxRadius: Float): Float {
    val eased = 1f - (1f - progress.coerceIn(0f, 1f)).pow(3)
    return eased * maxRadius * SPLASH_OVERSHOOT
}

/** How far past the farthest corner the front travels, as a multiple of that distance. */
internal const val SPLASH_OVERSHOOT = 1.12f
