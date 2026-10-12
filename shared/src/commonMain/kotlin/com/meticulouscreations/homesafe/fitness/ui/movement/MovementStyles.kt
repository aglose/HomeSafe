package com.meticulouscreations.homesafe.fitness.ui.movement

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.VertexMode
import androidx.compose.ui.graphics.Vertices
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.meticulouscreations.homesafe.fitness.ui.FitnessPalette
import com.meticulouscreations.homesafe.fitness.ui.FitnessTheme
import com.meticulouscreations.homesafe.fitness.ui.LocalFitnessShaders
import com.meticulouscreations.homesafe.fitness.ui.shader.FitnessShader
import com.meticulouscreations.homesafe.fitness.ui.shader.fitnessShaderOrNull
import com.meticulouscreations.homesafe.fitness.ui.shader.rememberFitnessClock
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** The ways of painting the anatomy. Each draws the same heads on the same rig, coloured by the part each plays in the lift. */
internal enum class MovementStyle {
    /** A study in neutral tissue: the body grey, each working head in its role's colour. */
    STUDY,

    /**
     * The study, forged: a body of dark iron traced in the workout's light, the working heads lit
     * from inside in their role's colour with the work running down their strands, and the names
     * set out like a cabinet's read-out.
     */
    FORGE,

    /**
     * A figure of pale stone, lit like a thing in a room: no muscle is drawn, the body's surface
     * is worked out from the rig and shaded, so the muscles show as forms under it, and the
     * working ones glow through from their cores.
     */
    MARBLE,
}

/** What labels a figure's heads: the names come from whoever draws it, since they are words. */
internal class HeadNames(val style: TextStyle, val of: (HeadId) -> String)

/**
 * [movement] being performed, its muscles painted in [style]. [seconds] is the clock the rep
 * runs on, read while drawing. With [names], the heads doing the work are called out by name.
 */
@Composable
internal fun MovementFigure(
    movement: Movement,
    style: MovementStyle,
    tints: Pair<Color, Color>,
    seconds: () -> Float,
    modifier: Modifier = Modifier,
    names: HeadNames? = null,
) {
    val colors = FitnessTheme.colors
    val shaders = rememberShaders(style)
    val measurer = rememberTextMeasurer()
    val still = remember(movement) { Still(movement) }
    val scratch = remember { Scratch() }
    Canvas(modifier) {
        val time = seconds()
        val t = repProgress(time)
        val compact = size.minDimension < 80.dp.toPx()
        val callouts = names?.takeIf { !compact }?.let { Callouts(measurer, it, scratch.texts) }
        val fit = fit(movement.bounds, if (callouts != null) size.width * LIFT_MARGIN else 0f)
        val scene = Scene(fit, movement.figure(t), still.work, t, 0f, time, tints, colors, scratch, compact)
        val forged = style == MovementStyle.FORGE
        val steel = when (style) {
            MovementStyle.FORGE -> forgedSteel(scene)
            MovementStyle.MARBLE -> STUDIO_STEEL
            MovementStyle.STUDY -> steel(colors)
        }
        val stroke = scene.stroke(this)
        if (style == MovementStyle.MARBLE) stage(scene) else ground(scene, if (forged) tints.first.copy(alpha = 0.5f) else colors.hairline)
        equipment(scene, steel, stroke)
        if (style != MovementStyle.MARBLE) travel(scene, still.travel, tints.first.copy(alpha = if (forged) 0.9f else 0.6f), stroke, moving = forged)
        body(scene, style, shaders)
        equipment(scene, steel, stroke, grips = true)
        if (forged && !compact) scanlines()
        if (callouts != null) callouts(scene, style, callouts, still.middle, still.work.filterValues { it >= SYNERGIST }.keys)
    }
}

/** [MovementFigure] on its own clock, rep after rep while [running]. */
@Composable
internal fun MovementLoop(movement: Movement, style: MovementStyle, tints: Pair<Color, Color>, modifier: Modifier = Modifier, running: Boolean = true) {
    val clock = rememberFitnessClock(running)
    MovementFigure(movement, style, tints, { clock.floatValue }, modifier)
}

