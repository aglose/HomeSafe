package com.meticulouscreations.homesafe.fitness.ui.movement

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.meticulouscreations.homesafe.fitness.domain.FigureStyle
import com.meticulouscreations.homesafe.fitness.domain.MovementKind
import com.meticulouscreations.homesafe.fitness.domain.MovementMatch
import com.meticulouscreations.homesafe.fitness.ui.FitnessPalette
import com.meticulouscreations.homesafe.fitness.ui.FitnessTheme
import com.meticulouscreations.homesafe.fitness.ui.shader.FitnessShader
import com.meticulouscreations.homesafe.fitness.ui.shader.fitnessShaderOrNull
import com.meticulouscreations.homesafe.fitness.ui.shader.rememberFitnessClock
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.fitness_figure_nearest
import homesafe.shared.generated.resources.fitness_figure_working_muscles
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** How big a figure is drawn: a still in a list's row, or the movement itself, moving. */
internal enum class FigureSize { BADGE, LARGE }

/**
 * The figure that performs an exercise, [kind] being the way the body moves in it, drawn the way
 * [style] says: the rendered lifter played back frame by frame, or the jointed rig as stone or
 * as a forged study, in the workout's [tints].
 *
 * As a [FigureSize.BADGE] it is a small still of the hard end of the rep, for the start of a
 * row. [FigureSize.LARGE] it is the rep itself, over and over while [running], with the muscles
 * that do the lift and the ones that help it called out by name; it fills the width it is given.
 *
 * Every kind has a figure. Where the rendered frames of one aren't to be had (not packed with
 * this build, or unreadable), the forged study is drawn instead; where the rig can't perform a
 * kind yet, its nearest movement stands in ([MovementCatalog.standIn]).
 */
@Composable
internal fun ExerciseFigure(
    kind: MovementKind,
    style: FigureStyle,
    tints: Pair<Color, Color>,
    modifier: Modifier = Modifier,
    size: FigureSize = FigureSize.LARGE,
    running: Boolean = true,
) {
    val colors = FitnessTheme.colors
    when (size) {
        FigureSize.BADGE -> Box(modifier.size(BADGE_SIZE).clip(BADGE_SHAPE).background(STAGE).border(1.dp, colors.hairline, BADGE_SHAPE)) {
            val rendered = if (style == FigureStyle.TRUE_3D) rememberMovementBadge(kind) else Loaded.Missing
            when (rendered) {
                is Loaded.Ready -> Image(rendered.value, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.Medium)

                // Its own layer, kept as a picture: the rig is a lot of drawing to do again for every frame a list scrolls.
                Loaded.Missing -> MovementFigure(
                    MovementCatalog.rig(kind),
                    MovementCatalog.rigStyle(style),
                    tints,
                    { HARD_END_SECONDS },
                    Modifier.fillMaxSize().padding(3.dp).graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
                )

                Loaded.Loading -> Unit
            }
        }

        FigureSize.LARGE -> Box(modifier.fillMaxWidth().height(LARGE_HEIGHT).clip(LARGE_SHAPE).background(STAGE).testTag("fitness_figure")) {
            val rendered = if (style == FigureStyle.TRUE_3D) rememberMovementSheets(kind) else Loaded.Missing
            when (rendered) {
                is Loaded.Ready -> RenderedFigure(rendered.value, tints, running, Modifier.fillMaxSize())
                Loaded.Missing -> RigFigure(MovementCatalog.rig(kind), MovementCatalog.rigStyle(style), tints, running, Modifier.fillMaxSize().padding(6.dp))
                Loaded.Loading -> Unit
            }
        }
    }
}

/**
 * The line under a figure that may not be doing the exercise it is shown for: nothing in the
 * exercise's name said which movement it is ([MovementMatch.named] false), so the figure is the
 * likeliest for its shelf, and this names it. Draws nothing for a movement the name did give.
 */
