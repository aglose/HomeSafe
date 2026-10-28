package com.meticulouscreations.homesafe.fitness.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.fitness.HealthUiState
import com.meticulouscreations.homesafe.fitness.domain.FEET_PER_METER
import com.meticulouscreations.homesafe.fitness.domain.HeartZone
import com.meticulouscreations.homesafe.fitness.domain.METERS_PER_MILE
import com.meticulouscreations.homesafe.fitness.domain.NotRun
import com.meticulouscreations.homesafe.fitness.domain.NotRunReason
import com.meticulouscreations.homesafe.fitness.domain.RouteAccess
import com.meticulouscreations.homesafe.fitness.domain.Run
import com.meticulouscreations.homesafe.fitness.domain.RunDetail
import com.meticulouscreations.homesafe.fitness.domain.RunTime
import com.meticulouscreations.homesafe.fitness.domain.RunWeek
import com.meticulouscreations.homesafe.fitness.domain.Runs
import com.meticulouscreations.homesafe.fitness.domain.RunsBoard
import com.meticulouscreations.homesafe.fitness.domain.SECONDS_PER_DAY
import com.meticulouscreations.homesafe.fitness.domain.ZoneBounds
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.resolve
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.fitness_heart_open
import homesafe.shared.generated.resources.fitness_history_none
import homesafe.shared.generated.resources.fitness_history_when
import homesafe.shared.generated.resources.fitness_run_all
import homesafe.shared.generated.resources.fitness_run_badge_fastest
import homesafe.shared.generated.resources.fitness_run_badge_longest
import homesafe.shared.generated.resources.fitness_run_bpm
import homesafe.shared.generated.resources.fitness_run_feet
import homesafe.shared.generated.resources.fitness_run_heart
import homesafe.shared.generated.resources.fitness_run_heart_described
import homesafe.shared.generated.resources.fitness_run_last
import homesafe.shared.generated.resources.fitness_run_last_timed
import homesafe.shared.generated.resources.fitness_run_miles
import homesafe.shared.generated.resources.fitness_run_name_afternoon
import homesafe.shared.generated.resources.fitness_run_name_evening
import homesafe.shared.generated.resources.fitness_run_name_midday
import homesafe.shared.generated.resources.fitness_run_name_morning
import homesafe.shared.generated.resources.fitness_run_name_night
import homesafe.shared.generated.resources.fitness_run_none
import homesafe.shared.generated.resources.fitness_run_not_run_walk
import homesafe.shared.generated.resources.fitness_run_not_run_workout
import homesafe.shared.generated.resources.fitness_run_not_runs_note
import homesafe.shared.generated.resources.fitness_run_not_runs_title
import homesafe.shared.generated.resources.fitness_run_not_shared
import homesafe.shared.generated.resources.fitness_run_open_run
import homesafe.shared.generated.resources.fitness_run_pace
import homesafe.shared.generated.resources.fitness_run_reading
import homesafe.shared.generated.resources.fitness_run_reason_walk
import homesafe.shared.generated.resources.fitness_run_reason_workout
import homesafe.shared.generated.resources.fitness_run_route
import homesafe.shared.generated.resources.fitness_run_route_ask
import homesafe.shared.generated.resources.fitness_run_route_described
import homesafe.shared.generated.resources.fitness_run_row_stats
import homesafe.shared.generated.resources.fitness_run_row_timed
import homesafe.shared.generated.resources.fitness_run_source
import homesafe.shared.generated.resources.fitness_run_split_mile
import homesafe.shared.generated.resources.fitness_run_splits
import homesafe.shared.generated.resources.fitness_run_spm
import homesafe.shared.generated.resources.fitness_run_stat_cadence
import homesafe.shared.generated.resources.fitness_run_stat_calories
import homesafe.shared.generated.resources.fitness_run_stat_climb
import homesafe.shared.generated.resources.fitness_run_stat_distance
import homesafe.shared.generated.resources.fitness_run_stat_heart
import homesafe.shared.generated.resources.fitness_run_stat_miles
import homesafe.shared.generated.resources.fitness_run_stat_pace
import homesafe.shared.generated.resources.fitness_run_stat_peak
import homesafe.shared.generated.resources.fitness_run_stat_runs
import homesafe.shared.generated.resources.fitness_run_stat_steps
import homesafe.shared.generated.resources.fitness_run_stat_time
import homesafe.shared.generated.resources.fitness_run_this_week
import homesafe.shared.generated.resources.fitness_run_treadmill
import homesafe.shared.generated.resources.fitness_run_week
import homesafe.shared.generated.resources.fitness_run_week_none
import homesafe.shared.generated.resources.fitness_run_weeks_described
import homesafe.shared.generated.resources.fitness_run_weeks_from
import homesafe.shared.generated.resources.fitness_run_weeks_title
import homesafe.shared.generated.resources.fitness_run_zones_unset
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** How the fitness screens write a run's numbers. */
internal object RunFormat {
    /** Miles to the hundredth, as a running watch shows them: "3.10". */
    fun miles(meters: Double): String {
        val hundredths = (meters / METERS_PER_MILE * 100).roundToLong().coerceAtLeast(0)
        return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
    }

