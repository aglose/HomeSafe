package com.meticulouscreations.homesafe.fitness.ui.shader

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.fitness.ui.LocalFitnessShaders
import kotlin.math.min

/** Where a shader's clock stands in a preview, which draws one frame: far enough in for everything to be on screen. */
private const val STILL_SECONDS = 14f

/**
 * Seconds for a shader's `time`, ticking every frame while [running] and starting over every
 * ten minutes so a float always has room for it. Still in a preview. Read it only while
 * drawing: a read during composition would recompose every frame.
 *
 * With [frameSeconds] it moves no more often than that: a glow or a ring of plasma looks the
 * same stepped thirty times a second as a hundred and twenty, and whatever reads this is drawn
 * again, over the whole of itself, each time it moves.
 */
@Composable
internal fun rememberFitnessClock(running: Boolean = true, frameSeconds: Float = 0f): FloatState {
    val still = LocalInspectionMode.current
    val time = remember { mutableFloatStateOf(STILL_SECONDS) }
    LaunchedEffect(running, still, frameSeconds) {
        if (!running || still) return@LaunchedEffect
        // An infinite animation's frames, so anything waiting for animations to settle doesn't wait on this.
        var last = withInfiniteAnimationFrameNanos { it }
        var owed = 0f
        while (true) {
            withInfiniteAnimationFrameNanos { now ->
                owed += ((now - last) / 1e9f).coerceIn(0f, 0.1f)
                last = now
                // A little short of the step is the step: frames don't land to the microsecond.
                if (owed >= frameSeconds - FRAME_SLACK_SECONDS) {
                    time.floatValue = (time.floatValue + owed) % 600f
                    owed = 0f
                }
            }
        }
    }
    return time
}

private const val FRAME_SLACK_SECONDS = 0.002f

/** How often a slow surface (the forge, a ring) moves on: thirty times a second. */
private const val SLOW_FRAME_SECONDS = 1f / 30f

/** How often the fibres move on: sixty times a second, since a finger drags them round. */
private const val FIBER_FRAME_SECONDS = 1f / 60f

@Composable
private fun rememberShader(source: String): FitnessShader? {
    val shaded = LocalFitnessShaders.current
    return remember(shaded, source) { if (shaded) fitnessShaderOrNull(source) else null }
}

/**
 * A layer of a surface's own, for whatever reads a [rememberFitnessClock] while drawing: each
 * tick then draws again only the surface, not everything else in the layer around it (a card's
 * words and outline, or a whole page that has no layer of its own between it and the surface).
 */
private fun Modifier.ownLayer(): Modifier = graphicsLayer {}

/**
 * The forge's glow behind a screen ([FORGE_SHADER]), crossfading to new colours when the phase
 * or the workout changes. [heat] 0–1 is how hard it burns. Where no shader compiles it is a soft
 * glow in the same colours.
 */
@Composable
internal fun ForgeBackground(tint: Color, tint2: Color, modifier: Modifier = Modifier, heat: Float = 0.5f, intensity: Float = 1f, running: Boolean = true) {
    val shader = rememberShader(FORGE_SHADER)
    val animatedTint by animateColorAsState(tint, tween(700), label = "forgeTint")
    val animatedTint2 by animateColorAsState(tint2, tween(700), label = "forgeTint2")
    val animatedHeat by animateFloatAsState(heat.coerceIn(0f, 1f), tween(900), label = "forgeHeat")
    val time = rememberFitnessClock(running, frameSeconds = SLOW_FRAME_SECONDS)
    Box(
        modifier.ownLayer().drawBehind {
            if (shader != null) {
                shader.setUniform("size", size.width, size.height)
                shader.setUniform("time", time.floatValue)
                shader.setUniform("tint", animatedTint)
                shader.setUniform("tint2", animatedTint2)
                shader.setUniform("heat", animatedHeat)
                shader.setUniform("intensity", intensity)
                drawRect(shader.brush())
            } else {
                drawRect(
                    Brush.radialGradient(
                        listOf(animatedTint.copy(alpha = 0.34f * intensity), animatedTint2.copy(alpha = 0.16f * intensity), Color.Transparent),
                        center = Offset(size.width * 0.7f, 0f),
                        radius = size.maxDimension * 0.75f,
                    ),
                )
            }
        },
    )
}

/**
 * A card's background of muscle fibres ([FIBER_SHADER]) in the workout's two colours. [seed]
 * 0–1 gives each card its own weave, [energy] 0–1 quickens the wave running down them. Given
 * [touches] (and the card given [fiberTouches] with the same), the fibres swirl round a finger
 * and ripple out from where it lands. Where no shader compiles, a slanted gradient of the same
 * colours with a glow under the finger.
 */
