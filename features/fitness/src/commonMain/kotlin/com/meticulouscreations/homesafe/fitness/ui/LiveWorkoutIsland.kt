package com.meticulouscreations.homesafe.fitness.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meticulouscreations.homesafe.fitness.live.LivePhase
import com.meticulouscreations.homesafe.fitness.live.LiveWorkout
import com.meticulouscreations.homesafe.fitness.live.LiveWorkoutLines
import com.meticulouscreations.homesafe.text.resolve
import com.meticulouscreations.homesafe.ui.theme.albertSansFontFamily
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.common_list_separator
import homesafe.shared.generated.resources.fitness_live_hide
import homesafe.shared.generated.resources.fitness_live_island
import homesafe.shared.generated.resources.fitness_live_open
import homesafe.shared.generated.resources.fitness_rest_go
import homesafe.shared.generated.resources.fitness_rest_minus
import homesafe.shared.generated.resources.fitness_rest_minus_short
import homesafe.shared.generated.resources.fitness_rest_plus
import homesafe.shared.generated.resources.fitness_rest_plus_short
import homesafe.shared.generated.resources.fitness_rest_remaining
import homesafe.shared.generated.resources.fitness_rest_skip
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

/** The island's widths, small and opened out. */
val LiveWorkoutIslandWidth = 360.dp

/**
 * The workout in progress as a floating island, for a window that isn't the app's own: the Mac
 * puts it at the top of the screen, under the menu bar, while the app's window is away. Shaped
 * and laid out like the iPhone's Dynamic Island, so it reads the same on both:
 *
 * - **Small** ([expanded] false): the workout's mark on the left (a dumbbell between rests, the
 *   rest's ring draining while one counts, go once it has run out), what is happening in the
 *   middle, and the time on the right: the rest counting down, or how long the workout has gone.
 * - **Opened out** (the pointer over it): the heart and what the workout has come to, then the
 *   rest's buttons while one counts (fifteen seconds less, fifteen more, skip), a way into the
 *   workout's own page, and one to put the island away until the next rest.
 *
 * A click on it anywhere but a button opens the workout ([onOpen]). Everything in it reads from
 * [workout]; the time is read off [nowMillis] four times a second.
 */