/** The whole body standing, as [view] sees it, every head painted in [style] and, with [names], labelled. */
@Composable
internal fun AnatomyPlate(view: View, style: MovementStyle, tints: Pair<Color, Color>, modifier: Modifier = Modifier, names: HeadNames? = null) {
    val colors = FitnessTheme.colors
    val shaders = rememberShaders(style)
    val measurer = rememberTextMeasurer()
    val figure = remember(view) { standing(view) }
    val scratch = remember { Scratch() }
    Canvas(modifier) {
        val callouts = names?.let { Callouts(measurer, it, scratch.texts) }
        val fit = fit(STANDING_BOUNDS, if (callouts != null) size.width * LABEL_MARGIN else 0f)
        val scene = Scene(fit, figure, emptyMap(), 0f, PLATE_TONE, 0f, tints, colors, scratch, compact = false)
        body(scene, style, shaders)
        if (style == MovementStyle.FORGE) scanlines()
        if (callouts != null) callouts(scene, style, callouts, scene.bands, scene.bands.mapNotNull { it.head.id }.toSet())
    }
}

/** The grain of fibre every muscle has; for the forge, the work that runs down the ones in use; and the stone figure's whole surface. */
internal class Shaders(val fibre: FitnessShader?, val pulse: FitnessShader?, val marble: FitnessShader?)

@Composable
internal fun rememberShaders(style: MovementStyle): Shaders {
    val shaded = LocalFitnessShaders.current
    return remember(style, shaded) {
        Shaders(
            fibre = if (shaded) fitnessShaderOrNull(FIBRE_SHADER) else null,
            pulse = if (shaded && style == MovementStyle.FORGE) fitnessShaderOrNull(PULSE_SHADER) else null,
            marble = if (shaded && style == MovementStyle.MARBLE) fitnessShaderOrNull(MARBLE_SHADER) else null,
        )
    }
}

/** How much of the width a labelled plate leaves on either side for the names. */
internal const val LABEL_MARGIN = 0.27f

/** The same for a lifter, who is wider than they are tall with the equipment: the names sit in the corners the figure leaves. */
internal const val LIFT_MARGIN = 0.09f

/** How strongly a standing plate's muscles are coloured, with no lift to say which are working. */
internal const val PLATE_TONE = 0.3f

/** What doesn't change over a rep: the way the weight goes, which heads do the work, and the figure mid-rep that the names are set out against. */
internal class Still(movement: Movement) {
    val travel = movement.travel()
    val work = movement.work()
    val middle = bands(movement.figure(0.5f))
}

internal class Scratch {
    val path = Path()
    val grain = Paint()
    val pulse = Paint().apply { blendMode = BlendMode.Plus }
    val texts = HashMap<HeadId, TextLayoutResult>()
    val bones = FloatArray(MARBLE_BONES * 4)
    val boneR = FloatArray(MARBLE_BONES * 4)
    val heads = FloatArray(MARBLE_HEADS * 4)
    val headR = FloatArray(MARBLE_HEADS * 4)
}

/** The figure's space on the canvas: [scale] pixels to a body height. */
internal class Fit(val scale: Float, private val x0: Float, private val y0: Float) {
    fun at(p: Offset) = Offset(x0 + p.x * scale, y0 - p.y * scale)
}

internal fun DrawScope.fit(bounds: Rect, margin: Float): Fit {
    val scale = min((size.width - margin * 2) / bounds.width, size.height / bounds.height)
    return Fit(scale, (size.width - bounds.width * scale) / 2 - bounds.left * scale, (size.height + bounds.height * scale) / 2 + bounds.top * scale)
}

