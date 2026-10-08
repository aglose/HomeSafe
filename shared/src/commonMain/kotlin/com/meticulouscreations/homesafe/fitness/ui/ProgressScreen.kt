package com.meticulouscreations.homesafe.fitness.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.finance.domain.Series
import com.meticulouscreations.homesafe.finance.ui.LocalChartStyle
import com.meticulouscreations.homesafe.finance.ui.components.ChartLine
import com.meticulouscreations.homesafe.finance.ui.components.LineChart
import com.meticulouscreations.homesafe.fitness.FitnessUiState
import com.meticulouscreations.homesafe.fitness.TrainedDay
import com.meticulouscreations.homesafe.fitness.domain.MuscleVolume
import com.meticulouscreations.homesafe.fitness.domain.PhaseKind
import com.meticulouscreations.homesafe.fitness.domain.SECONDS_PER_DAY
import com.meticulouscreations.homesafe.fitness.domain.VolumeStatus
import com.meticulouscreations.homesafe.fitness.ui.shader.PlasmaRing
import com.meticulouscreations.homesafe.text.resolve
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.fitness_lifts_of_best
import homesafe.shared.generated.resources.fitness_progress_board
import homesafe.shared.generated.resources.fitness_progress_board_empty
import homesafe.shared.generated.resources.fitness_progress_board_line
import homesafe.shared.generated.resources.fitness_progress_calendar
import homesafe.shared.generated.resources.fitness_progress_calendar_described
import homesafe.shared.generated.resources.fitness_progress_map
import homesafe.shared.generated.resources.fitness_progress_map_caption
import homesafe.shared.generated.resources.fitness_progress_map_described
import homesafe.shared.generated.resources.fitness_progress_phase
import homesafe.shared.generated.resources.fitness_progress_phase_about_bulk
import homesafe.shared.generated.resources.fitness_progress_phase_about_cut
import homesafe.shared.generated.resources.fitness_progress_phase_about_maintain
import homesafe.shared.generated.resources.fitness_progress_phase_cancel
import homesafe.shared.generated.resources.fitness_progress_phase_confirm
import homesafe.shared.generated.resources.fitness_progress_phase_switch
import homesafe.shared.generated.resources.fitness_progress_standing
import homesafe.shared.generated.resources.fitness_progress_standing_label
import homesafe.shared.generated.resources.fitness_progress_standing_none
import homesafe.shared.generated.resources.fitness_progress_volume_sets
import homesafe.shared.generated.resources.fitness_progress_weight
import homesafe.shared.generated.resources.fitness_progress_weight_chart
import homesafe.shared.generated.resources.fitness_progress_weight_empty
import homesafe.shared.generated.resources.fitness_progress_weight_less
import homesafe.shared.generated.resources.fitness_progress_weight_log
import homesafe.shared.generated.resources.fitness_progress_weight_more
import homesafe.shared.generated.resources.fitness_progress_weight_save
import homesafe.shared.generated.resources.fitness_progress_weight_today
import homesafe.shared.generated.resources.fitness_progress_weight_trend
import homesafe.shared.generated.resources.fitness_weight_pounds
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * How it is all going: the phase and how the lifts are holding up in it, bodyweight with the
 * noise taken out, the week's work drawn on the body, the last twelve weeks as a calendar, and
 * each lift against its best.
 */
@Composable
internal fun ProgressScreen(state: FitnessUiState, padding: PaddingValues, actions: FitnessActions, onOpenExercise: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = FitnessGutter).testTag("fitness_progress"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PhaseCard(state, actions.onStartPhase)
        BodyweightCard(state, actions.onLogBodyweight)
        MusclesCard(state.week.muscles)
        CalendarCard(state)
        StandingBoard(state, onOpenExercise)
    }
}

