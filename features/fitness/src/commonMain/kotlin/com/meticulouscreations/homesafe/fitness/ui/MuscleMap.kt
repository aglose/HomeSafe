package com.meticulouscreations.homesafe.fitness.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.meticulouscreations.homesafe.fitness.domain.Muscle
import com.meticulouscreations.homesafe.fitness.domain.MuscleVolume
import com.meticulouscreations.homesafe.fitness.domain.VolumeStatus

/** A muscle's place on the figure, in the figure's own units (100 wide, 220 tall): an oval, or a rounded slab when [slab]. */
private class Region(val muscle: Muscle, val cx: Float, val cy: Float, val rx: Float, val ry: Float, val slab: Boolean = false)

private const val FIGURE_WIDTH = 100f
private const val FIGURE_HEIGHT = 220f

private fun pair(muscle: Muscle, cx: Float, cy: Float, rx: Float, ry: Float, slab: Boolean = false) =
    listOf(Region(muscle, cx, cy, rx, ry, slab), Region(muscle, FIGURE_WIDTH - cx, cy, rx, ry, slab))

private val FRONT: List<Region> =
    pair(Muscle.SIDE_DELTS, 19.5f, 45f, 4.5f, 8.5f) +
        pair(Muscle.FRONT_DELTS, 27.5f, 42.5f, 7.5f, 7f) +
        pair(Muscle.CHEST, 41f, 50f, 8f, 9.5f, slab = true) +
        pair(Muscle.BICEPS, 19f, 65f, 6.2f, 12.5f) +
        pair(Muscle.FOREARMS, 14f, 97f, 5.2f, 15f) +
        Region(Muscle.ABS, 50f, 82f, 9f, 19f, slab = true) +
        pair(Muscle.QUADS, 38.5f, 150f, 10f, 27f) +
        pair(Muscle.ADDUCTORS, 47f, 135f, 2.8f, 13f)

private val BACK: List<Region> =
    pair(Muscle.REAR_DELTS, 26.5f, 43.5f, 8f, 7f) +
        pair(Muscle.BACK, 40.5f, 74f, 8.6f, 21f, slab = true) +
        pair(Muscle.TRICEPS, 19f, 65f, 6.2f, 12.5f) +
        pair(Muscle.FOREARMS, 14f, 97f, 5.2f, 15f) +
        pair(Muscle.GLUTES, 40f, 116f, 9.6f, 10f) +
        pair(Muscle.HAMSTRINGS, 38.5f, 153f, 9.6f, 23f) +
        pair(Muscle.CALVES, 38f, 195f, 7.2f, 17f)

/**
 * The body, front and back, with each muscle lit by the week it has had: dark when it hasn't
 * been trained, through violet and amber as the sets build, green in its target band and ember
 * past it. The muscles light up one after another when the map appears, and the ones on target
 * breathe. A drawing, not anatomy: the figure is a mannequin of ovals, there to show at a glance
 * what the week has covered and what it has missed.
 */