    /** "3.10 mi". */
    fun distance(meters: Double): UiText = UiText.of(Res.string.fitness_run_miles, miles(meters))

    /** Miles for a week's total, to the tenth: "12.4 mi". */
    fun weekDistance(week: RunWeek): UiText = UiText.of(Res.string.fitness_run_miles, FitnessFormat.number(week.miles))

    /** Minutes and seconds a mile, "9:12 /mi"; a dash for a run with no pace. */
    fun pace(secondsPerMile: Int?): UiText =
        secondsPerMile?.let { UiText.of(Res.string.fitness_run_pace, FitnessFormat.clock(it)) } ?: UiText.of(Res.string.fitness_history_none)

    /** How long a run took, as a stopwatch has it: "28:14", and past an hour "1:02:45". */
    fun clock(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        val tail = (s % 60).toString().padStart(2, '0')
        return if (s >= 3_600) "${s / 3_600}:${(s % 3_600 / 60).toString().padStart(2, '0')}:$tail" else "${s / 60}:$tail"
    }

    /** A climb in feet: "1,240 ft". */
    fun feet(meters: Double): UiText = UiText.of(Res.string.fitness_run_feet, FitnessFormat.grouped((meters * FEET_PER_METER).roundToLong()))

    fun name(time: RunTime): StringResource = when (time) {
        RunTime.MORNING -> Res.string.fitness_run_name_morning
        RunTime.MIDDAY -> Res.string.fitness_run_name_midday
        RunTime.AFTERNOON -> Res.string.fitness_run_name_afternoon
        RunTime.EVENING -> Res.string.fitness_run_name_evening
        RunTime.NIGHT -> Res.string.fitness_run_name_night
    }
}

/** A run's three numbers in a line: how far, how fast, how long. Only the last for one with no distance. */
@Composable
private fun runLine(run: Run): String = if (run.meters > 0) {
    stringResource(Res.string.fitness_run_row_stats, RunFormat.distance(run.meters).resolve(), RunFormat.pace(run.paceSeconds).resolve(), RunFormat.clock(run.seconds))
} else {
    RunFormat.clock(run.seconds)
}

/** This week's running in a line, "12.4 mi this week · 3 runs", or that there has been none. */
@Composable
internal fun runWeekLine(week: RunWeek?): String = if (week == null || week.runs == 0) {
    stringResource(Res.string.fitness_run_week_none)
} else {
    pluralStringResource(Res.plurals.fitness_run_week, week.runs, RunFormat.weekDistance(week).resolve(), week.runs)
}