@Composable
private fun PhaseCard(state: FitnessUiState, onStartPhase: (PhaseKind) -> Unit) {
    val colors = FitnessTheme.colors
    val type = FitnessTheme.type
    val phase = state.phase
    val tints = colors.phase(phase.kind)
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    FitnessCard(Modifier.testTag("fitness_phase_card"), title = stringResource(Res.string.fitness_progress_phase)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(116.dp), contentAlignment = Alignment.Center) {
                PlasmaRing(phase.standing ?: 0f, tints.first, tints.second, colors.surfaceRaised, Modifier.matchParentSize())
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        phase.standing?.let { stringResource(Res.string.fitness_progress_standing, (it * 100).roundToInt()) } ?: stringResource(Res.string.fitness_progress_standing_none),
                        style = type.title,
                        color = colors.text,
                    )
                    Text(stringResource(Res.string.fitness_progress_standing_label), style = type.micro, color = colors.textFaint)
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                PhasePill(phase, {})
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(
                        when (phase.kind) {
                            PhaseKind.BULK -> Res.string.fitness_progress_phase_about_bulk
                            PhaseKind.CUT -> Res.string.fitness_progress_phase_about_cut
                            PhaseKind.MAINTAIN -> Res.string.fitness_progress_phase_about_maintain
                        },
                    ),
                    style = type.label,
                    color = colors.textMuted,
                )
                val change = state.bodyweight.weeklyChange
                val pace = phase.pace
                if (change != null && pace != null) {
                    Text(FitnessFormat.pace(phase.kind, pace, change).resolve(), style = type.label, color = tints.first, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        CardLabel(stringResource(Res.string.fitness_progress_phase_switch))
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PhaseKind.entries.forEach { kind ->
                val current = kind == phase.kind && phase.startedAtEpochSeconds > 0
                ChoiceChip(
                    stringResource(kind.label),
                    selected = current || pending == kind.name,
                    onClick = { pending = if (current) null else kind.name },
                    modifier = Modifier.testTag("fitness_phase_${kind.name.lowercase()}"),
                    tint = colors.phase(kind).first,
                )
            }
        }
        // A phase is a line drawn through the whole log, so starting one takes a second, deliberate tap.
        AnimatedVisibility(pending != null) {
            val kind = PhaseKind.entries.firstOrNull { it.name == pending } ?: return@AnimatedVisibility
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ForgeButton(
                    stringResource(Res.string.fitness_progress_phase_confirm, stringResource(kind.label)),
                    {
                        onStartPhase(kind)
                        pending = null
                    },
                    Modifier.weight(1f).testTag("fitness_phase_confirm"),
                    colors = colors.phase(kind),
                    height = 46.dp,
                )
                GhostButton(stringResource(Res.string.fitness_progress_phase_cancel), { pending = null })
            }
        }
    }
}