internal class Scene(
    val fit: Fit,
    val figure: Figure,
    val work: Map<HeadId, Float>,
    /** How near the rep is to its hard end, 0–1. */
    val effort: Float,
    /** The colour every muscle has before any work: 0 on a lifter, more on a standing plate. */
    val tone: Float,
    /** The clock, in seconds, for what moves whether or not the lifter does. */
    val time: Float,
    val tints: Pair<Color, Color>,
    val colors: FitnessPalette,
    val scratch: Scratch,
    /** Drawn small, as a row's badge: the masses alone, without the fibres and edges that only crowd it. */
    val compact: Boolean,
) {
    val bands = bands(figure)
    val path get() = scratch.path

    fun stroke(scope: DrawScope) = with(scope) { (fit.scale * 0.008f).coerceIn(1.dp.toPx(), 2.dp.toPx()) }

    /** The part [head] plays in the lift, 0–1. */
    fun role(head: Head): Float = head.id?.let(work::get) ?: 0f

    /** How hard [head] is pulling now: its part, rising with the rep. */
    fun pull(head: Head): Float = max(tone, role(head) * (0.45f + 0.55f * effort))
}

/** Draws [block] to one side and lays the result down, so that clearing inside it cuts through only what it drew. */
internal inline fun DrawScope.layer(block: () -> Unit) {
    drawIntoCanvas { canvas ->
        canvas.saveLayer(Rect(Offset.Zero, size), Paint())
        block()
        canvas.restore()
    }
}

/** A rod with round ends, [grow] pixels fatter than it is. Opaque colours only: its three pieces overlap. */
internal fun DrawScope.rod(scene: Scene, bone: Bone, color: Color, grow: Float = 0f, blendMode: BlendMode = BlendMode.SrcOver) {
    val pa = scene.fit.at(bone.a)
    val pb = scene.fit.at(bone.b)
    val r1 = bone.ra * scene.fit.scale + grow
    val r2 = bone.rb * scene.fit.scale + grow
    drawCircle(color, r1, pa, blendMode = blendMode)
    val line = pb - pa
    val length = line.getDistance()
    if (length < 0.5f) return
    drawCircle(color, r2, pb, blendMode = blendMode)
    // The two lines touching both circles: their normals lean by the taper.
    val along = line / length
    val c = ((r1 - r2) / length).coerceIn(-1f, 1f)
    val s = sqrt(1f - c * c)
    val n1 = Offset(along.x * c - along.y * s, along.y * c + along.x * s)
    val n2 = Offset(along.x * c + along.y * s, along.y * c - along.x * s)
    scene.path.rewind()
    scene.path.moveTo(pa.x + n1.x * r1, pa.y + n1.y * r1)
    scene.path.lineTo(pb.x + n1.x * r2, pb.y + n1.y * r2)
    scene.path.lineTo(pb.x + n2.x * r2, pb.y + n2.y * r2)
    scene.path.lineTo(pa.x + n2.x * r1, pa.y + n2.y * r1)
    scene.path.close()
    drawPath(scene.path, color, blendMode = blendMode)
}

internal fun DrawScope.ground(scene: Scene, color: Color) {
    if (scene.compact) return
    val y = scene.fit.at(Offset.Zero).y
    drawLine(color, Offset(size.width * 0.04f, y), Offset(size.width * 0.96f, y), 1.dp.toPx())
}

/** How the bench and the iron are drawn: lines and dark pads, so the body is what is looked at. */
internal class Steel(val frame: Color, val pad: Color, val iron: Color, val ironEdge: Color, val grip: Color)

internal fun steel(colors: FitnessPalette) = Steel(
    frame = colors.text.copy(alpha = 0.22f),
    pad = Color(0xFF1A1D24),
    iron = Color(0xFF1A1D24),
    ironEdge = colors.text.copy(alpha = 0.34f),
    grip = colors.textMuted,
)