/** The last run in a line for Today's door: how long ago, how far and how fast. Null when there has been none. */
@Composable
internal fun lastRunLine(health: HealthUiState): String? {
    val last = health.runs.runs.firstOrNull() ?: return null
    val ago = FitnessFormat.whenText(last.startEpochSeconds, health.nowEpochSeconds, health.utcOffsetSeconds).resolve()
    return if (last.meters > 0) {
        stringResource(Res.string.fitness_run_last, ago, RunFormat.distance(last.meters).resolve(), RunFormat.pace(last.paceSeconds).resolve())
    } else {
        stringResource(Res.string.fitness_run_last_timed, ago, RunFormat.clock(last.seconds))
    }
}

/**
 * Every run the health store holds, the latest first, under what this week came to and the last
 * eight weeks as bars of miles. The longest run and the quickest are marked. A run opens onto
 * its own page. Runs aren't started here: the band (or the Google Health app) records them, and
 * they turn up once it has synced.
 */
@Composable
internal fun RunsScreen(health: HealthUiState, padding: PaddingValues, onOpenRun: (String) -> Unit, onManage: () -> Unit, modifier: Modifier = Modifier) {
    val colors = FitnessTheme.colors
    val type = FitnessTheme.type
    val board = health.runs
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = FitnessGutter).testTag("fitness_runs"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!health.runsShared) {
            FitnessCard(Modifier.testTag("fitness_runs_unshared")) {
                Text(stringResource(Res.string.fitness_run_not_shared), style = type.body, color = colors.textMuted)
                Spacer(Modifier.height(14.dp))
                ManageButton(onManage)
            }
            return@Column
        }
        WeekCard(board)
        if (board.runs.isEmpty()) {
            FitnessCard(Modifier.testTag("fitness_runs_empty")) { Text(stringResource(Res.string.fitness_run_none), style = type.body, color = colors.textMuted) }
        } else {
            CardLabel(stringResource(Res.string.fitness_run_all), Modifier.padding(top = 6.dp))
            board.runs.forEach { run -> RunCard(run, longest = run.id == board.longestId, fastest = run.id == board.fastestId, onOpen = { onOpenRun(run.id) }) }
        }
        if (board.notRuns.isNotEmpty()) {
            CardLabel(stringResource(Res.string.fitness_run_not_runs_title), Modifier.padding(top = 6.dp))
            Text(stringResource(Res.string.fitness_run_not_runs_note), style = type.label, color = colors.textFaint)
            board.notRuns.forEach { left -> NotRunCard(left, onOpen = { onOpenRun(left.run.id) }) }
        }
        Text(stringResource(Res.string.fitness_run_source), style = type.label, color = colors.textFaint, modifier = Modifier.padding(top = 6.dp))
        ManageButton(onManage)
    }
}