@Composable
private fun BodyweightCard(state: FitnessUiState, onLog: (Double) -> Unit) {
    val colors = FitnessTheme.colors
    val type = FitnessTheme.type
    val trend = state.bodyweight
    val latest = trend.latest
    var logging by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable(latest?.pounds) { mutableDoubleStateOf(latest?.pounds ?: 170.0) }
    FitnessCard(Modifier.testTag("fitness_weight_card"), title = stringResource(Res.string.fitness_progress_weight)) {
        if (latest == null) {
            Text(stringResource(Res.string.fitness_progress_weight_empty), style = type.body, color = colors.textMuted)
        } else {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(stringResource(Res.string.fitness_weight_pounds, FitnessFormat.number(latest.trend)), style = type.hero, color = colors.text)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.padding(bottom = 6.dp)) {
                    Text(stringResource(Res.string.fitness_progress_weight_trend), style = type.micro, color = colors.textFaint)
                    Text(stringResource(Res.string.fitness_progress_weight_today, FitnessFormat.number(latest.pounds)), style = type.label, color = colors.textMuted)
                }
            }
            if (trend.points.size >= 2) {
                Spacer(Modifier.height(12.dp))
                val scale = remember(trend) { Series(LongArray(trend.points.size) { trend.points[it].epochDay * SECONDS_PER_DAY }, DoubleArray(trend.points.size) { trend.points[it].pounds }) }
                val smooth = remember(trend) { Series(LongArray(trend.points.size) { trend.points[it].epochDay * SECONDS_PER_DAY }, DoubleArray(trend.points.size) { trend.points[it].trend }) }
                val tint = colors.phase(state.phase.kind).first
                CompositionLocalProvider(LocalChartStyle provides FitnessChartStyle) {
                    LineChart(
                        lines = listOf(ChartLine(smooth, tint, fill = true), ChartLine(scale, colors.textFaint, width = 1.2f, dashed = true)),
                        modifier = Modifier.fillMaxWidth().height(150.dp),
                        timeAxis = true,
                        periods = phaseBands(state.phases, scale.times.first(), scale.times.last()),
                        contentDescription = stringResource(Res.string.fitness_progress_weight_chart),
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (logging) {
            NumberStepper(
                label = stringResource(Res.string.fitness_progress_weight_log),
                value = FitnessFormat.number(draft),
                order = draft,
                onStep = { draft = ((draft * 10 + it * 2).roundToInt() / 10.0).coerceIn(50.0, 500.0) },
                minusLabel = stringResource(Res.string.fitness_progress_weight_less),
                plusLabel = stringResource(Res.string.fitness_progress_weight_more),
                modifier = Modifier.fillMaxWidth().testTag("fitness_weight_stepper"),
                tint = colors.phase(state.phase.kind).first,
            )
            Spacer(Modifier.height(10.dp))
            ForgeButton(
                stringResource(Res.string.fitness_progress_weight_save),
                {
                    onLog(draft)
                    logging = false
                },
                Modifier.fillMaxWidth().testTag("fitness_weight_save"),
                colors = colors.phase(state.phase.kind),
                icon = Icons.Filled.Check,
                height = 48.dp,
            )
        } else {
            GhostButton(stringResource(Res.string.fitness_progress_weight_log), { logging = true }, Modifier.fillMaxWidth().testTag("fitness_weight_log"))
        }
    }
}

@Composable
private fun MusclesCard(muscles: List<MuscleVolume>) {
    val colors = FitnessTheme.colors
    val type = FitnessTheme.type
    FitnessCard(Modifier.testTag("fitness_muscles_card"), title = stringResource(Res.string.fitness_progress_map)) {
        MuscleMap(muscles, Modifier.fillMaxWidth().height(250.dp), contentDescription = stringResource(Res.string.fitness_progress_map_described))
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
            listOf(VolumeStatus.LOW, VolumeStatus.BUILDING, VolumeStatus.ON_TARGET, VolumeStatus.HIGH).forEach { status ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(colors.volume(status)))
                    Spacer(Modifier.width(5.dp))
                    Text(stringResource(FitnessFormat.volume(status)), style = type.micro, color = colors.textFaint)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        val trained = muscles.filter { it.sets > 0 }.sortedByDescending { it.fill }
        trained.forEach { volume -> VolumeRow(volume) }
        Text(stringResource(Res.string.fitness_progress_map_caption), style = type.label, color = colors.textFaint, modifier = Modifier.padding(top = 6.dp))
    }
}

/** A muscle's week as a bar, with the band its sets are meant to land in marked on the track. */
@Composable
private fun VolumeRow(volume: MuscleVolume) {
    val colors = FitnessTheme.colors
    val type = FitnessTheme.type
    val still = LocalInspectionMode.current
    // The track runs to a little past the top of the band, so "plenty" has somewhere to show.
    val span = (volume.band.targetHigh * 1.35).toFloat()
    val fill by animateFloatAsState((volume.sets.toFloat() / span).coerceIn(0f, 1f), if (still) tween(0) else tween(800), label = "volume")
    val tint = colors.volume(volume.status)
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(volume.muscle.label), style = type.label, color = colors.textMuted, modifier = Modifier.width(92.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(
            Modifier.weight(1f).height(10.dp).drawBehind {
                val radius = CornerRadius(size.height / 2)
                drawRoundRect(colors.surfaceRaised, cornerRadius = radius)
                val from = size.width * (volume.band.targetLow.toFloat() / span)
                val to = size.width * (volume.band.targetHigh.toFloat() / span)
                drawRoundRect(colors.good.copy(alpha = 0.16f), Offset(from, 0f), Size(to - from, size.height), radius)
                drawRoundRect(tint, size = Size(size.width * fill, size.height), cornerRadius = radius)
            },
        )
        Text(
            stringResource(Res.string.fitness_progress_volume_sets, FitnessFormat.number(volume.sets)),
            style = type.label,
            color = tint,
            modifier = Modifier.width(64.dp).padding(start = 10.dp),
            maxLines = 1,
        )
    }
}

private const val CALENDAR_WEEKS = 12

/** The last twelve weeks, a square a day in the colour of what was trained: the habit, at a glance. */
@Composable
private fun CalendarCard(state: FitnessUiState) {
    val colors = FitnessTheme.colors
    val today = (state.nowEpochSeconds + state.utcOffsetSeconds).floorDiv(SECONDS_PER_DAY)
    // 1970-01-01 was a Thursday; weeks here start on Monday.
    val weekday = ((today + 3) % 7).toInt()
    val first = today - weekday - (CALENDAR_WEEKS - 1) * 7L
    val byDay = remember(state.calendar) { state.calendar.associateBy { it.epochDay } }
    val trained = state.calendar.count { it.epochDay >= first }
    val described = stringResource(Res.string.fitness_progress_calendar_described, trained, CALENDAR_WEEKS)
    FitnessCard(Modifier.testTag("fitness_calendar_card"), title = stringResource(Res.string.fitness_progress_calendar)) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(CALENDAR_WEEKS / 7f * 1.02f).semantics { contentDescription = described }) {
            val gap = 4.dp.toPx()
            val cell = (size.width - gap * (CALENDAR_WEEKS - 1)) / CALENDAR_WEEKS
            val radius = CornerRadius(cell * 0.28f)
            for (week in 0 until CALENDAR_WEEKS) {
                for (day in 0 until 7) {
                    val epochDay = first + week * 7L + day
                    if (epochDay > today) continue
                    val at = Offset(week * (cell + gap), day * (cell + gap))
                    val entry: TrainedDay? = byDay[epochDay]
                    val color = when {
                        entry == null -> colors.surfaceRaised
                        entry.focus != null -> colors.focus(entry.focus).first
                        else -> colors.amber
                    }
                    drawRoundRect(color, at, Size(cell, cell), radius)
                    if (epochDay == today) drawRoundRect(Color.White, at, Size(cell, cell), radius, style = Stroke(1.5.dp.toPx()))
                }
            }
        }
    }
}