@Composable
internal fun NearestFigureNote(match: MovementMatch, modifier: Modifier = Modifier) {
    if (match.named) return
    Text(
        stringResource(Res.string.fitness_figure_nearest, stringResource(match.kind.label)),
        style = FitnessTheme.type.label,
        color = FitnessTheme.colors.textFaint,
        modifier = modifier.testTag("fitness_figure_nearest"),
    )
}

/** The jointed rig performing [movement], rep after rep, its working heads named. */
@Composable
private fun RigFigure(movement: Movement, style: MovementStyle, tints: Pair<Color, Color>, running: Boolean, modifier: Modifier = Modifier) {
    val clock = rememberFitnessClock(running)
    val heads = remember(movement) { MovementCatalog.named(movement) }
    val names = rememberHeadNames(heads)
    val spoken = workingMuscles(heads, names)
    // The rig keeps the names as it first measured them: other names are another figure.
    key(names) { MovementFigure(movement, style, tints, { clock.floatValue }, modifier.semantics { contentDescription = spoken }, names) }
}

/**
 * The names drawn on a figure, as a sentence: they are painted, not text on the screen, so a
 * screen reader has only this to read them from.
 */
@Composable
private fun workingMuscles(heads: Set<HeadId>, names: HeadNames): String =
    stringResource(Res.string.fitness_figure_working_muscles, heads.sortedBy { it.ordinal }.joinToString { names.of(it).replace('\n', ' ') })

/**
 * The rendered lifter, moving as the rep does: a rep is [SHEET_FRAMES] pictures, too few to
 * play back one after another without it showing, so between two of them the one is faded into
 * the other by as much of the way from one to the next as the rep is. Over it the working
 * muscles are laid as light in their roles' colours, brighter as the rep nears its hard end,
 * with their names set out down either side. At either end of a rep the frame is the end's own,
 * held, and nothing is drawn again for a beat.
 */
@Composable
private fun RenderedFigure(sheets: MovementSheets, tints: Pair<Color, Color>, running: Boolean, modifier: Modifier = Modifier) {
    val colors = FitnessTheme.colors
    val clock = rememberFitnessClock(running)
    // How far through the frames the rep is: a whole number is that frame, anything between is a blend of the two either side.
    val place = remember(clock) { derivedStateOf { repProgress(clock.floatValue).coerceIn(0f, 1f) * (SHEET_FRAMES - 1) } }
    val names = rememberHeadNames(sheets.named)
    val spoken = workingMuscles(sheets.named, names)
    val measurer = rememberTextMeasurer()
    val lights = remember { MuscleLights() }
    val heat = remember { fitnessShaderOrNull(HEAT_SHADER) }
    Spacer(
        // A layer of its own to blend in: one frame is added to another, and a muscle's colour is stained into the lifter alone.
        modifier.semantics { contentDescription = spoken }.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithCache {
            val texts = sheets.named.associateWith { measurer.measure(names.of(it), names.style) }
            // The frame is square, and is given all the height: the names go over its edges, where a lifter leaves room.
            val side = min(size.width, size.height)
            val at = Rect(Offset((size.width - side) / 2f, (size.height - side) / 2f), Size(side, side))
            val callouts = setOut(sheets.anchors[SHEET_FRAMES / 2], sheets.named, texts, at, size.height, 5.dp.toPx())
            val floor = Brush.radialGradient(listOf(tints.first.copy(alpha = 0.2f), Color.Transparent), Offset(size.width / 2f, at.top + side * FLOOR_AT), side * 0.46f)
            onDrawBehind {
                val along = place.value
                val frame = along.toInt().coerceIn(0, SHEET_FRAMES - 1)
                val next = min(frame + 1, SHEET_FRAMES - 1)
                val blend = if (next == frame) 0f else along - frame
                rendered(sheets, frame, next, blend, along / (SHEET_FRAMES - 1f), at, lights, heat)
                // The workout's own light, pooled on the floor under the lifter: behind what has been drawn.
                scale(1f, 0.16f, Offset(size.width / 2f, at.top + side * FLOOR_AT)) { drawRect(floor, blendMode = BlendMode.DstOver) }
                named(sheets.anchors[frame], sheets.anchors[next], blend, callouts, texts, at, colors)
            }
        },
    )
}