/** This week in three numbers, and the last eight weeks' miles as bars, this week's lit. */
@Composable
private fun WeekCard(board: RunsBoard, modifier: Modifier = Modifier) {
    val colors = FitnessTheme.colors
    val type = FitnessTheme.type
    val week = board.thisWeek ?: RunWeek(0)
    FitnessCard(modifier.testTag("fitness_runs_week"), title = stringResource(Res.string.fitness_run_this_week)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat(FitnessFormat.number(week.miles), stringResource(Res.string.fitness_run_stat_miles), Modifier.weight(1f), color = if (week.runs > 0) colors.run.first else colors.text)
            Stat(week.runs.toString(), stringResource(Res.string.fitness_run_stat_runs), Modifier.weight(1f))
            Stat(FitnessFormat.duration(week.seconds).resolve(), stringResource(Res.string.fitness_run_stat_time), Modifier.weight(1f))
        }
        if (board.weeks.size > 1) {
            Spacer(Modifier.height(16.dp))
            CardLabel(stringResource(Res.string.fitness_run_weeks_title))
            Spacer(Modifier.height(8.dp))
            val most = board.weeks.maxOf { it.meters }.coerceAtLeast(1.0)
            val described = stringResource(Res.string.fitness_run_weeks_described, board.weeks.size, RunFormat.weekDistance(week).resolve())
            Row(
                Modifier.fillMaxWidth().height(86.dp).testTag("fitness_runs_weeks").clearAndSetSemantics { contentDescription = described },
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                board.weeks.forEachIndexed { index, each ->
                    val current = index == board.weeks.lastIndex
                    Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                        if (each.meters > 0) {
                            Text(FitnessFormat.number(each.miles), style = type.micro, color = if (current) colors.text else colors.textFaint, maxLines = 1, softWrap = false)
                            Spacer(Modifier.height(3.dp))
                        }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                // A week with no running keeps a foot, so the eight weeks are all there to count.
                                .height((64 * (each.meters / most)).coerceAtLeast(3.0).dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (current) Brush.verticalGradient(listOf(colors.run.first, colors.run.second)) else Brush.verticalGradient(listOf(colors.textFaint.copy(alpha = 0.5f), colors.textFaint.copy(alpha = 0.3f)))),
                        )
                    }
                }
            }
            Text(
                stringResource(Res.string.fitness_run_weeks_from, FitnessFormat.date(board.weeks.first().startEpochDay * SECONDS_PER_DAY, 0).resolve()),
                style = type.label,
                color = colors.textFaint,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** A run in the list: what it is called and when it was, how far, how fast and how long, its heart's average, and what it is the best at. */
@Composable
private fun RunCard(run: Run, longest: Boolean, fastest: Boolean, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val colors = FitnessTheme.colors
    val type = FitnessTheme.type
    val name = stringResource(RunFormat.name(run.time))
    val day = FitnessFormat.day(run.epochDay).resolve()
    Row(
        modifier
            .fillMaxWidth()
            .clip(FitnessCardShape)
            .background(colors.surface)
            .border(1.dp, colors.hairline, FitnessCardShape)
            .clickable(role = Role.Button, onClickLabel = stringResource(Res.string.fitness_run_open_run, name, day), onClick = onOpen)
            .testTag("fitness_run_${run.id}")
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(5.dp).height(54.dp).clip(CircleShape).background(Brush.verticalGradient(listOf(colors.run.first, colors.run.second))))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = type.headline, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(Res.string.fitness_history_when, day, FitnessFormat.timeOfDay(run.startEpochSeconds, run.utcOffsetSeconds).resolve()),
                style = type.label,
                color = colors.textFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(runLine(run), style = type.bodyStrong, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            RunBadges(run, longest, fastest, Modifier.padding(top = 4.dp))
        }
        run.averageBpm?.let { bpm ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Favorite, contentDescription = null, tint = colors.danger, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(Res.string.fitness_run_bpm, bpm), style = type.label, color = colors.textMuted, maxLines = 1)
            }
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = colors.textFaint, modifier = Modifier.padding(start = 4.dp).size(22.dp))
    }
}

/** What a session left out as no run was, in a word. */
private fun reasonName(reason: NotRunReason): StringResource = when (reason) {
    NotRunReason.WALK -> Res.string.fitness_run_reason_walk
    NotRunReason.WORKOUT -> Res.string.fitness_run_reason_workout
}