/** The equipment: everything behind the lifter, or with [grips] what the hand holds end on and whatever else is in front of them (it goes over the body). */
internal fun DrawScope.equipment(scene: Scene, steel: Steel, stroke: Float, grips: Boolean = false) {
    for (prop in scene.figure.props) {
        if ((prop.kind == PropKind.GRIP || prop.front) != grips) continue
        val a = scene.fit.at(prop.a)
        val b = scene.fit.at(prop.b)
        val r = prop.r * scene.fit.scale
        val disc = prop.a == prop.b
        when (prop.kind) {
            PropKind.FRAME -> if (disc) drawCircle(steel.frame, r, a, style = Stroke(stroke)) else drawLine(steel.frame, a, b, max(stroke, r), StrokeCap.Round)

            PropKind.PAD -> if (disc) drawCircle(steel.pad, r, a) else drawLine(steel.pad, a, b, r * 2, StrokeCap.Round)

            PropKind.CABLE -> drawLine(steel.frame, a, b, max(stroke * 0.6f, 1f))

            PropKind.WEIGHT -> if (disc) {
                // A plate in front of the lifter hides what is behind it; one behind them is a ring, so that it hides nothing.
                if (prop.front) drawCircle(steel.iron, r, a)
                drawCircle(steel.ironEdge, r, a, style = Stroke(stroke))
                drawCircle(steel.ironEdge, r * 0.4f, a, style = Stroke(stroke * 0.7f))
            } else {
                // Iron seen edge on, with a thin rim of light so that it shows against the dark.
                val rim = (b - a).let { it / max(it.getDistance(), 0.001f) } * (stroke * 0.5f)
                drawLine(steel.ironEdge.copy(alpha = steel.ironEdge.alpha * 0.55f), a - rim, b + rim, r * 2 + stroke)
                drawLine(steel.iron, a, b, r * 2)
            }

            PropKind.GRIP -> drawCircle(steel.grip, max(r, stroke), a)
        }
    }
}

/** The way the weight goes, as a dashed line with an arrowhead at the hard end; when [moving], the dashes run that way. */
internal fun DrawScope.travel(scene: Scene, way: List<Offset>, color: Color, stroke: Float, moving: Boolean) {
    if (scene.compact) return
    val points = way.map(scene.fit::at)
    if ((points.last() - points.first()).getDistance() < stroke * 6) return
    val path = Path().apply {
        moveTo(points.first().x, points.first().y)
        points.drop(1).forEach { lineTo(it.x, it.y) }
    }
    val dash = stroke * 2.6f
    val phase = if (moving) -scene.time * dash * 5f else 0f
    drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash * 1.2f), phase)))
    val tip = points.last()
    val back = (points[points.size - 2] - tip).let { it / max(it.getDistance(), 0.001f) }
    val wing = Offset(-back.y, back.x)
    val size = stroke * 4.2f
    drawLine(color, tip, tip + (back + wing * 0.7f) * size, stroke, StrokeCap.Round)
    drawLine(color, tip, tip + (back - wing * 0.7f) * size, stroke, StrokeCap.Round)
}

internal val RESTING_TISSUE = Color(0xFF7C7684)

/** The work, on a scale that reads in order however it is printed: plum for what steadies, through red-orange, to yellow for what does the lift. */
internal val ROLE_SCALE = listOf(0f to RESTING_TISSUE, STABILIZER to Color(0xFFA2428F), SYNERGIST to Color(0xFFF0603F), PRIME_MOVER to Color(0xFFFFD24A))

/** The colour of a head playing [role] in a lift. */
internal fun roleColor(role: Float): Color {
    val upper = ROLE_SCALE.indexOfFirst { it.first >= role }.coerceAtLeast(1)
    val (from, low) = ROLE_SCALE[upper - 1]
    val (to, high) = ROLE_SCALE[upper]
    return lerp(low, high, ((role - from) / (to - from)).coerceIn(0f, 1f))
}

/** The lifter: bones as the tissue between muscles, and every head over them, group by group so that a limb in front covers what is behind. */
internal fun DrawScope.body(scene: Scene, style: MovementStyle, shaders: Shaders) {
    val stroke = scene.stroke(this)
    val grain = max(scene.fit.scale * 0.0075f, 2.2f)
    // On Skia a draw scope's stroke paint believes its joins are round before it has told Skia so, and what it
    // believes it has is never passed on: ask for another join once, and the round ones asked for after this take.
    scene.path.rewind()
    drawPath(scene.path, Color.Transparent, style = Stroke(1f, join = StrokeJoin.Bevel))
    scene.scratch.grain.shader = shaders.fibre?.takeIf { !scene.compact }?.let {
        it.setUniform("grain", grain)
        (it.brush() as? ShaderBrush)?.createShader(size)
    }
    scene.scratch.pulse.shader = shaders.pulse?.takeIf { !scene.compact }?.let {
        it.setUniform("grain", grain)
        it.setUniform("time", scene.time)
        (it.brush() as? ShaderBrush)?.createShader(size)
    }
    val shapes = scene.bands.map { scene.drawn(it) }
    val marble = shaders.marble
    when {
        style == MovementStyle.FORGE -> forged(scene, shapes, stroke)
        // Where the stone figure's shader can't be had, the study stands in for it.
        style == MovementStyle.MARBLE && marble != null -> marble(scene, shapes, marble)
        else -> studied(scene, shapes, stroke)
    }
}