/**
 * The lifter [blend] of the way from [frame] to [next], and over them those frames' muscles in
 * the same shares. Drawn into a clear layer: the two frames are added together, each by its
 * share, which is a true cross-fade (laying one over the other would leave the first showing
 * whole wherever the lifter has just moved away from).
 */
private fun DrawScope.rendered(sheets: MovementSheets, frame: Int, next: Int, blend: Float, effort: Float, at: Rect, lights: MuscleLights, heat: FitnessShader?) {
    if (heat != null) {
        // One shader does the whole of it, the way the renderer's own previews are made (HEAT_SHADER).
        val first = sheets.body.cell(frame, sheets.bodyFrame)
        val second = sheets.body.cell(next, sheets.bodyFrame)
        val light = sheets.muscle.cell(frame, sheets.muscleFrame)
        val other = sheets.muscle.cell(next, sheets.muscleFrame)
        heat.setImage("body", sheets.body)
        heat.setImage("muscle", sheets.muscle)
        heat.setUniform("origin", at.left, at.top)
        heat.setUniform("extent", at.width, at.height)
        heat.setUniform("cells", floatArrayOf(first.x.toFloat(), first.y.toFloat(), second.x.toFloat(), second.y.toFloat()))
        heat.setUniform("lights", floatArrayOf(light.x.toFloat(), light.y.toFloat(), other.x.toFloat(), other.y.toFloat()))
        heat.setUniform("frames", sheets.bodyFrame.toFloat(), sheets.muscleFrame.toFloat())
        heat.setUniform("blend", blend)
        heat.setUniform("effort", effort)
        drawRect(heat.brush(), at.topLeft, at.size)
        return
    }
    // Where a shader can't be had, much the same by blending: paler, and with no light thrown.
    val to = IntOffset(at.left.roundToInt(), at.top.roundToInt())
    val size = IntSize(at.width.roundToInt(), at.height.roundToInt())
    val body = IntSize(sheets.bodyFrame, sheets.bodyFrame)
    drawImage(sheets.body, sheets.body.cell(frame, sheets.bodyFrame), body, to, size, alpha = 1f - blend, filterQuality = FilterQuality.Medium)
    if (blend > 0f) drawImage(sheets.body, sheets.body.cell(next, sheets.bodyFrame), body, to, size, alpha = blend, blendMode = BlendMode.Plus, filterQuality = FilterQuality.Medium)

    val cell = IntSize(sheets.muscleFrame, sheets.muscleFrame)
    // What steadies first, what does the lift last and over the rest. Each is laid on twice: once as a stain, which keeps
    // the body's light and shade under it (and on pale stone doesn't wash out to white, as light alone would) and touches
    // nothing but the lifter; and once, fainter, as light of its own, which is also what it throws on the ground around them.
    for (role in MuscleLights.ROLES.indices.reversed()) {
        // What only steadies the lift is left as stone: lit too, it puts a stain over half the body and the muscles the
        // lift is for are lost in it.
        if (MuscleLights.ROLES[role] < SYNERGIST) continue
        val strength = (0.4f + 0.6f * MuscleLights.ROLES[role]) * (0.45f + 0.55f * effort)
        for (second in listOf(false, true)) {
            val share = strength * if (second) blend else 1f - blend
            val from = sheets.muscle.cell(if (second) next else frame, sheets.muscleFrame)
            lights.stain(role, share)?.let { drawImage(sheets.muscle, from, cell, to, size, colorFilter = it, blendMode = BlendMode.Modulate, filterQuality = FilterQuality.Medium) }
            lights.glow(role, share)?.let { drawImage(sheets.muscle, from, cell, to, size, colorFilter = it, blendMode = BlendMode.Plus, filterQuality = FilterQuality.Medium) }
        }
    }
}

