package com.meticulouscreations.homesafe.fitness.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.fitness.domain.Rung
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max

/** A button that is the screen's one thing to do: a pill of the workout's heat. Sinks a little under the finger. */
@Composable
internal fun ForgeButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    colors: Pair<Color, Color> = FitnessTheme.colors.ember to FitnessTheme.colors.amber,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    height: Dp = 56.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium), label = "forgeButton")
    val haptics = LocalHapticFeedback.current
    Row(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.4f
            }
            .height(height)
            .clip(CircleShape)
            .background(Brush.horizontalGradient(listOf(colors.second, colors.first)))
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button) {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                onClick()
            }
            .padding(horizontal = 22.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = FitnessTheme.type.headline, color = Color.White, maxLines = 1)
    }
}

/** A quieter button: an outlined pill. */
@Composable
internal fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, tint: Color = FitnessTheme.colors.text) {
    Row(
        modifier
            .height(44.dp)
            .clip(CircleShape)
            .border(1.dp, tint.copy(alpha = 0.32f), CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = FitnessTheme.type.bodyStrong, color = tint, maxLines = 1)
    }
}

/** One of a row of choices. */
@Composable
internal fun ChoiceChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = FitnessTheme.colors.ember) {
    val colors = FitnessTheme.colors
    Box(
        modifier
            .height(36.dp)
            .clip(CircleShape)
            .background(if (selected) tint.copy(alpha = 0.22f) else colors.surfaceRaised)
            .border(1.dp, if (selected) tint else colors.hairline, CircleShape)
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = FitnessTheme.type.label, color = if (selected) colors.text else colors.textMuted, maxLines = 1)
    }
}