/** A session the band called a run that wasn't one: dimmer than a run, with what it looks to have been in place of a name. */
@Composable
private fun NotRunCard(left: NotRun, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val colors = FitnessTheme.colors
    val type = FitnessTheme.type
    val run = left.run
    val name = stringResource(reasonName(left.reason))
    val day = FitnessFormat.day(run.epochDay).resolve()
    Row(
        modifier
            .fillMaxWidth()
            .clip(FitnessCardShape)
            .border(1.dp, colors.hairline, FitnessCardShape)
            .clickable(role = Role.Button, onClickLabel = stringResource(Res.string.fitness_run_open_run, name, day), onClick = onOpen)
            .testTag("fitness_not_run_${run.id}")
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(Res.string.fitness_history_when, day, FitnessFormat.timeOfDay(run.startEpochSeconds, run.utcOffsetSeconds).resolve()),
                style = type.label,
                color = colors.textFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // A workout's pace over the few yards it moved means nothing.
            val line = if (left.reason == NotRunReason.WORKOUT && run.meters > 0) {
                stringResource(Res.string.fitness_run_row_timed, RunFormat.distance(run.meters).resolve(), RunFormat.clock(run.seconds))
            } else {
                runLine(run)
            }
            Text(line, style = type.body, color = colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(name, style = type.label, color = colors.textMuted, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = colors.textFaint, modifier = Modifier.padding(start = 4.dp).size(20.dp))
    }
}

/** What marks a run out: the longest there is, the quickest, or that it was run indoors. Nothing drawn for a run that is none of them. */
@Composable
private fun RunBadges(run: Run, longest: Boolean, fastest: Boolean, modifier: Modifier = Modifier) {
    if (!longest && !fastest && !run.treadmill) return
    val colors = FitnessTheme.colors
    val type = FitnessTheme.type
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        listOfNotNull(
            Res.string.fitness_run_badge_longest.takeIf { longest },
            Res.string.fitness_run_badge_fastest.takeIf { fastest },
        ).forEach { badge ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.EmojiEvents, contentDescription = null, tint = colors.gold, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(3.dp))
                Text(stringResource(badge), style = type.label, color = colors.gold, maxLines = 1)
            }
        }
        if (run.treadmill) Text(stringResource(Res.string.fitness_run_treadmill), style = type.label, color = colors.textFaint, maxLines = 1)
    }
}

/**
 * A run opened up: how far, how fast and how long in the logger's own numerals, the rest of what
 * was measured under them, its route where there is one, the heart rate through it with the
 * time in each zone, and its miles one by one. [detail] is null while the run's track is still
 * being read; the totals above it are already there. [bounds] are the zones in force, for
 * shading the heart-rate chart.
 */