internal val STUDY_FASCIA = Color(0xFF4F4B56)
internal val STUDY_TENDON = Color(0xFFB9B4BF)
internal val STUDY_INK = Color(0xFF131217)

/** The study: neutral tissue, each part of the body cut clear of what is behind it. */
internal fun DrawScope.studied(scene: Scene, shapes: List<Drawn>, stroke: Float) {
    val gap = (scene.fit.scale * 0.006f).coerceIn(0.75.dp.toPx(), 1.5.dp.toPx())
    val paint: (Int) -> Unit = { index ->
        val band = scene.bands[index]
        val head = band.head
        val role = scene.role(head)
        val pull = scene.pull(head)
        // The colour says which part a head plays and does not change; how hard it pulls only brightens it.
        val belly = if (role <= 0f) lerp(RESTING_TISSUE, Color(0xFF9A93A3), pull) else lerp(lerp(roleColor(role), RESTING_TISSUE, 0.4f), roleColor(role), (pull / role).coerceIn(0f, 1f))
        val mesh = Mesh(shapes[index], head)
        drawIntoCanvas { it.drawVertices(mesh.lit(STUDY_TENDON, belly, glow = 0f), BlendMode.Modulate, scene.scratch.grain) }
        cuts(band, shapes[index], STUDY_TENDON.copy(alpha = 0.8f), stroke)
        // The crease between this head and its neighbours, which also hides the mesh's hard edge.
        outline(scene.path, shapes[index], 0f, BAND_STEPS - 1f)
        drawPath(scene.path, STUDY_INK.copy(alpha = if (scene.compact) 0.5f else 0.85f), style = Stroke(max(stroke * 0.5f, 1f), join = StrokeJoin.Round))
    }
    layer {
        for (group in Group.entries) {
            val far = group == Group.FAR_LEG
            if (!far) scene.bands.forEachIndexed { index, band -> if (band.head.group == group && band.head.under) paint(index) }
            val bones = scene.figure.bones.filter { it.group == group }
            bones.forEach { rod(scene, it, STUDY_FASCIA, grow = gap, blendMode = BlendMode.Clear) }
            bones.forEach { rod(scene, it, if (far) Color(0xFF26242B) else STUDY_FASCIA) }
            if (far) continue
            scene.bands.forEachIndexed { index, band -> if (band.head.group == group && !band.head.under) paint(index) }
            landmarks(scene, group, STUDY_TENDON, STUDY_INK, stroke)
        }
    }
}

/** The bony points that show between the muscles. */
internal fun DrawScope.landmarks(scene: Scene, group: Group, fill: Color, edge: Color, stroke: Float) {
    for (mark in landmarks(scene.figure.view)) {
        if (mark.group != group) continue
        for (limbs in scene.figure.limbs) {
            val at = scene.fit.at(limbs.at(mark.seg, mark.along, mark.out))
            drawCircle(fill, mark.r * scene.fit.scale, at)
            drawCircle(edge, mark.r * scene.fit.scale, at, style = Stroke(max(stroke * 0.5f, 1f)))
        }
    }
}

/** A band's borders on the canvas, the belly swollen by how hard it pulls. */
internal class Drawn(val a: List<Offset>, val b: List<Offset>) {
    /** A point [x] steps along and [across] of the way from one border to the other. */
    fun at(x: Float, across: Float): Offset {
        val i = x.toInt().coerceIn(0, BAND_STEPS - 2)
        val u = x - i
        val pa = a[i] + (a[i + 1] - a[i]) * u
        val pb = b[i] + (b[i + 1] - b[i]) * u
        return pa + (pb - pa) * across
    }
}