@Composable
internal fun MuscleMap(volumes: List<MuscleVolume>, modifier: Modifier = Modifier, contentDescription: String = "") {
    val colors = FitnessTheme.colors
    val byMuscle = remember(volumes) { volumes.associateBy { it.muscle } }
    val still = LocalInspectionMode.current
    val reveal = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(Unit) { if (!still) reveal.animateTo(1f, tween(1100, easing = FastOutSlowInEasing)) }
    val breath by rememberInfiniteTransition(label = "muscles").animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "muscleBreath")

    Canvas(modifier.semantics { if (contentDescription.isNotEmpty()) this.contentDescription = contentDescription }) {
        // Two figures side by side with a gap, as large as the space allows.
        val gap = size.width * 0.06f
        val unit = minOf((size.width - gap) / 2 / FIGURE_WIDTH, size.height / FIGURE_HEIGHT)
        val figureWidth = FIGURE_WIDTH * unit
        val left = (size.width - figureWidth * 2 - gap) / 2
        val top = (size.height - FIGURE_HEIGHT * unit) / 2
        listOf(FRONT to Offset(left, top), BACK to Offset(left + figureWidth + gap, top)).forEachIndexed { figure, (regions, origin) ->
            silhouette(origin, unit, colors.surfaceRaised)
            if (figure == 1) traps(origin, unit, tone(byMuscle[Muscle.TRAPS], colors, reveal.value, breath, order = 0f))
            regions.forEachIndexed { i, region ->
                val color = tone(byMuscle[region.muscle], colors, reveal.value, breath, order = i / regions.size.toFloat())
                val center = origin + Offset(region.cx * unit, region.cy * unit)
                val half = Size(region.rx * unit, region.ry * unit)
                if (region.slab) {
                    drawRoundRect(color, center - Offset(half.width, half.height), Size(half.width * 2, half.height * 2), CornerRadius(half.width * 0.55f))
                } else {
                    drawOval(color, center - Offset(half.width, half.height), Size(half.width * 2, half.height * 2))
                }
            }
            if (figure == 0) {
                // The abs' own lines, cut back out of the slab.
                val x = origin.x + 50f * unit
                drawLine(colors.surface, Offset(x, origin.y + 65f * unit), Offset(x, origin.y + 99f * unit), unit * 1.1f)
                listOf(72f, 81f, 90f).forEach { y ->
                    drawLine(colors.surface, Offset(origin.x + 42f * unit, origin.y + y * unit), Offset(origin.x + 58f * unit, origin.y + y * unit), unit * 1.1f)
                }
            }
        }
    }
}

/** A muscle's colour for its week, faded in by its turn in the reveal and breathing a little once it is on target. */
private fun tone(volume: MuscleVolume?, colors: FitnessPalette, reveal: Float, breath: Float, order: Float): Color {
    val status = volume?.status ?: VolumeStatus.NONE
    val base = colors.volume(status)
    if (status == VolumeStatus.NONE) return base
    val shown = ((reveal * 1.6f - order * 0.6f)).coerceIn(0f, 1f)
    val strength = 0.55f + 0.45f * (volume?.fill ?: 0f).coerceIn(0f, 1f)
    val pulse = if (status == VolumeStatus.ON_TARGET || status == VolumeStatus.HIGH) 0.82f + 0.18f * breath else 1f
    return lerp(colors.volume(VolumeStatus.NONE), base, shown * strength * pulse)
}

private fun DrawScope.traps(origin: Offset, unit: Float, color: Color) {
    val path = Path().apply {
        moveTo(origin.x + 50f * unit, origin.y + 27f * unit)
        lineTo(origin.x + 66f * unit, origin.y + 38f * unit)
        lineTo(origin.x + 50f * unit, origin.y + 64f * unit)
        lineTo(origin.x + 34f * unit, origin.y + 38f * unit)
        close()
    }
    drawPath(path, color)
}

/** The mannequin the muscles sit on: head, trunk, arms and legs, in one flat tone. */
private fun DrawScope.silhouette(origin: Offset, unit: Float, color: Color) {
    fun oval(cx: Float, cy: Float, rx: Float, ry: Float) =
        drawOval(color, origin + Offset((cx - rx) * unit, (cy - ry) * unit), Size(rx * 2 * unit, ry * 2 * unit))

    fun both(cx: Float, cy: Float, rx: Float, ry: Float) {
        oval(cx, cy, rx, ry)
        oval(FIGURE_WIDTH - cx, cy, rx, ry)
    }
    oval(50f, 15f, 10.5f, 12f)
    drawRoundRect(color, origin + Offset(44f * unit, 24f * unit), Size(12f * unit, 12f * unit), CornerRadius(3f * unit))
    drawRoundRect(color, origin + Offset(27f * unit, 33f * unit), Size(46f * unit, 78f * unit), CornerRadius(15f * unit))
    drawRoundRect(color, origin + Offset(29f * unit, 96f * unit), Size(42f * unit, 34f * unit), CornerRadius(13f * unit))
    both(22f, 43f, 10f, 10f)
    both(19f, 64f, 8f, 18f)
    both(14f, 97f, 6.8f, 19f)
    both(11.5f, 120f, 5f, 6f)
    both(38.5f, 150f, 12.5f, 33f)
    both(38f, 194f, 8.6f, 24f)
    both(38f, 216f, 7f, 4f)
}