/** A small round button with an icon, named for a screen reader by [label]. */
@Composable
internal fun RoundIconButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 44.dp, tint: Color = FitnessTheme.colors.text, fill: Color = Color.Transparent) {
    Box(
        modifier.size(size).clip(CircleShape).background(fill).clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

/**
 * A number with a button either side of it, sized for a thumb between sets. A tap steps once;
 * holding a button keeps stepping, faster the longer it is held. The number rolls up or down to
 * its new value, and each step ticks under the finger. A tap on the number itself calls
 * [onEdit], to type one in.
 */
@Composable
internal fun NumberStepper(
    label: String,
    value: String,
    /** Rises and falls with the number, so the roll knows which way to go. */
    order: Double,
    onStep: (Int) -> Unit,
    minusLabel: String,
    plusLabel: String,
    modifier: Modifier = Modifier,
    tint: Color = FitnessTheme.colors.ember,
    caption: String? = null,
    onEdit: (() -> Unit)? = null,
) {
    val colors = FitnessTheme.colors
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        CardLabel(label)
        // The last value shown, outside the snapshot system: it only decides which way the number rolls.
        val previous = remember { DoubleArray(1) { order } }
        val rising = order >= previous[0]
        SideEffect { previous[0] = order }
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .then(if (onEdit != null) Modifier.clickable(role = Role.Button, onClick = onEdit) else Modifier)
                .padding(vertical = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = value,
                transitionSpec = {
                    val dir = if (rising) 1 else -1
                    (slideInVertically(tween(180)) { it * dir / 2 } + fadeIn(tween(140))) togetherWith (slideOutVertically(tween(180)) { -it * dir / 2 } + fadeOut(tween(100)))
                },
                label = "stepperValue",
            ) { shown ->
                Text(shown, style = FitnessTheme.type.numeral, color = colors.text, maxLines = 1, softWrap = false, textAlign = TextAlign.Center)
            }
        }
        if (caption != null) {
            Text(caption, style = FitnessTheme.type.label, color = colors.textFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(8.dp))
        // Under the number, each half the column wide: nothing to aim for, a thumb just lands on one.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StepButton(minus = true, minusLabel, tint, { onStep(-1) }, Modifier.weight(1f))
            StepButton(minus = false, plusLabel, tint, { onStep(1) }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun StepButton(minus: Boolean, label: String, tint: Color, onStep: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val step by rememberUpdatedState(onStep)
    val scope = rememberCoroutineScope()
    val press = remember { Animatable(1f) }
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier
            .height(52.dp)
            .graphicsLayer {
                scaleX = press.value
                scaleY = press.value
            }
            .clip(shape)
            .background(tint.copy(alpha = 0.16f))
            .border(1.dp, tint.copy(alpha = 0.55f), shape)
            .semantics {
                role = Role.Button
                contentDescription = label
                onClick {
                    step()
                    true
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    scope.launch { press.animateTo(0.88f, tween(70)) }
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    step()
                    // Held: keep stepping, slowly at first and then quickly.
                    val repeat = scope.launch {
                        delay(420)
                        var wait = 130L
                        while (true) {
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                            step()
                            delay(wait)
                            wait = max(45L, wait - 9L)
                        }
                    }
                    waitForUpOrCancellation()
                    repeat.cancel()
                    scope.launch { press.animateTo(1f, spring(dampingRatio = 0.5f)) }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // Drawn, not an icon: a plain bar and cross read at a glance and weigh nothing.
        Canvas(Modifier.size(18.dp)) {
            val stroke = 2.6.dp.toPx()
            drawLine(tint, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), stroke, cap = StrokeCap.Round)
            if (!minus) drawLine(tint, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), stroke, cap = StrokeCap.Round)
        }
    }
}

/**
 * An exercise's ladder as bars: a bar for each weight, lightest on the left, as tall as the most
 * reps ever done at it. The part of a bar done this phase is lit; the rest is the ghost of what
 * was done before. The rung to work on next ([targetWeight], aiming for [targetReps]) is marked
 * with a dashed outline to climb into and breathes. Bars rise one after another when the ladder
 * first appears. With more rungs than fit, it shows the top of the ladder.
 */
@Composable
internal fun LadderChart(
    ladder: List<Rung>,
    tint: Color,
    tint2: Color,
    modifier: Modifier = Modifier,
    targetWeight: Double? = null,
    targetReps: Int? = null,
    maxRungs: Int = 9,
    labels: Boolean = true,
    contentDescription: String = "",
) {
    val colors = FitnessTheme.colors
    val type = FitnessTheme.type
    val measurer = rememberTextMeasurer()
    // The target's own rung is drawn even when nothing has been done at it yet.
    val rungs = remember(ladder, targetWeight, maxRungs) {
        val withTarget = if (targetWeight != null && ladder.none { it.weight == targetWeight }) (ladder + Rung(targetWeight, 0)).sortedBy { it.weight } else ladder
        withTarget.takeLast(maxRungs)
    }
    val still = LocalInspectionMode.current
    val rise = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(rungs.size) { if (!still) rise.animateTo(1f, tween(500 + rungs.size * 70, easing = FastOutSlowInEasing)) }
    val breath by rememberInfiniteTransition(label = "ladder").animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "ladderBreath")
    val labelStyle = type.micro.copy(letterSpacing = TextStyle.Default.letterSpacing)

    Canvas(modifier.semantics { if (contentDescription.isNotEmpty()) this.contentDescription = contentDescription }) {
        if (rungs.isEmpty()) return@Canvas
        val foot = if (labels) 18.dp.toPx() else 0f
        val head = if (labels) 16.dp.toPx() else 0f
        val gap = (if (labels) 8.dp else 3.dp).toPx()
        val barWidth = ((size.width - gap * (rungs.size - 1)) / rungs.size).coerceAtMost(46.dp.toPx())
        val total = barWidth * rungs.size + gap * (rungs.size - 1)
        val left = (size.width - total) / 2
        val area = size.height - foot - head
        val top = max(rungs.maxOf { it.reps }, targetReps ?: 0).coerceAtLeast(1)
        val corner = CornerRadius(barWidth * 0.28f)

        rungs.forEachIndexed { i, rung ->
            val grown = ((rise.value * (rungs.size + 3) - i) / 3f).coerceIn(0f, 1f)
            val x = left + i * (barWidth + gap)
            val isTarget = targetWeight != null && rung.weight == targetWeight
            val allTime = area * rung.reps / top * grown
            val phase = area * (rung.phaseReps ?: 0) / top * grown
            val base = size.height - foot
            // A faint socket for the bar to stand in.
            drawRoundRect(colors.hairline, Offset(x, head), Size(barWidth, area), corner)
            if (allTime > 0f) {
                drawRoundRect(
                    Brush.verticalGradient(listOf(tint.copy(alpha = 0.42f), tint2.copy(alpha = 0.26f)), startY = base - allTime, endY = base),
                    Offset(x, base - allTime),
                    Size(barWidth, allTime),
                    corner,
                )
            }
            if (phase > 0f) {
                drawRoundRect(Brush.verticalGradient(listOf(tint, tint2), startY = base - phase, endY = base), Offset(x, base - phase), Size(barWidth, phase), corner)
            }
            if (isTarget && targetReps != null) {
                val goal = area * targetReps / top
                val glow = 0.45f + 0.55f * breath
                drawRoundRect(
                    colors.gold.copy(alpha = glow),
                    Offset(x, base - goal),
                    Size(barWidth, goal),
                    corner,
                    style = Stroke(1.6.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))),
                )
                drawRoundRect(colors.gold.copy(alpha = 0.10f * glow), Offset(x, base - goal), Size(barWidth, goal), corner)
            }
            if (labels) {
                val weight = measurer.measure(FitnessFormat.short(rung.weight), labelStyle.copy(color = if (isTarget) colors.gold else colors.textFaint), maxLines = 1)
                drawText(weight, topLeft = Offset(x + (barWidth - weight.size.width) / 2, base + 4.dp.toPx()))
                val shown = if (isTarget && targetReps != null && rung.reps == 0) targetReps else rung.reps
                if (shown > 0 && grown > 0.6f) {
                    val reps = measurer.measure(shown.toString(), labelStyle.copy(color = if (isTarget) colors.gold else colors.textMuted), maxLines = 1)
                    val tall = if (isTarget && targetReps != null) max(allTime, area * targetReps / top) else allTime
                    drawText(reps, topLeft = Offset(x + (barWidth - reps.size.width) / 2, (base - tall - reps.size.height - 2.dp.toPx()).coerceAtLeast(0f)))
                }
            }
        }
    }
}

/** How last time stands against the best ever: a bar that fills to [fraction], cold while there is ground to make up and gold once level. */
@Composable
internal fun StandingMeter(fraction: Float, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    val colors = FitnessTheme.colors
    val still = LocalInspectionMode.current
    val shown by animateFloatAsState(fraction.coerceIn(0f, 1f), if (still) tween(0) else tween(900, easing = FastOutSlowInEasing), label = "standing")
    Box(
        modifier.fillMaxWidth().height(height).clip(CircleShape).background(colors.surfaceRaised).drawBehind {
            val level = shown >= 0.995f
            drawRoundRect(
                Brush.horizontalGradient(if (level) listOf(colors.amber, colors.gold) else listOf(colors.ice.copy(alpha = 0.7f), colors.ice)),
                size = Size(size.width * shown, size.height),
                cornerRadius = CornerRadius(size.height / 2),
            )
        },
    )
}

/** A figure with a word under it. */
@Composable
internal fun Stat(value: String, label: String, modifier: Modifier = Modifier, color: Color = FitnessTheme.colors.text) {
    Column(modifier) {
        Text(value, style = FitnessTheme.type.title, color = color, maxLines = 1)
        Text(label, style = FitnessTheme.type.label, color = FitnessTheme.colors.textFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