internal fun Scene.drawn(band: Band): Drawn {
    val work = role(band.head) * (0.25f + 0.75f * effort)
    val a = ArrayList<Offset>(BAND_STEPS)
    val b = ArrayList<Offset>(BAND_STEPS)
    for (i in 0 until BAND_STEPS) {
        val pa = fit.at(band.a[i])
        val pb = fit.at(band.b[i])
        val half = (pa - pb) / 2f
        // A working belly thickens in its middle: by a share of its width, but a broad sheet no more than a narrow strap does.
        val swell = min(half.getDistance() * 0.2f, fit.scale * 0.009f) * work * sin(PI.toFloat() * i / (BAND_STEPS - 1))
        val out = half / max(half.getDistance(), 0.001f)
        a += pa + out * swell
        b += pb - out * swell
    }
    return Drawn(a, b)
}

/** The outline of the stretch of [shape] from step [from] to step [to], into [path]: each border a smooth line through its steps, the two ends cut straight across. */
internal fun outline(path: Path, shape: Drawn, from: Float, to: Float) {
    path.rewind()
    val first = shape.at(from, 0f)
    path.moveTo(first.x, first.y)
    border(path, shape, from, to, 0f)
    shape.at(to, 1f).let { path.lineTo(it.x, it.y) }
    border(path, shape, to, from, 1f)
    path.close()
}

/** One border of [shape] ([across] 0 or 1) from step [from] to step [to], either way along it: a curve through every step on the way, so a round muscle has no corners. */
private fun border(path: Path, shape: Drawn, from: Float, to: Float, across: Float) {
    val first = min(from, to).toInt() + 1
    val between = (ceil(max(from, to)).toInt() - first).coerceAtLeast(0)
    val last = between + 1
    fun stop(k: Int): Offset = when {
        k <= 0 -> shape.at(from, across)
        k >= last -> shape.at(to, across)
        else -> shape.at((if (to > from) first + k - 1 else first + between - k).toFloat(), across)
    }
    for (k in 0 until last) {
        val p0 = stop(k - 1)
        val p1 = stop(k)
        val p2 = stop(k + 1)
        val p3 = stop(k + 2)
        path.cubicTo(p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f, p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f, p2.x, p2.y)
    }
}

/** The tendinous lines across a head that has them. */
internal fun DrawScope.cuts(band: Band, shape: Drawn, color: Color, stroke: Float) {
    for (cut in band.head.cuts) drawLine(color, shape.at(cut * (BAND_STEPS - 1), 0f), shape.at(cut * (BAND_STEPS - 1), 1f), max(stroke * 0.9f, 1.5f), StrokeCap.Round)
}

internal const val MESH_ACROSS = 7

/** Where the light comes from: over the viewer's left shoulder. */
internal val LIGHT = Offset(-0.42f, -0.58f)
internal const val LIGHT_TOWARD = 0.7f

/**
 * A head as a surface to shade: a grid of points across the band, each knowing how much of it is
 * belly and not tendon and how squarely it faces the light, as a rounded belly would. Its texture
 * coordinates are the muscle's own (pixels along it and across), for the shaders laid over it.
 */
internal class Mesh(shape: Drawn, private val head: Head) {
    private val positions = ArrayList<Offset>(BAND_STEPS * MESH_ACROSS)
    private val texture = ArrayList<Offset>(BAND_STEPS * MESH_ACROSS)
    private val meat = FloatArray(BAND_STEPS * MESH_ACROSS)
    private val facing = FloatArray(BAND_STEPS * MESH_ACROSS)
    private val indices = ArrayList<Int>((BAND_STEPS - 1) * (MESH_ACROSS - 1) * 6)