/** Where frame [index] is in a sheet of frames [frame] pixels a side. */
private fun ImageBitmap.cell(index: Int, frame: Int) = IntOffset(index % SHEET_COLUMNS * frame, min(index / SHEET_COLUMNS * frame, height - frame))

/**
 * What turns a channel of the muscle sheet into a role's colour, at a strength: the channel's
 * own strength there times how hard the rep is and how much of this frame is showing. A
 * strength is kept to one of [LEVELS] steps, far finer than can be seen, so that each filter is
 * made once and a rep played over and over makes no more of them.
 */
private class MuscleLights {
    private val stains = arrayOfNulls<ColorFilter>(ROLES.size * (LEVELS + 1))
    private val glows = arrayOfNulls<ColorFilter>(ROLES.size * (LEVELS + 1))

    /**
     * The role's colour stained into what is under it (for [BlendMode.Modulate]): white where the
     * channel is empty, which changes nothing, and the colour where it is full. Null where
     * [strength] is too little to show.
     */
    fun stain(role: Int, strength: Float): ColorFilter? = kept(stains, role, strength * STAIN) { amount ->
        val color = HEAT[role]

        // One row of the matrix: this part of the colour, taken away from white by as much as the channel holds.
        fun row(part: Float) = FloatArray(5).also {
            it[role] = -(1f - part) * amount
            it[4] = 255f
        }
        ColorMatrix(row(color.red) + row(color.green) + row(color.blue) + floatArrayOf(0f, 0f, 0f, 0f, 255f))
    }

    /** The role's colour as light (for [BlendMode.Plus]): the colour, as see-through as the channel is empty. */
    fun glow(role: Int, strength: Float): ColorFilter? = kept(glows, role, strength * GLOW) { amount ->
        val color = CORE[role]
        val alpha = FloatArray(5).also { it[role] = amount }
        ColorMatrix(floatArrayOf(0f, 0f, 0f, 0f, color.red * 255f, 0f, 0f, 0f, 0f, color.green * 255f, 0f, 0f, 0f, 0f, color.blue * 255f) + alpha)
    }

    private inline fun kept(filters: Array<ColorFilter?>, role: Int, strength: Float, matrix: (Float) -> ColorMatrix): ColorFilter? {
        val level = (strength * LEVELS).roundToInt().coerceIn(0, LEVELS)
        if (level == 0) return null
        val index = role * (LEVELS + 1) + level
        return filters[index] ?: ColorFilter.colorMatrix(matrix(level / LEVELS.toFloat())).also { filters[index] = it }
    }

    companion object {
        /** The roles in the order the muscle sheet's channels hold them: red, green, blue. */
        val ROLES = floatArrayOf(PRIME_MOVER, SYNERGIST, STABILIZER)

        /**
         * What a working muscle is stained on the stone, by role: heat, as in a forge, the hotter the harder it works.
         * (The study's own role colours are for a diagram; its yellow, stained into pale stone, is hardly a colour at all.)
         */
        val HEAT = listOf(Color(0xFFFF6A14), Color(0xFFE2381A), Color(0xFF9A3A6E))

        /** And the light it gives off besides, brightest at its core. */
        val CORE = listOf(Color(0xFFFFC454), Color(0xFFFF8A2E), Color(0xFFB04C7E))

        /** How far a muscle at full strength is stained its colour, and how much light it gives besides. */
        const val STAIN = 1f
        const val GLOW = 0.62f
        const val LEVELS = 64
    }
}

/** A name's place beside the rendered figure: which head, which of the body's two sides its line goes to, which side of the picture it is set on, and how far down. */
private class Callout(val head: HeadId, val right: Boolean, val left: Boolean, val role: Float, var y: Float)

/**
 * Sets the names of [named] out down either side of the frame [at], from where the heads are in
 * [reference] (the middle of the rep), so that the names stay put while their lines follow the
 * muscles. Seen from the front or the back there are two of every head, and the names go turn
 * about, each to the head on its own side; seen from the side, a name goes to the side its head
 * is nearer.
 */