/** Each lift that has been trained here, against the best it has ever been: the list to work down after a cut. */
@Composable
private fun StandingBoard(state: FitnessUiState, onOpenExercise: (String) -> Unit) {
    val colors = FitnessTheme.colors
    val type = FitnessTheme.type
    val lifts = remember(state.boards) { state.boards.filter { it.last != null && it.best != null }.sortedBy { it.standing }.take(10) }
    FitnessCard(Modifier.testTag("fitness_board_card"), title = stringResource(Res.string.fitness_progress_board)) {
        if (lifts.isEmpty()) {
            Text(stringResource(Res.string.fitness_progress_board_empty), style = type.body, color = colors.textMuted)
            return@FitnessCard
        }
        lifts.forEachIndexed { index, board ->
            if (index > 0) Spacer(Modifier.height(12.dp))
            val last = board.last ?: return@forEachIndexed
            val best = board.best ?: return@forEachIndexed
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) { onOpenExercise(board.exercise.id) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(board.exercise.name, style = type.bodyStrong, color = colors.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(Res.string.fitness_lifts_of_best, (board.standing * 100).roundToInt()), style = type.label, color = if (board.standing >= 0.995f) colors.gold else colors.ice, maxLines = 1)
                }
                val now = FitnessFormat.set(board.exercise.loadKind, last.weight, last.reps).resolve()
                Text(
                    if (board.standing >= 0.995f) now else stringResource(Res.string.fitness_progress_board_line, now, FitnessFormat.set(board.exercise.loadKind, best.weight, best.reps).resolve()),
                    style = type.label,
                    color = colors.textFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                StandingMeter(board.standing)
            }
        }
    }
}