    init {
        val last = BAND_STEPS - 1
        var widest = 1f
        for (i in 0..last) widest = max(widest, (shape.a[i] - shape.b[i]).getDistance())
        var run = 0f
        for (i in 0..last) {
            val u = i / last.toFloat()
            if (i > 0) run += ((shape.a[i] + shape.b[i]) / 2f - (shape.a[i - 1] + shape.b[i - 1]) / 2f).getDistance()
            // Tendon at each end, running into the belly over a short stretch.
            val flesh = if (head.id == null) 0f else ramp(u, head.tendonIn, head.tendonIn + 0.1f) * (1f - ramp(u, 1f - head.tendonOut - 0.1f, 1f - head.tendonOut))
            val span = shape.b[i] - shape.a[i]
            val out = span / max(span.getDistance(), 0.001f)
            val ahead = (shape.a[min(i + 1, last)] + shape.b[min(i + 1, last)] - shape.a[max(i - 1, 0)] - shape.b[max(i - 1, 0)]).let { it / max(it.getDistance(), 0.001f) }
            // A flat tendon rounds less than a belly does. A belly rounds across itself, and a mass like the buttock along itself too.
            val round = 0.55f + 0.4f * flesh
            val tilt = (2f * u - 1f) * head.dome * (0.4f + 0.6f * flesh)
            for (j in 0 until MESH_ACROSS) {
                val v = j / (MESH_ACROSS - 1f) * 2f - 1f
                val at = i * MESH_ACROSS + j
                positions += shape.a[i] + span * (j / (MESH_ACROSS - 1f))
                texture += Offset(run, j / (MESH_ACROSS - 1f) * widest)
                meat[at] = flesh
                val toward = sqrt((1f - v * v * round * round - tilt * tilt).coerceAtLeast(0f))
                facing[at] = max(v * round * (out.x * LIGHT.x + out.y * LIGHT.y) + tilt * (ahead.x * LIGHT.x + ahead.y * LIGHT.y) + toward * LIGHT_TOWARD, 0f)
            }
        }
        for (i in 0 until last) {
            for (j in 0 until MESH_ACROSS - 1) {
                val at = i * MESH_ACROSS + j
                indices += listOf(at, at + 1, at + MESH_ACROSS, at + 1, at + MESH_ACROSS + 1, at + MESH_ACROSS)
            }
        }
    }

    /** Lit from over the shoulder, [tendon] running into [belly]. [glow] 0–1 is light of the belly's own, which no shadow dims. */
    fun lit(tendon: Color, belly: Color, glow: Float): Vertices = vertices { at ->
        val base = lerp(tendon, belly, meat[at])
        val light = (0.36f + 0.82f * facing[at]).let { it + (1f - it).coerceAtLeast(0f) * glow * meat[at] }
        val gloss = 0.3f * meat[at] * facing[at].pow(9)
        Color((base.red * light + gloss).coerceIn(0f, 1f), (base.green * light + gloss).coerceIn(0f, 1f), (base.blue * light + gloss).coerceIn(0f, 1f))
    }

    /** One [color] over the belly at [strength], fading out into the tendons: what a shader's light is tinted by. */
    fun flat(color: Color, strength: Float): Vertices = vertices { at ->
        val k = strength * meat[at]
        Color(color.red * k, color.green * k, color.blue * k)
    }

    private inline fun vertices(color: (Int) -> Color): Vertices = Vertices(VertexMode.Triangles, positions, texture, List(positions.size) { color(it) }, indices)
}

internal fun ramp(x: Float, from: Float, to: Float): Float {
    if (to <= from) return if (x >= to) 1f else 0f
    val u = ((x - from) / (to - from)).coerceIn(0f, 1f)
    return u * u * (3f - 2f * u)
}

internal class Callouts(val measurer: TextMeasurer, val names: HeadNames, val texts: MutableMap<HeadId, TextLayoutResult>)

/** A name's place: which side of the figure it is set on, and how far down. */
internal class Slot(val id: HeadId, val limb: Int, val left: Boolean, var y: Float)

/**
 * The names of the [labelled] heads, down either side of the figure, each with a line to its
 * head in its role's colour. Where each name goes is settled from [reference] (the figure
 * mid-rep), so the names stay put while the lines follow the muscles. The forge sets them in
 * capitals and gives each a three-step gauge of its role.
 */
