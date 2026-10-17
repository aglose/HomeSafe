package com.meticulouscreations.homesafe.fitness.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.fitness.domain.WorkoutFocus
import kotlin.math.min

/** The side of the body this drawing is: [half] is drawn as it is and again mirrored, [whole] once. */
private class Drawing(val half: String, val whole: String = "")

/**
 * The part of the body a door works, in white line, as a sketch of the muscle would be: the
 * chest's pecs and abs, the back's traps and lats, the thighs, the delts, a flexed arm, and for
 * the runs the calves from behind. Drawn in a box 100 on a side, left half mirrored where the
 * part is a pair.
 */
internal enum class BodyOutline(private val drawing: Drawing) {
    CHEST(
        Drawing(
            half = "M42,4 C42,10 41,14 39,17 C33,20 26,21 20,23 C12,25 7,33 8,44 C8,50 10,55 12,58 C13,62 13,66 14,70 " +
                "M47,22 C40,22 32,23 26,26 C22,34 20,44 21,52 " +
                "M21,48 C24,58 32,64 42,63 C46,62 49,60 50,58 " +
                "M21,56 C24,64 25,72 25,80 C26,88 28,94 30,99 " +
                "M40,66 C39,76 39,88 41,99 " +
                "M41,73 C44,74 47,74 50,73 M41,85 C44,86 47,86 50,85 " +
                "M28,68 L33,70 M28,75 L33,76",
            whole = "M50,26 L50,99",
        ),
    ),
    BACK(
        Drawing(
            half = "M42,4 C42,9 41,13 39,16 C33,19 26,21 20,23 C12,25 7,33 8,44 C8,50 10,55 12,58 C13,62 13,66 14,70 " +
                "M30,21 C36,28 44,40 50,50 " +
                "M24,26 C20,32 18,40 19,48 " +
                "M22,40 C28,38 34,40 38,46 " +
                "M19,50 C22,60 24,70 26,80 C27,88 28,94 29,99 " +
                "M34,52 C34,64 38,76 46,86 " +
                "M45,82 C45,88 45,94 44,99",
            whole = "M50,14 L50,99",
        ),
    ),
    LEGS(
        Drawing(
            half = "M16,0 C10,14 7,32 9,48 C10,58 14,66 18,72 " +
                "M20,0 C30,4 40,7 47,10 C46,30 44,50 40,62 C39,66 38,69 37,72 " +
                "M22,8 C20,26 21,46 26,64 " +
                "M30,10 C33,26 34,40 34,52 " +
                "M41,50 C34,52 30,60 31,69 " +
                "M23,74 C23,70 31,70 31,74 C31,78 23,78 23,74 " +
                "M18,78 C15,86 15,93 17,100 M37,78 C41,85 41,93 38,100",
        ),
    ),
    SHOULDERS(
        Drawing(
            half = "M43,6 C43,14 42,18 40,22 C34,25 28,26 22,28 C10,30 3,42 4,56 C4,64 6,72 9,78 C10,84 11,92 12,99 " +
                "M48,26 C42,26 34,28 28,30 C22,40 20,56 22,70 C22,74 21,77 20,80 " +
                "M18,32 C14,44 13,58 16,72 " +
                "M9,78 C13,81 17,81 20,80 " +
                "M22,62 C28,72 38,76 50,74 " +
                "M22,74 C23,82 24,90 25,99",
            whole = "M50,30 L50,74",
        ),
    ),
    ARMS(
        Drawing(
            half = "",
            whole = "M6,74 C6,58 14,50 24,52 C30,40 52,36 60,54 C61,57 62,60 62,62 C62,50 63,38 64,28 " +
                "M6,74 C8,86 20,94 40,94 C56,94 68,92 76,86 C86,78 88,58 84,40 C83,34 82,30 82,28 " +
                "M64,28 C60,18 64,8 72,8 C80,8 86,12 86,20 C86,25 84,28 82,28 Z " +
                "M65,21 C70,23 76,23 81,21 " +
                "M22,72 C34,69 48,68 61,65 " +
                "M24,52 C22,60 20,66 18,72 " +
                "M71,72 C75,60 76,48 74,34",
        ),
    ),
    RUNS(
        Drawing(
            half = "M18,9 C24,12 34,12 41,9 " +
                "M15,4 C10,18 10,32 16,44 C20,54 24,62 26,74 C27,82 27,90 26,98 " +
                "M45,4 C50,20 50,38 44,52 C40,60 36,66 34,74 C33,82 33,90 34,98 " +
                "M30,14 C30,28 30,40 30,52 " +
                "M17,44 C21,52 26,54 30,52 C33,62 38,64 43,56",
        ),
    ),
    ;

    /** The drawing as one path, in its box of 100. */
    fun path(): Path {
        val path = Path()
        if (drawing.half.isNotEmpty()) {
            path.addPath(PathParser().parsePathString(drawing.half).toPath())
            val mirror = PathParser().parsePathString(drawing.half).toPath()
            // x becomes 100 - x: the other side.
            val flip = Matrix()
            flip.translate(BOX)
            flip.scale(-1f, 1f)
            mirror.transform(flip)
            path.addPath(mirror)
        }
        if (drawing.whole.isNotEmpty()) path.addPath(PathParser().parsePathString(drawing.whole).toPath())
        return path
    }

    companion object {
        const val BOX = 100f

        fun of(focus: WorkoutFocus): BodyOutline = when (focus) {
            WorkoutFocus.CHEST -> CHEST
            WorkoutFocus.BACK -> BACK
            WorkoutFocus.LEGS -> LEGS
            WorkoutFocus.SHOULDERS -> SHOULDERS
            WorkoutFocus.ARMS -> ARMS
        }
    }
}

/**
 * [outline] drawn as large as fits and centred, in thin white line with a soft halo, so it reads
 * over a card's moving fibres without competing with the words. Decoration: it says nothing to
 * a screen reader, the card's own label does.
 */
@Composable
internal fun BodyOutlineDrawing(outline: BodyOutline, modifier: Modifier = Modifier, alpha: Float = 0.85f) {
    val path = remember(outline) { outline.path() }
    Canvas(modifier) {
        val scale = min(size.width, size.height) / BodyOutline.BOX
        val line = 1.6.dp.toPx() / scale
        translate((size.width - BodyOutline.BOX * scale) / 2, (size.height - BodyOutline.BOX * scale) / 2) {
            withTransform({ scale(scale, scale, pivot = Offset.Zero) }) {
                drawPath(path, Color.White.copy(alpha = 0.14f * alpha), style = Stroke(line * 4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(path, Color.White.copy(alpha = alpha), style = Stroke(line, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}
