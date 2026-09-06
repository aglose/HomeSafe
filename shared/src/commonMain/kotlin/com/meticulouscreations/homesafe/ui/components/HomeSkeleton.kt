package com.meticulouscreations.homesafe.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The pieces of the home page's loading skeleton — shown while the server is authenticating
 * the sign-in, and again for the beat between landing on Home and the camera cache answering.
 * The home feed itself lays them out (see HomeFeed), in the real page's own layout, so the
 * real page fades in *onto* the skeleton rather than replacing a spinner. Progress is shown by
 * a bright head-and-tail running round each card outline, staggered card to card, and a soft
 * sheen sweeping the placeholder text.
 */

/** One lap of the outline runner, and one sweep of the sheen. */
private const val LOADING_PHASE_PERIOD_MS = 2400

/** Corner radius shared with the real camera cards, so the outline sits on the card it stands for. */
val SkeletonCardCornerRadius: Dp = 20.dp

/**
 * A 0..1 phase that advances with the display's frame clock. It is derived from the absolute
 * frame time, not from when this composable appeared, so two skeletons on screen in succession
 * (sign-in's, then Home's) are in step and the hand-over between them is invisible.
 *
 * Read it only inside a draw lambda: the value changes every frame, and a read in composition
 * would recompose the reader at the frame rate for nothing.
 */
@Composable
fun rememberLoadingPhase(): State<Float> {
    val phase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { nanos: Long ->
                val millis = nanos / 1_000_000L
                phase.floatValue = (millis % LOADING_PHASE_PERIOD_MS) / LOADING_PHASE_PERIOD_MS.toFloat()
            }
        }
    }
    return phase
}

/**
 * The outline of a camera card that is still on its way, with the runner going round it.
 * [phaseOffset] staggers the runner so a column of cards reads as a cascade, not a chorus line.
 */
@Composable
fun SkeletonCameraCard(
    phase: () -> Float,
    modifier: Modifier = Modifier,
    phaseOffset: Float = 0f,
) {
    val shape = RoundedCornerShape(SkeletonCardCornerRadius)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            // The runner is drawn outside the clip so its glow can straddle the edge.
            .outlineRunner(
                phase = phase,
                phaseOffset = phaseOffset,
                cornerRadius = SkeletonCardCornerRadius,
                color = MaterialTheme.colorScheme.primary,
            )
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f), shape),
    )
}

/**
 * A rounded placeholder bar the size of this layout, with a soft highlight sweeping across it
 * once per [phase] lap. Put it on a transparent Text of the words to come and the bar is
 * exactly the size the words will be.
 */
@Composable
fun Modifier.sheenBar(phase: () -> Float): Modifier {
    val base = MaterialTheme.colorScheme.surfaceContainerHighest
    val highlight = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    return drawWithCache {
        val radius = CornerRadius(size.height / 2f)
        onDrawBehind {
            val width = size.width
            // The band starts fully off the left edge and leaves fully off the right.
            val centre = (phase() * 2f - 0.5f) * width
            drawRoundRect(color = base, cornerRadius = radius)
            drawRoundRect(
                brush = Brush.linearGradient(
                    colors = listOf(Color.Transparent, highlight, Color.Transparent),
                    start = Offset(centre - width * 0.45f, 0f),
                    end = Offset(centre + width * 0.45f, 0f),
                ),
                cornerRadius = radius,
            )
        }
    }
}

/** How much of the perimeter the runner covers, head to the end of its tail. */
private const val RUNNER_FRACTION = 0.28f

/**
 * The runner's peak opacity. Well under full: it is a progress cue at the edge of attention,
 * not the thing on screen, and at full strength it pulled the eye off the greeting.
 */
private const val RUNNER_PEAK_ALPHA = 0.5f
private const val RUNNER_GLOW_PEAK_ALPHA = 0.12f

/** The tail is drawn as this many pieces of falling opacity. */
private const val RUNNER_PIECES = 12

/**
 * Draws a bright head with a fading tail travelling clockwise round this layout's rounded
 * outline, once per [phase] lap. The perimeter path is measured once per size; each frame only
 * extracts the pieces of it under the runner.
 */
fun Modifier.outlineRunner(
    phase: () -> Float,
    phaseOffset: Float,
    cornerRadius: Dp,
    color: Color,
    strokeWidth: Dp = 1.5.dp,
    glowWidth: Dp = 6.dp,
): Modifier = drawWithCache {
    val outline = Path().apply {
        addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(cornerRadius.toPx())))
    }
    val measure = PathMeasure().apply { setPath(outline, forceClosed = true) }
    val perimeter = measure.length
    val piece = Path()
    // Butt caps: adjoining pieces then meet edge to edge instead of overlapping their round
    // ends, which drew as a string of beads. The head alone gets a rounded end, below.
    val core = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Butt)
    val glow = Stroke(width = glowWidth.toPx(), cap = StrokeCap.Butt)
    val headCap = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
    val pieceLength = perimeter * RUNNER_FRACTION / RUNNER_PIECES

    onDrawWithContent {
        drawContent()
        if (perimeter <= 0f) return@onDrawWithContent
        val head = ((phase() + phaseOffset) % 1f) * perimeter
        for (index in 0 until RUNNER_PIECES) {
            val stop = head - index * pieceLength
            val start = stop - pieceLength
            val strength = 1f - index / RUNNER_PIECES.toFloat()
            piece.rewind()
            measure.appendSegment(start, stop, piece)
            drawPath(piece, color = color.copy(alpha = RUNNER_GLOW_PEAK_ALPHA * strength), style = glow)
            drawPath(piece, color = color.copy(alpha = RUNNER_PEAK_ALPHA * strength), style = core)
        }
        // A short, round-ended piece at the very front so the head reads as a point of light.
        piece.rewind()
        measure.appendSegment(head - strokeWidth.toPx(), head, piece)
        drawPath(piece, color = color.copy(alpha = RUNNER_PEAK_ALPHA), style = headCap)
    }
}

/** Appends the piece of the closed path from [start] to [stop] (in distance), wrapping past its end. */
private fun PathMeasure.appendSegment(start: Float, stop: Float, into: Path) {
    val total = length
    val wrappedStart = ((start % total) + total) % total
    val wrappedStop = wrappedStart + (stop - start)
    if (wrappedStop <= total) {
        getSegment(wrappedStart, wrappedStop, into, startWithMoveTo = true)
    } else {
        getSegment(wrappedStart, total, into, startWithMoveTo = true)
        getSegment(0f, wrappedStop - total, into, startWithMoveTo = true)
    }
}