internal fun DrawScope.callouts(scene: Scene, style: MovementStyle, callouts: Callouts, reference: List<Band>, labelled: Set<HeadId>) {
    val forged = style == MovementStyle.FORGE
    val seen = HashSet<HeadId>()
    val mirrored = scene.figure.limbs.size > 1
    val found = reference.filter { band -> band.limb == 0 && band.head.id.let { it != null && it in labelled && seen.add(it) } }.sortedBy { scene.fit.at(it.center).y }
    val middle = found.map { scene.fit.at(it.center).x }.sorted().let { if (it.isEmpty()) 0f else it[it.size / 2] }
    val slots = found.mapIndexed { index, band ->
        val id = band.head.id ?: return
        // Seen from the front or back there are two of everything: name them turn about, one on each side.
        val limb = if (mirrored) index % 2 else 0
        val at = scene.fit.at(reference.first { it.head.id == id && it.limb == limb }.center)
        Slot(id, limb, if (mirrored) limb == 1 else at.x < middle, at.y)
    }
    val inset = 3.dp.toPx()
    val textStyle = if (forged) callouts.names.style.copy(letterSpacing = 0.05.em) else callouts.names.style
    for (left in listOf(true, false)) {
        val column = slots.filter { it.left == left }.sortedBy { it.y }
        if (column.isEmpty()) continue
        val layouts = column.map { slot ->
            callouts.texts.getOrPut(slot.id) { callouts.measurer.measure(callouts.names.of(slot.id).let { if (forged) it.uppercase() else it }, textStyle) }
        }
        val row = layouts.maxOf { it.size.height } + (if (forged) 10.dp else 5.dp).toPx()
        // Keep the names from running into one another, then from running off the bottom.
        for (i in column.indices) column[i].y = max(column[i].y, if (i == 0) row / 2 else column[i - 1].y + row)
        for (i in column.indices.reversed()) column[i].y = min(column[i].y, if (i == column.lastIndex) size.height - row / 2 else column[i + 1].y - row)
        column.forEachIndexed { i, slot ->
            val layout = layouts[i]
            val band = scene.bands.firstOrNull { it.head.id == slot.id && it.limb == slot.limb } ?: return@forEachIndexed
            val target = scene.fit.at(band.center)
            val x = if (left) inset else size.width - inset - layout.size.width
            val role = scene.role(band.head)
            val color = if (role > 0f) roleColor(role) else scene.colors.textMuted
            val top = slot.y - layout.size.height / 2f
            val from = Offset(if (left) x + layout.size.width + inset else x - inset, slot.y)
            val knee = Offset(from.x + (if (left) 1f else -1f) * 6.dp.toPx(), slot.y)
            drawLine(color.copy(alpha = if (forged) 0.8f else 0.55f), from, knee, 1.dp.toPx())
            drawLine(color.copy(alpha = if (forged) 0.8f else 0.55f), knee, target, 1.dp.toPx())
            drawCircle(scene.colors.background, 2.6.dp.toPx(), target)
            drawCircle(color, 1.8.dp.toPx(), target)
            // A plate behind the name, so that a limb passing under it doesn't take the words with it.
            if (forged) drawRoundRect(scene.colors.background.copy(alpha = 0.72f), Offset(x - inset, top - inset), Size(layout.size.width + inset * 2, layout.size.height + inset * 2 + 5.dp.toPx()), CornerRadius(4.dp.toPx()))
            drawText(layout, color = scene.colors.text, topLeft = Offset(x, top))
            if (forged && role > 0f) {
                // The role as a gauge under the name: one bar lit for what steadies, two for what helps, three for what does the lift.
                val lit = (role * 3f).roundToInt().coerceIn(1, 3)
                val bar = Size(9.dp.toPx(), 2.5.dp.toPx())
                for (k in 0 until 3) {
                    val at = Offset(if (left) x + k * (bar.width + 2.dp.toPx()) else size.width - inset - (3 - k) * (bar.width + 2.dp.toPx()) + 2.dp.toPx(), top + layout.size.height + 2.dp.toPx())
                    drawRect(if (k < lit) color else scene.colors.text.copy(alpha = 0.16f), at, bar)
                }
            }
        }
    }
}