@Composable
internal fun RunScreen(
    run: Run,
    detail: RunDetail?,
    board: RunsBoard,
    todayEpochDay: Long,
    padding: PaddingValues,
    onAskRoute: () -> Unit,
    modifier: Modifier = Modifier,
    bounds: ZoneBounds? = null,
    /** Opens the heart-rate page, where the zones are set; null where there is none. */
    onOpenHeart: (() -> Unit)? = null,
) {
    val colors = FitnessTheme.colors
    val type = FitnessTheme.type
    val none = stringResource(Res.string.fitness_history_none)
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = FitnessGutter).testTag("fitness_run"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column {
            Text(stringResource(RunFormat.name(run.time)), style = type.hero, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(
                    Res.string.fitness_history_when,
                    // The year, once it is no longer plain which one.
                    FitnessFormat.day(run.epochDay, withYear = todayEpochDay - run.epochDay > 300).resolve(),
                    FitnessFormat.timeOfDay(run.startEpochSeconds, run.utcOffsetSeconds).resolve(),
                ),
                style = type.body,
                color = colors.textMuted,
                modifier = Modifier.padding(top = 4.dp),
            )
            RunBadges(run, longest = run.id == board.longestId, fastest = run.id == board.fastestId, Modifier.padding(top = 6.dp))
            board.notRuns.firstOrNull { it.run.id == run.id }?.let { left ->
                Text(
                    stringResource(
                        when (left.reason) {
                            NotRunReason.WALK -> Res.string.fitness_run_not_run_walk
                            NotRunReason.WORKOUT -> Res.string.fitness_run_not_run_workout
                        },
                    ),
                    style = type.body,
                    color = colors.textMuted,
                    modifier = Modifier.padding(top = 6.dp).testTag("fitness_run_not_counted"),
                )
            }
        }

        FitnessCard(Modifier.testTag("fitness_run_stats")) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigStat(if (run.meters > 0) RunFormat.distance(run.meters).resolve() else none, stringResource(Res.string.fitness_run_stat_distance), Modifier.weight(1f), colors.run.first)
                BigStat(RunFormat.pace(run.paceSeconds).resolve(), stringResource(Res.string.fitness_run_stat_pace), Modifier.weight(1f))
                BigStat(RunFormat.clock(run.seconds), stringResource(Res.string.fitness_run_stat_time), Modifier.weight(1f))
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat(run.averageBpm?.let { stringResource(Res.string.fitness_run_bpm, it) } ?: none, stringResource(Res.string.fitness_run_stat_heart), Modifier.weight(1f))
                Stat(run.peakBpm?.let { stringResource(Res.string.fitness_run_bpm, it) } ?: none, stringResource(Res.string.fitness_run_stat_peak), Modifier.weight(1f))
                Stat(run.calories?.let { FitnessFormat.grouped(it.toLong()) } ?: none, stringResource(Res.string.fitness_run_stat_calories), Modifier.weight(1f))
            }
            if (run.cadence != null || run.climbMeters != null || run.steps != null) {
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Stat(run.cadence?.let { stringResource(Res.string.fitness_run_spm, it) } ?: none, stringResource(Res.string.fitness_run_stat_cadence), Modifier.weight(1f))
                    Stat(run.climbMeters?.let { RunFormat.feet(it).resolve() } ?: none, stringResource(Res.string.fitness_run_stat_climb), Modifier.weight(1f))
                    Stat(run.steps?.let { FitnessFormat.grouped(it) } ?: none, stringResource(Res.string.fitness_run_stat_steps), Modifier.weight(1f))
                }
            }
        }

        if (detail == null) {
            Text(stringResource(Res.string.fitness_run_reading), style = type.body, color = colors.textMuted, modifier = Modifier.testTag("fitness_run_reading"))
            return@Column
        }

        if (detail.route.size > 1) {
            FitnessCard(Modifier.testTag("fitness_run_route"), title = stringResource(Res.string.fitness_run_route)) { RouteSketch(detail, Modifier.fillMaxWidth().height(200.dp)) }
        } else if (run.route == RouteAccess.ASK) {
            GhostButton(stringResource(Res.string.fitness_run_route_ask), onAskRoute, Modifier.fillMaxWidth().testTag("fitness_run_route_ask"), icon = Icons.Filled.Route, tint = colors.run.first)
        }

        if (detail.heart.size > 1) {
            FitnessCard(Modifier.testTag("fitness_run_heart"), title = stringResource(Res.string.fitness_run_heart)) {
                HeartChart(detail, run.startEpochSeconds, run.endEpochSeconds, bounds, Modifier.fillMaxWidth().height(140.dp))
                Spacer(Modifier.height(14.dp))
                val summary = detail.summary
                if (summary != null) {
                    HeartSummaryBody(summary)
                } else {
                    Text(stringResource(Res.string.fitness_run_zones_unset), style = type.body, color = colors.textMuted)
                    if (onOpenHeart != null) {
                        Spacer(Modifier.height(10.dp))
                        GhostButton(stringResource(Res.string.fitness_heart_open), onOpenHeart, Modifier.fillMaxWidth().testTag("fitness_run_open_heart"), icon = Icons.Filled.MonitorHeart, tint = colors.textMuted)
                    }
                }
            }
        }

        if (detail.splits.isNotEmpty()) {
            FitnessCard(Modifier.testTag("fitness_run_splits"), title = stringResource(Res.string.fitness_run_splits)) { Splits(detail) }
        }
    }
}