@Composable
fun LiveWorkoutIsland(
    workout: LiveWorkout,
    expanded: Boolean,
    onOpen: () -> Unit,
    onSkipRest: () -> Unit,
    onAdjustRest: (Int) -> Unit,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
    nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    val still = LocalInspectionMode.current
    val now = remember { mutableLongStateOf(nowMillis()) }
    val clockNow by rememberUpdatedState(nowMillis)
    LaunchedEffect(still) {
        if (still) return@LaunchedEffect
        while (true) {
            now.longValue = clockNow()
            delay(250)
        }
    }
    val at = now.longValue
    val phase = workout.phase(at)
    val lines = remember(workout) { LiveWorkoutLines.of(workout) }
    val (accent, accent2) = remember(workout.focus) { FitnessPalette().focus(workout.focus) }
    val gold = FitnessPalette().gold
    val font = albertSansFontFamily()
    val headline = when (phase) {
        LivePhase.TRAINING -> lines.title
        LivePhase.RESTING -> lines.resting
        LivePhase.REST_OVER -> lines.restOver
    }.resolve()
    val detail = when (phase) {
        LivePhase.TRAINING -> lines.lift ?: lines.next ?: lines.summary
        LivePhase.RESTING -> lines.next ?: lines.lift ?: lines.summary
        LivePhase.REST_OVER -> lines.lift ?: lines.next ?: lines.summary
    }.resolve()
    val go = stringResource(Res.string.fitness_rest_go)
    val clock = when (phase) {
        LivePhase.RESTING -> workout.restClock(at)
        LivePhase.REST_OVER -> go
        LivePhase.TRAINING -> elapsed(((at - workout.startedAtEpochMillis) / 1000).coerceAtLeast(0))
    }
    val spokenTime = if (phase == LivePhase.RESTING) stringResource(Res.string.fitness_rest_remaining, clock) else clock
    val island = stringResource(Res.string.fitness_live_island)
    val separator = stringResource(Res.string.common_list_separator)
    val edge by animateColorAsState(if (phase == LivePhase.REST_OVER) gold else accent.copy(alpha = 0.45f))
    val shape = RoundedCornerShape(if (expanded) 30.dp else 26.dp)

    Column(
        modifier
            .widthIn(max = LiveWorkoutIslandWidth)
            .fillMaxWidth()
            .clip(shape)
            .background(IslandBlack)
            .border(1.dp, Brush.horizontalGradient(listOf(edge, IslandBlack, edge.copy(alpha = edge.alpha * 0.4f))), shape)
            .clickable(role = Role.Button, onClickLabel = stringResource(Res.string.fitness_live_open), onClick = onOpen)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag("fitness_live_island"),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                // One sentence for a screen reader, said again when the rest runs out.
                .clearAndSetSemantics {
                    contentDescription = listOf(island, headline, spokenTime, detail).joinToString(separator)
                    liveRegion = LiveRegionMode.Polite
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Mark(phase, workout.restLeft(at), accent, accent2, gold)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(headline, style = TextStyle(fontFamily = font, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail, style = TextStyle(fontFamily = font, fontSize = 12.sp, color = Color.White.copy(alpha = 0.62f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(10.dp))
            Text(
                clock,
                style = TextStyle(
                    fontFamily = font,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = when (phase) {
                        LivePhase.RESTING -> accent
                        LivePhase.REST_OVER -> gold
                        LivePhase.TRAINING -> Color.White
                    },
                    fontFeatureSettings = "tnum",
                ),
                maxLines = 1,
            )
        }
        AnimatedVisibility(expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.padding(top = 10.dp, start = 2.dp, end = 2.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    lines.heart?.let { heart ->
                        Icon(Icons.Filled.Favorite, null, tint = HeartPink, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(heart.resolve(), style = TextStyle(fontFamily = font, fontSize = 12.sp, color = HeartPink, fontFeatureSettings = "tnum"))
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(lines.summary.resolve(), style = TextStyle(fontFamily = font, fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (phase == LivePhase.RESTING) {
                        Pill(stringResource(Res.string.fitness_rest_minus_short), stringResource(Res.string.fitness_rest_minus), font, { onAdjustRest(-15) }, Modifier.weight(0.8f))
                        Pill(stringResource(Res.string.fitness_rest_plus_short), stringResource(Res.string.fitness_rest_plus), font, { onAdjustRest(15) }, Modifier.weight(0.8f))
                        Pill(stringResource(Res.string.fitness_rest_skip), null, font, onSkipRest, Modifier.weight(2.2f), icon = Icons.Filled.SkipNext, fill = accent.copy(alpha = 0.28f))
                    } else {
                        Pill(stringResource(Res.string.fitness_live_open), null, font, onOpen, Modifier.weight(1f), icon = Icons.Filled.OpenInFull, fill = accent.copy(alpha = 0.28f))
                    }
                    IconPill(Icons.Filled.KeyboardArrowUp, stringResource(Res.string.fitness_live_hide), onHide)
                }
            }
        }
    }
}

/** The workout's mark: the rest's ring draining round its icon while one counts. */
@Composable
private fun Mark(phase: LivePhase, restLeft: Float, accent: Color, accent2: Color, gold: Color) {
    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
        when (phase) {
            LivePhase.RESTING -> {
                Canvas(Modifier.size(36.dp)) {
                    val stroke = 3.5.dp.toPx()
                    val inset = stroke / 2
                    val arc = Size(size.width - stroke, size.height - stroke)
                    drawArc(Color.White.copy(alpha = 0.12f), 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
                    drawArc(Brush.sweepGradient(listOf(accent2, accent, accent2)), -90f, 360f * restLeft, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
                }
                Icon(Icons.Filled.Timer, null, tint = accent, modifier = Modifier.size(16.dp))
            }

            LivePhase.REST_OVER -> {
                // The time beside it already says go: the mark only says the next set is waiting.
                Box(Modifier.size(36.dp).clip(CircleShape).background(Brush.linearGradient(listOf(gold, accent))), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.PlayArrow, null, tint = IslandBlack, modifier = Modifier.size(22.dp))
                }
            }

            LivePhase.TRAINING -> {
                Box(Modifier.size(36.dp).clip(CircleShape).background(accent.copy(alpha = 0.18f)).border(1.5.dp, accent.copy(alpha = 0.7f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.FitnessCenter, null, tint = accent, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun Pill(
    text: String,
    spoken: String?,
    font: FontFamily,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    fill: Color = Color.White.copy(alpha = 0.1f),
) {
    Row(
        modifier
            .height(32.dp)
            .clip(CircleShape)
            .background(fill)
            .clickable(role = Role.Button, onClick = onClick)
            .then(if (spoken != null) Modifier.semantics { contentDescription = spoken } else Modifier)
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, style = TextStyle(fontFamily = font, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun IconPill(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(32.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.1f)).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(18.dp))
    }
}

/** How long the workout has gone, as a stopwatch: "24:07", and past an hour "1:04:07". */
internal fun elapsed(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    val mm = m.toString().padStart(2, '0')
    val ss = s.toString().padStart(2, '0')
    return if (h > 0) "$h:$mm:$ss" else "$m:$ss"
}

private val IslandBlack = Color(0xFF050506)
private val HeartPink = Color(0xFFFF5C8A)
