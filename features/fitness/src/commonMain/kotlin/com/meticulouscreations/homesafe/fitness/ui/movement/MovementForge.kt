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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// The forged study: MovementStyle.FORGE. What it draws over the shared anatomy, and its own shader.

/**
 * Work, running down a muscle: bright bars travelling from origin to insertion along the strands,
 * in the same muscle space as [FIBRE_SHADER]. It gives only how bright (the mesh carries the
 * head's colour) and is added over the muscle, so a head that isn't working draws none.
 */
internal const val PULSE_SHADER = """
uniform float grain;
uniform float time;
half4 main(float2 c) {
    float along = c.x / (grain * 15.0) - time * 1.9;
    float bar = pow(0.5 + 0.5 * cos(along * 6.2831853), 7.0);
    float strand = 0.55 + 0.45 * cos(c.y / grain * 6.2831853);
    return half4(half3(bar * strand), 1.0);
}
"""

/** A cabinet screen's lines, faintly, over the whole picture. */
internal fun DrawScope.scanlines() {
    val step = 3.dp.toPx()
    var y = step / 2
    while (y < size.height) {
        drawLine(Color.Black.copy(alpha = 0.16f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
        y += step
    }
}

/** The forge's equipment: the same lines, in the workout's own light. */
internal fun forgedSteel(scene: Scene) = Steel(
    frame = lerp(scene.tints.second, scene.colors.text, 0.25f).copy(alpha = 0.6f),
    pad = Color(0xFF171A22),
    iron = Color(0xFF171A22),
    ironEdge = scene.tints.first.copy(alpha = 0.75f),
    grip = scene.colors.text,
)

internal val IRON = Color(0xFF16181E)
internal val FAR_IRON = Color(0xFF0E0F13)
internal val GUNMETAL = Color(0xFF383D4B)
internal val STEEL_TENDON = Color(0xFF6A7284)

/**
 * The forge: each part of the body is first traced fat in the workout's light and then filled,
 * so only its outer edge is left glowing; inside, the resting heads are gunmetal and the working
 * ones are lit in their role's colour, with a bright edge, a bloom around it and the work running
 * down their strands.
 */
internal fun DrawScope.forged(scene: Scene, shapes: List<Drawn>, stroke: Float) {
    val light = lerp(scene.tints.first, Color.White, 0.18f)
    val haze = lerp(scene.colors.surface, scene.tints.first, 0.22f)
    val last = BAND_STEPS - 1f
    for (group in Group.entries) {
        val far = group == Group.FAR_LEG
        val bones = scene.figure.bones.filter { it.group == group }
        val mine = scene.bands.indices.filter { !far && scene.bands[it].head.group == group }
        // The haze, then the edge itself: both wider than the part, both covered by it except round its outside.
        for ((grow, color) in listOf(stroke * 2.6f to haze, stroke * 0.85f to if (far) lerp(scene.colors.surface, scene.tints.second, 0.6f) else light)) {
            if (scene.compact && grow > stroke) continue
            bones.forEach { rod(scene, it, color, grow = grow) }
            for (index in mine) {
                outline(scene.path, shapes[index], 0f, last)
                drawPath(scene.path, color, style = Stroke(grow * 2, join = StrokeJoin.Round))
            }
        }
        mine.forEach { if (scene.bands[it].head.under) forgedHead(scene, scene.bands[it], shapes[it], stroke) }
        bones.forEach { rod(scene, it, if (far) FAR_IRON else IRON) }
        mine.forEach { if (!scene.bands[it].head.under) forgedHead(scene, scene.bands[it], shapes[it], stroke) }
        if (!far) landmarks(scene, group, STEEL_TENDON, IRON, stroke)
    }
}

internal fun DrawScope.forgedHead(scene: Scene, band: Band, shape: Drawn, stroke: Float) {
    val head = band.head
    val role = scene.role(head)
    val pull = scene.pull(head)
    val live = if (role > 0f) (pull / role).coerceIn(0f, 1f) else 0f
    val hot = roleColor(role)
    // A prime mover burns in its full colour; what only steadies the lift stays nearer the iron.
    val heat = (0.45f + 0.55f * live) * (0.55f + 0.45f * role)
    val belly = if (role <= 0f) lerp(GUNMETAL, scene.tints.second, 0.1f + 0.25f * pull) else lerp(GUNMETAL, hot, heat)
    val mesh = Mesh(shape, head)
    drawIntoCanvas { it.drawVertices(mesh.lit(STEEL_TENDON, belly, glow = 0.5f * live), BlendMode.Modulate, scene.scratch.grain) }
    // The work, running origin to insertion down a head that is doing some.
    if (role > 0f && scene.scratch.pulse.shader != null) {
        drawIntoCanvas { it.drawVertices(mesh.flat(lerp(hot, Color.White, 0.35f), (0.2f + 0.8f * live) * role), BlendMode.Modulate, scene.scratch.pulse) }
    }
    cuts(band, shape, STEEL_TENDON, stroke)
    outline(scene.path, shape, 0f, BAND_STEPS - 1f)
    if (role <= 0f) {
        drawPath(scene.path, lerp(Color(0xFF07080A), scene.tints.first, 0.22f), style = Stroke(max(stroke * 0.5f, 1f), join = StrokeJoin.Round))
        return
    }
    // A working head's own edge, and the light it throws: wide and faint, then narrower and brighter, added to what is under it.
    if (!scene.compact) {
        drawPath(scene.path, hot.copy(alpha = (0.10f + 0.12f * live) * role), style = Stroke(stroke * 6f, join = StrokeJoin.Round), blendMode = BlendMode.Plus)
        drawPath(scene.path, hot.copy(alpha = (0.18f + 0.22f * live) * role), style = Stroke(stroke * 2.6f, join = StrokeJoin.Round), blendMode = BlendMode.Plus)
    }
    drawPath(scene.path, lerp(lerp(GUNMETAL, hot, 0.5f + 0.5f * role), Color.White, (0.25f + 0.4f * live) * role), style = Stroke(max(stroke * 0.6f, 1f), join = StrokeJoin.Round))
}