/** One of a run's three headline numbers. */
@Composable
private fun BigStat(value: String, label: String, modifier: Modifier = Modifier, color: Color = FitnessTheme.colors.text) {
    Column(modifier) {
        Text(value, style = FitnessTheme.type.title, color = color, maxLines = 1, softWrap = false)
        Text(label, style = FitnessTheme.type.label, color = FitnessTheme.colors.textFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * A run mile by mile: its number (for the part of a mile it ended on, how far that was), its
 * pace, a bar that is longer the quicker the mile, and the heart's average through it.
 */
@Composable
private fun Splits(detail: RunDetail, modifier: Modifier = Modifier) {
    val colors = FitnessTheme.colors
    val type = FitnessTheme.type
    val splits = detail.splits
    val quickest = splits.minOf { it.paceSeconds }.coerceAtLeast(1)
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            Text(stringResource(Res.string.fitness_run_split_mile).uppercase(), style = type.micro, color = colors.textFaint, modifier = Modifier.width(46.dp))
            Text(stringResource(Res.string.fitness_run_stat_pace).uppercase(), style = type.micro, color = colors.textFaint, modifier = Modifier.weight(1f))
        }
        splits.forEach { split ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp).testTag("fitness_run_split_${split.number}"), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (split.whole) split.number.toString() else FitnessFormat.number(split.miles),
                    style = type.bodyStrong,
                    color = if (split.whole) colors.text else colors.textMuted,
                    maxLines = 1,
                    modifier = Modifier.width(46.dp),
                )
                Text(FitnessFormat.clock(split.paceSeconds), style = type.bodyStrong, color = colors.text, maxLines = 1, modifier = Modifier.width(54.dp))
                Box(Modifier.weight(1f).height(8.dp).clip(CircleShape).background(colors.surfaceRaised)) {
                    Box(
                        Modifier
                            .fillMaxWidth((quickest.toFloat() / split.paceSeconds.coerceAtLeast(1)).coerceIn(0.05f, 1f))
                            .height(8.dp)
                            .clip(CircleShape)
                            .background(Brush.horizontalGradient(listOf(colors.run.second, colors.run.first))),
                    )
                }
                Text(
                    split.averageBpm?.let { stringResource(Res.string.fitness_run_bpm, it) }.orEmpty(),
                    style = type.label,
                    color = colors.textMuted,
                    maxLines = 1,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(72.dp),
                )
            }
        }
    }
}

/** How many points the heart-rate chart is drawn from, however many readings there are. */
private const val CHART_POINTS = 160

/**
 * The heart rate through a run as a line, on bands of the zones' colours when zones are set, so
 * that where the line sits says how hard that stretch was. The line breaks where the band lost
 * the wrist for longer than a reading stands for: nothing is drawn across time nobody measured.
 * The lowest and highest readings are written at its side.
 */