private fun setOut(reference: List<FrameAnchor>, named: Set<HeadId>, texts: Map<HeadId, TextLayoutResult>, at: Rect, height: Float, gap: Float): List<Callout> {
    val found = named.mapNotNull { head -> reference.filter { it.head == head }.takeIf { it.isNotEmpty() } }.sortedBy { anchors -> anchors.minOf { it.y } }
    if (found.isEmpty()) return emptyList()
    val mirrored = found.count { anchors -> anchors.size > 1 && abs(anchors[0].x - anchors[1].x) > MIRRORED_APART } * 2 >= found.size
    val middle = found.map { it.first().x }.sorted().let { it[it.size / 2] }
    val callouts = found.mapIndexed { index, anchors ->
        val left = if (mirrored) index % 2 == 1 else anchors.first().x < middle
        val to = if (left) anchors.minBy { it.x } else anchors.maxBy { it.x }
        Callout(to.head, to.right, left, to.role, at.top + to.y * at.height)
    }
    for (left in listOf(true, false)) {
        val column = callouts.filter { it.left == left }.sortedBy { it.y }
        if (column.isEmpty()) continue
        val row = column.maxOf { texts[it.head]?.size?.height ?: 0 } + gap
        // Keep the names from running into one another, then from running off the bottom.
        for (i in column.indices) column[i].y = max(column[i].y, if (i == 0) row / 2 else column[i - 1].y + row)
        for (i in column.indices.reversed()) column[i].y = min(column[i].y, if (i == column.lastIndex) height - row / 2 else column[i + 1].y - row)
    }
    return callouts
}

/** The names, each with a line in its role's colour to where its head is now: [blend] of the way from where [anchors] has it to where [next] does. */
private fun DrawScope.named(anchors: List<FrameAnchor>, next: List<FrameAnchor>, blend: Float, callouts: List<Callout>, texts: Map<HeadId, TextLayoutResult>, at: Rect, colors: FitnessPalette) {
    val inset = 6.dp.toPx()
    for (callout in callouts) {
        val text = texts[callout.head] ?: continue
        val color = roleColor(callout.role)
        val x = if (callout.left) inset else size.width - inset - text.size.width
        val top = callout.y - text.size.height / 2f
        // A head the frame has lost sight of keeps its name, without a line to nowhere.
        anchors.firstOrNull { it.head == callout.head && it.right == callout.right }?.let { anchor ->
            val after = next.firstOrNull { it.head == callout.head && it.right == callout.right } ?: anchor
            val target = Offset(at.left + lerp(anchor.x, after.x, blend) * at.width, at.top + lerp(anchor.y, after.y, blend) * at.height)
            val from = Offset(if (callout.left) x + text.size.width + 3.dp.toPx() else x - 3.dp.toPx(), callout.y)
            val knee = Offset(from.x + (if (callout.left) 1f else -1f) * 6.dp.toPx(), callout.y)
            drawLine(color.copy(alpha = 0.7f), from, knee, 1.dp.toPx())
            drawLine(color.copy(alpha = 0.7f), knee, target, 1.dp.toPx())
            drawCircle(STAGE, 2.6.dp.toPx(), target)
            drawCircle(color, 1.8.dp.toPx(), target)
        }
        drawText(text, color = colors.text, topLeft = Offset(x, top))
    }
}

/** The dark ground every figure stands on: the one the rendered badges were made on, so a badge has no edge. */
private val STAGE = Color(0xFF0B0C10)
private val BADGE_SIZE = 52.dp
private val BADGE_SHAPE = RoundedCornerShape(14.dp)
private val LARGE_HEIGHT = 300.dp
private val LARGE_SHAPE = RoundedCornerShape(18.dp)

/** A moment of the rep's clock at which it is held at its hard end ([repProgress] 1): what a badge shows. */
private const val HARD_END_SECONDS = 1.45f

/** How far down a rendered frame the floor is. */
private const val FLOOR_AT = 0.9f

/** How far apart, as a share of the frame, a head's two anchors are when the lifter is seen from the front or back and not the side. */
private const val MIRRORED_APART = 0.05f