@Composable
internal fun FiberField(
    tint: Color,
    tint2: Color,
    seed: Float,
    modifier: Modifier = Modifier,
    energy: Float = 0f,
    running: Boolean = true,
    touches: FiberTouches? = null,
) {
    val shader = rememberShader(FIBER_SHADER)
    val animatedEnergy by animateFloatAsState(energy.coerceIn(0f, 1f), tween(500), label = "fiberEnergy")
    val time = rememberFitnessClock(running, frameSeconds = FIBER_FRAME_SECONDS)
    Box(
        modifier.ownLayer().drawBehind {
            val now = time.floatValue
            touches?.tick(now)
            if (shader != null) {
                shader.setUniform("size", size.width, size.height)
                shader.setUniform("time", now)
                shader.setUniform("tint", tint)
                shader.setUniform("tint2", tint2)
                shader.setUniform("seed", seed)
                shader.setUniform("energy", animatedEnergy)
                shader.setUniform("touches", touches?.marks ?: NO_MARKS)
                shader.setUniform("splash", touches?.splash ?: NO_SPLASH)
                drawRect(shader.brush())
            } else {
                drawRect(
                    Brush.linearGradient(
                        listOf(lerp(tint2, Color.Black, 0.55f), lerp(tint2, tint, 0.35f + 0.25f * animatedEnergy)),
                        start = Offset(0f, size.height),
                        end = Offset(size.width, 0f),
                    ),
                )
                touches?.forEachHeld { at -> drawCircle(Brush.radialGradient(listOf(tint.copy(alpha = 0.45f), Color.Transparent), at, size.height * 0.4f), size.height * 0.4f, at) }
            }
        },
    )
}

/** Long enough for a mark's swirl ([FIBER_SHADER]'s `held`) and ring to have faded to nothing. */
private const val SETTLED_SECONDS = 4f

private val NO_MARKS = FloatArray(FIBER_TOUCHES * 4) { if (it % 4 >= 2) -1f else 0f }
private val NO_SPLASH = FloatArray(FIBER_TOUCHES)

/**
 * Where fingers are on a [FiberField] and where they have lately been, for its shader to stir
 * the fibres round: [FIBER_TOUCHES] marks, the oldest given up for a new one. A finger makes a
 * mark where it lands and, as it travels, another every so often while the last settles, so a
 * drag leaves a wake behind it. Times are the field's own clock, which it keeps [now] up to.
 */
@Stable
internal class FiberTouches {
    /** `float4 touches[]`: x and y in pixels, when made (-1 for none), when its finger lifted (-1 while it's down). */
    val marks = NO_MARKS.copyOf()

    /** `float splash[]`: how strong a ring each mark sends out. */
    val splash = FloatArray(FIBER_TOUCHES)

    /** The field's clock as it was last drawn. */
    var now = 0f
        private set

    private var next = 0
    private val fingers = HashMap<Long, Int>()

    fun down(pointer: Long, at: Offset) {
        up(pointer)
        fingers[pointer] = mark(at, splash = 1f)
    }

    /** A finger [step] pixels or more from where it last left a mark leaves another. */
    fun move(pointer: Long, at: Offset, step: Float) {
        val slot = fingers[pointer]
        if (slot != null) {
            val dx = at.x - marks[slot * 4]
            val dy = at.y - marks[slot * 4 + 1]
            if (dx * dx + dy * dy < step * step) return
            marks[slot * 4 + 3] = now
        }
        fingers[pointer] = mark(at, splash = 0.35f)
    }

    fun up(pointer: Long) {
        fingers.remove(pointer)?.let { marks[it * 4 + 3] = now }
    }

    /**
     * Moves [now] on to the field's clock and empties each mark that has settled: its finger
     * lifted and its ring run out [SETTLED_SECONDS] ago, or made before the clock last started
     * over. The shader passes an empty mark by, so a card nobody has touched lately costs no more
     * than one with no marks at all.
     */
    fun tick(time: Float) {
        now = time
        for (slot in 0 until FIBER_TOUCHES) {
            val made = marks[slot * 4 + 2]
            val lifted = marks[slot * 4 + 3]
            if (made < 0f || lifted < 0f) continue
            if (time < made || (time - lifted > SETTLED_SECONDS && time - made > SETTLED_SECONDS)) {
                marks[slot * 4 + 2] = -1f
                marks[slot * 4 + 3] = -1f
                splash[slot] = 0f
            }
        }
    }