@Composable
private fun HeartChart(detail: RunDetail, startEpochSeconds: Long, endEpochSeconds: Long, bounds: ZoneBounds?, modifier: Modifier = Modifier) {
    val colors = FitnessTheme.colors
    val type = FitnessTheme.type
    val heart = detail.heart
    // Readings a second apart for an hour are thousands of points: each sliver of the run is drawn at its average,
    // within the stretch of readings it belongs to.
    val lines = remember(heart, startEpochSeconds, endEpochSeconds) {
        val span = (endEpochSeconds - startEpochSeconds).coerceAtLeast(1).toDouble()
        Runs.heartStretches(heart).map { stretch ->
            stretch.groupBy { (((it.epochSeconds - startEpochSeconds) / span) * CHART_POINTS).toInt().coerceIn(0, CHART_POINTS - 1) }
                .map { (bucket, readings) -> Offset((bucket + 0.5f) / CHART_POINTS, readings.map { it.value }.average().toFloat()) }
                .sortedBy { it.x }
        }
    }
    val points = lines.flatten()
    if (points.size < 2) return
    val low = points.minOf { it.y }
    val high = points.maxOf { it.y }
    val described = stringResource(Res.string.fitness_run_heart_described, low.roundToInt(), high.roundToInt())
    val zoneColors = HeartZone.entries.map { colors.zone(it) }
    Box(modifier.testTag("fitness_run_heart_chart").clearAndSetSemantics { contentDescription = described }) {
        Canvas(Modifier.matchParentSize()) {
            // A little air above and below, so the line never runs along an edge.
            val floor = low - 6f
            val ceiling = high + 6f

            fun y(bpm: Float): Float = size.height * (1f - (bpm - floor) / (ceiling - floor))
            if (bounds != null) {
                HeartZone.entries.forEach { zone ->
                    val range = bounds.range(zone)
                    val top = y((range.last + 1).toFloat().coerceAtMost(ceiling)).coerceIn(0f, size.height)
                    val bottom = y(range.first.toFloat().coerceAtLeast(floor)).coerceIn(0f, size.height)
                    if (bottom > top) drawRect(zoneColors[zone.ordinal].copy(alpha = 0.14f), Offset(0f, top), Size(size.width, bottom - top))
                }
            }
            lines.forEach { stretch ->
                // A reading with none near it either side is still a reading: a dot.
                if (stretch.size == 1) drawCircle(colors.text, 1.5.dp.toPx(), Offset(stretch[0].x * size.width, y(stretch[0].y)))
                val line = Path()
                stretch.forEachIndexed { index, point ->
                    val x = point.x * size.width
                    if (index == 0) line.moveTo(x, y(point.y)) else line.lineTo(x, y(point.y))
                }
                drawPath(line, colors.text, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        Text(high.roundToInt().toString(), style = type.micro, color = colors.textMuted, modifier = Modifier.align(Alignment.TopStart).padding(4.dp))
        Text(low.roundToInt().toString(), style = type.micro, color = colors.textMuted, modifier = Modifier.align(Alignment.BottomStart).padding(4.dp))
    }
}

/**
 * A run's route as a line, north up, with where it began (a ring) and where it ended (a dot).
 * No map under it: the shape of a route is enough to know it by, and a map would send where the
 * runner lives to a tile server.
 */
@Composable
private fun RouteSketch(detail: RunDetail, modifier: Modifier = Modifier) {
    val colors = FitnessTheme.colors
    val route = detail.route
    val described = stringResource(Res.string.fitness_run_route_described)
    // Flat enough over a run: east-west shrunk by the latitude, so the route keeps its shape.
    val flat = remember(route) {
        val squeeze = cos(route.first().latitude * PI / 180)
        route.map { it.longitude * squeeze to -it.latitude }
    }
    Canvas(modifier.semantics { contentDescription = described }) {
        val west = flat.minOf { it.first }
        val east = flat.maxOf { it.first }
        val north = flat.minOf { it.second }
        val south = flat.maxOf { it.second }
        val inset = 12.dp.toPx()
        // An out-and-back along one street has no width to speak of: it is still given a little.
        val wide = (east - west).coerceAtLeast(1e-6)
        val tall = (south - north).coerceAtLeast(1e-6)
        val scale = minOf((size.width - inset * 2) / wide, (size.height - inset * 2) / tall)
        val left = (size.width - wide * scale) / 2
        val top = (size.height - tall * scale) / 2

        fun place(point: Pair<Double, Double>) = Offset((left + (point.first - west) * scale).toFloat(), (top + (point.second - north) * scale).toFloat())
        val line = Path()
        flat.forEachIndexed { index, point ->
            val at = place(point)
            if (index == 0) line.moveTo(at.x, at.y) else line.lineTo(at.x, at.y)
        }
        drawPath(line, Brush.linearGradient(listOf(colors.run.second, colors.run.first)), style = Stroke(4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(colors.run.second, 6.dp.toPx(), place(flat.first()), style = Stroke(2.5.dp.toPx()))
        drawCircle(colors.text, 5.dp.toPx(), place(flat.last()))
    }
}