    /** Each place a finger is down now. */
    fun forEachHeld(action: (Offset) -> Unit) {
        fingers.values.forEach { action(Offset(marks[it * 4], marks[it * 4 + 1])) }
    }

    private fun mark(at: Offset, splash: Float): Int {
        val slot = next
        next = (next + 1) % FIBER_TOUCHES
        // A finger that held the mark given up keeps going from its next move.
        fingers.values.remove(slot)
        marks[slot * 4] = at.x
        marks[slot * 4 + 1] = at.y
        marks[slot * 4 + 2] = now
        marks[slot * 4 + 3] = -1f
        this.splash[slot] = splash
        return slot
    }
}

/**
 * Tells [touches] where fingers go down, travel and lift on this, only watching: taps, presses
 * and scrolls go on to whatever else handles them.
 */
internal fun Modifier.fiberTouches(touches: FiberTouches): Modifier = pointerInput(touches) {
    val step = 16.dp.toPx()
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { change ->
                val pointer = change.id.value
                when {
                    change.changedToDownIgnoreConsumed() -> touches.down(pointer, change.position)
                    change.changedToUpIgnoreConsumed() -> touches.up(pointer)
                    change.pressed -> touches.move(pointer, change.position, step)
                }
            }
        }
    }
}

/**
 * A ring filled clockwise from the top to [level] 0–1 in running plasma ([RING_SHADER]),
 * sweeping to a new level when it changes. Where no shader compiles, a gradient arc on a dim
 * track. [thickness] is the ring's width against its radius.
 */
@Composable
internal fun PlasmaRing(
    level: Float,
    tint: Color,
    tint2: Color,
    track: Color,
    modifier: Modifier = Modifier,
    thickness: Float = 0.16f,
    sweepMillis: Int = 900,
    running: Boolean = true,
) {
    val still = LocalInspectionMode.current
    val animated = animateFloatAsState(level.coerceIn(0f, 1f), if (still) tween(0) else tween(sweepMillis, easing = FastOutSlowInEasing), label = "ringLevel")
    PlasmaRing({ animated.value }, tint, tint2, track, modifier, thickness, running)
}

/**
 * [PlasmaRing] at a [level] that moves every frame, a rest draining say: it is read only while
 * drawing, so nothing around the ring is composed again as it moves, and it is drawn as it is,
 * with no sweep.
 */
@Composable
internal fun PlasmaRing(
    level: () -> Float,
    tint: Color,
    tint2: Color,
    track: Color,
    modifier: Modifier = Modifier,
    thickness: Float = 0.16f,
    running: Boolean = true,
) {
    val shader = rememberShader(RING_SHADER)
    val time = rememberFitnessClock(running, frameSeconds = SLOW_FRAME_SECONDS)
    Canvas(modifier.ownLayer()) {
        val filled = level().coerceIn(0f, 1f)
        if (shader != null) {
            shader.setUniform("size", size.width, size.height)
            shader.setUniform("time", time.floatValue)
            shader.setUniform("level", filled)
            shader.setUniform("tint", tint)
            shader.setUniform("tint2", tint2)
            shader.setUniform("track", track)
            shader.setUniform("thickness", thickness)
            drawRect(shader.brush())
        } else {
            val radius = min(size.width, size.height) / 2
            val stroke = radius * thickness
            val inset = stroke / 2 + radius * 0.07f
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            drawArc(track, -90f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            drawArc(
                Brush.sweepGradient(listOf(tint2, tint, tint2)),
                -90f,
                360f * filled,
                false,
                Offset(inset, inset),
                arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
    }
}

/**
 * A record's flash over the whole screen ([BURST_SHADER]), played once each time [token]
 * changes, from a point [origin] of the way across and down. Draws nothing between plays, and
 * nothing at all where no shader compiles: the banner that comes with it says it either way.
 */
@Composable
internal fun RecordBurst(token: Int, tint: Color, tint2: Color, modifier: Modifier = Modifier, origin: Offset = Offset(0.5f, 0.36f)) {
    val shader = rememberShader(BURST_SHADER)
    val progress = remember { Animatable(1f) }
    LaunchedEffect(token) {
        if (token == 0) return@LaunchedEffect
        progress.snapTo(0f)
        progress.animateTo(1f, tween(1500, easing = LinearEasing))
    }
    Box(
        modifier.drawBehind {
            val p = progress.value
            if (shader == null || p >= 1f) return@drawBehind
            shader.setUniform("size", size.width, size.height)
            shader.setUniform("center", size.width * origin.x, size.height * origin.y)
            shader.setUniform("progress", p)
            shader.setUniform("tint", tint)
            shader.setUniform("tint2", tint2)
            drawRect(shader.brush())
        },
    )
}
