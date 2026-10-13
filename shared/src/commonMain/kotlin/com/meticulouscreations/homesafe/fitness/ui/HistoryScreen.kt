package com.meticulouscreations.homesafe.fitness.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.fitness.FitnessUiState
import com.meticulouscreations.homesafe.fitness.SessionLift
import com.meticulouscreations.homesafe.fitness.WorkoutSession
import com.meticulouscreations.homesafe.fitness.domain.HeartSummary
import com.meticulouscreations.homesafe.fitness.domain.LoadKind
import com.meticulouscreations.homesafe.fitness.domain.PhaseKind
import com.meticulouscreations.homesafe.fitness.domain.RecordScope
import com.meticulouscreations.homesafe.fitness.domain.SECONDS_PER_DAY
import com.meticulouscreations.homesafe.fitness.domain.WorkoutFocus
import com.meticulouscreations.homesafe.text.resolve
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.fitness_history_day_none
import homesafe.shared.generated.resources.fitness_history_day_sessions
import homesafe.shared.generated.resources.fitness_history_day_today
import homesafe.shared.generated.resources.fitness_history_empty
import homesafe.shared.generated.resources.fitness_history_empty_month
import homesafe.shared.generated.resources.fitness_history_exercises
import homesafe.shared.generated.resources.fitness_history_in_progress
import homesafe.shared.generated.resources.fitness_history_list_month
import homesafe.shared.generated.resources.fitness_history_loose
import homesafe.shared.generated.resources.fitness_history_next_month
import homesafe.shared.generated.resources.fitness_history_none
import homesafe.shared.generated.resources.fitness_history_open
import homesafe.shared.generated.resources.fitness_history_previous_month
import homesafe.shared.generated.resources.fitness_history_records
import homesafe.shared.generated.resources.fitness_history_row_stats
import homesafe.shared.generated.resources.fitness_history_row_stats_short
import homesafe.shared.generated.resources.fitness_history_sets
import homesafe.shared.generated.resources.fitness_history_show_month
import homesafe.shared.generated.resources.fitness_history_stat_exercises
import homesafe.shared.generated.resources.fitness_history_stat_hours
import homesafe.shared.generated.resources.fitness_history_stat_records
import homesafe.shared.generated.resources.fitness_history_stat_reps
import homesafe.shared.generated.resources.fitness_history_stat_sets
import homesafe.shared.generated.resources.fitness_history_stat_time
import homesafe.shared.generated.resources.fitness_history_stat_volume
import homesafe.shared.generated.resources.fitness_history_stat_workouts
import homesafe.shared.generated.resources.fitness_history_this_month
import homesafe.shared.generated.resources.fitness_history_when
import homesafe.shared.generated.resources.fitness_logger_set_record
import homesafe.shared.generated.resources.fitness_session_delete
import homesafe.shared.generated.resources.fitness_session_delete_confirm
import homesafe.shared.generated.resources.fitness_session_delete_sets
import homesafe.shared.generated.resources.fitness_session_empty
import homesafe.shared.generated.resources.fitness_session_heart
import homesafe.shared.generated.resources.fitness_session_lifts
import homesafe.shared.generated.resources.fitness_session_open_exercise
import homesafe.shared.generated.resources.fitness_session_volume_caption
import homesafe.shared.generated.resources.fitness_workout_title
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Looking back: a month as a calendar, with each day something was trained lit in that workout's
 * colour, what the month came to, and under it the month's workouts with what each came to. A
 * tap on a lit day narrows the list to that day; a tap on a workout opens it ([SessionScreen]).
 * The arrows walk back month by month as far as the log goes, and forward no further than now.
 */
@Composable
internal fun HistoryScreen(state: FitnessUiState, padding: PaddingValues, onOpenSession: (WorkoutSession) -> Unit, modifier: Modifier = Modifier) {
    val colors = FitnessTheme.colors
    val type = FitnessTheme.type
    val today = (state.nowEpochSeconds + state.utcOffsetSeconds).floorDiv(SECONDS_PER_DAY)
    val current = monthIndex(today)
    // The month on show, counted in months so that a step is one more or one fewer. Until an arrow is tapped there is
    // none chosen, and the calendar follows the clock into a new month by itself.
    var chosen by rememberSaveable { mutableStateOf<Int?>(null) }
    val month = chosen ?: current
    // The day the list is narrowed to belongs to the month on show: another month, come to by an arrow or by the clock
    // running into it, starts with none.
    var picked by rememberSaveable(month) { mutableStateOf<Long?>(null) }
    val earliest = remember(state.history, current) { state.history.lastOrNull()?.let { monthIndex(it.epochDay) }?.coerceAtMost(current) ?: current }
    val first = firstDay(month)
    val next = firstDay(month + 1)
    val sessions = remember(state.history, first, next) { state.history.filter { it.epochDay in first until next } }
    val byDay = remember(sessions) { MonthDays(sessions.groupBy { it.epochDay }) }
    val monthName = stringResource(FitnessFormat.monthName(month.mod(12) + 1))

    // Held to the months there is something to show: a button that is off can still be pressed by a screen reader.
    fun show(target: Int) {
        chosen = target.coerceIn(earliest, current).takeIf { it != current }
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = FitnessGutter).testTag("fitness_history"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FitnessCard(Modifier.testTag("fitness_history_calendar")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(
                    Icons.Filled.ChevronLeft,
                    stringResource(Res.string.fitness_history_previous_month),
                    { show(month - 1) },
                    Modifier.testTag("fitness_history_previous"),
                    fill = colors.surfaceRaised,
                    enabled = month > earliest,
                )
                Text(
                    FitnessFormat.monthYear(month.floorDiv(12), month.mod(12) + 1).resolve(),
                    style = type.title,
                    color = colors.text,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.weight(1f).testTag("fitness_history_month"),
                )
                RoundIconButton(
                    Icons.Filled.ChevronRight,
                    stringResource(Res.string.fitness_history_next_month),
                    { show(month + 1) },
                    Modifier.testTag("fitness_history_next"),
                    fill = colors.surfaceRaised,
                    enabled = month < current,
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth().clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(CELL_GAP)) {
                repeat(7) { weekday ->
                    Text(stringResource(FitnessFormat.weekdayName(weekday)).uppercase(), style = type.micro, color = colors.textFaint, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(8.dp))
            AnimatedContent(
                targetState = month,
                transitionSpec = {
                    val way = if (targetState > initialState) 1 else -1
                    (slideInHorizontally(tween(260)) { it * way / 3 } + fadeIn(tween(200))) togetherWith (slideOutHorizontally(tween(260)) { -it * way / 3 } + fadeOut(tween(140)))
                },
                label = "historyMonth",
            ) { shown ->
                // The month that is leaving keeps its own days while it slides away.
                val days = if (shown == month) byDay else remember(state.history, shown) { MonthDays(state.history.filter { it.epochDay in firstDay(shown) until firstDay(shown + 1) }.groupBy { it.epochDay }) }
                MonthGrid(shown, days, today, picked.takeIf { shown == month }, onPick = { day -> picked = day.takeIf { it != picked } })
            }
            val shownFocuses = remember(sessions) { WorkoutFocus.entries.filter { focus -> sessions.any { it.focus == focus } } }
            val loose = sessions.any { it.workout == null }
            if (shownFocuses.isNotEmpty() || loose) {
                Spacer(Modifier.height(12.dp))
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    shownFocuses.forEach { LegendDot(colors.focus(it).first, stringResource(it.label)) }
                    if (loose) LegendDot(colors.amber, stringResource(Res.string.fitness_history_loose))
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth().testTag("fitness_history_month_stats"), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val records = sessions.sumOf { it.recordCount }
                // A day's loose sets are in the sets and the records, but they are no workout.
                Stat(sessions.count { it.workout != null }.toString(), stringResource(Res.string.fitness_history_stat_workouts), Modifier.weight(1f))
                Stat(sessions.sumOf { it.setCount }.toString(), stringResource(Res.string.fitness_history_stat_sets), Modifier.weight(1f))
                Stat(FitnessFormat.number(sessions.sumOf { it.durationSeconds ?: 0L } / 3_600.0), stringResource(Res.string.fitness_history_stat_hours), Modifier.weight(1f))
                Stat(records.toString(), stringResource(Res.string.fitness_history_stat_records), Modifier.weight(1f), color = if (records > 0) colors.gold else colors.text)
            }
        }

        val day = picked
        val listed = if (day == null) sessions else byDay[day]
        Row(Modifier.fillMaxWidth().padding(top = 4.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            CardLabel(if (day == null) stringResource(Res.string.fitness_history_list_month, monthName) else FitnessFormat.day(day).resolve(), Modifier.weight(1f))
            if (day != null) {
                ChoiceChip(stringResource(Res.string.fitness_history_show_month), selected = false, onClick = { picked = null }, modifier = Modifier.testTag("fitness_history_show_month"))
            } else if (month != current) {
                ChoiceChip(stringResource(Res.string.fitness_history_this_month), selected = false, onClick = { show(current) }, modifier = Modifier.testTag("fitness_history_this_month"))
            }
        }
        if (listed.isEmpty()) {
            Text(
                stringResource(if (state.history.isEmpty()) Res.string.fitness_history_empty else Res.string.fitness_history_empty_month),
                style = type.body,
                color = colors.textMuted,
                modifier = Modifier.padding(horizontal = 4.dp).testTag("fitness_history_empty"),
            )
        }
        listed.forEach { session -> SessionCard(session, { onOpenSession(session) }) }
    }
}

private val CELL_GAP = 6.dp

/** A month's sessions by the day each is filed under. Held as one thing that doesn't change, so the grid is redrawn only for another month's. */
@Immutable
private data class MonthDays(val byDay: Map<Long, List<WorkoutSession>>) {
    operator fun get(epochDay: Long): List<WorkoutSession> = byDay[epochDay].orEmpty()
}

/** Months since year 0 of the month [epochDay] falls in. */
private fun monthIndex(epochDay: Long): Int {
    val (year, month) = FitnessFormat.civil(epochDay)
    return year * 12 + month - 1
}

/** The first day of the month counted by [monthIndex]. */
private fun firstDay(month: Int): Long = FitnessFormat.firstOfMonth(month.floorDiv(12), month.mod(12) + 1)

/** A month's days in weeks that begin on Monday, a square a day. */
@Composable
private fun MonthGrid(month: Int, days: MonthDays, today: Long, picked: Long?, onPick: (Long) -> Unit, modifier: Modifier = Modifier) {
    val colors = FitnessTheme.colors
    val first = firstDay(month)
    val length = (firstDay(month + 1) - first).toInt()
    val lead = FitnessFormat.weekday(first)
    val monthName = stringResource(FitnessFormat.monthName(month.mod(12) + 1))
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CELL_GAP)) {
        for (week in 0 until (lead + length + 6) / 7) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CELL_GAP)) {
                for (column in 0 until 7) {
                    val number = week * 7 + column - lead + 1
                    if (number !in 1..length) {
                        Spacer(Modifier.weight(1f))
                        continue
                    }
                    val epochDay = first + number - 1
                    val sessions = days[epochDay]
                    DayCell(
                        number = number,
                        monthName = monthName,
                        sessions = sessions.size,
                        // The day's first workout colours it; sets logged outside one are amber.
                        fill = sessions.firstOrNull()?.let { session -> session.focus?.let { colors.focus(it).first } ?: colors.amber },
                        isToday = epochDay == today,
                        ahead = epochDay > today,
                        picked = epochDay == picked,
                        onPick = { onPick(epochDay) },
                        modifier = Modifier.weight(1f).testTag("fitness_history_day_$number"),
                    )
                }
            }
        }
    }
}

/**
 * A day of the month. One something was trained on ([sessions] of them) is filled with its
 * workout's colour ([fill]) and can be tapped; with more than one session it carries a dot for
 * each, up to three, which is all a square has room for (a screen reader is told the true count).
 * Today is ringed, and the day the list is narrowed to is ringed heavier.
 */
@Composable
private fun DayCell(number: Int, monthName: String, sessions: Int, fill: Color?, isToday: Boolean, ahead: Boolean, picked: Boolean, onPick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = FitnessTheme.colors
    val shape = RoundedCornerShape(12.dp)
    val trained = sessions > 0
    val day = if (trained) {
        pluralStringResource(Res.plurals.fitness_history_day_sessions, sessions, monthName, number, sessions)
    } else {
        stringResource(Res.string.fitness_history_day_none, monthName, number)
    }
    // The ring round today is all a sighted eye gets; a screen reader is told in words.
    val described = if (isToday) stringResource(Res.string.fitness_history_day_today, day) else day
    Box(
        modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(fill ?: if (ahead) Color.Transparent else colors.surfaceRaised)
            .then(
                when {
                    picked -> Modifier.border(2.5.dp, Color.White, shape)
                    isToday -> Modifier.border(1.5.dp, if (trained) Color.White else colors.textMuted, shape)
                    else -> Modifier
                },
            )
            .then(if (trained) Modifier.clickable(role = Role.Button, onClick = onPick) else Modifier)
            .clearAndSetSemantics {
                contentDescription = described
                if (trained) selected = picked
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            number.toString(),
            style = if (trained) FitnessTheme.type.bodyStrong else FitnessTheme.type.label,
            // Dark on the bright fill of a trained day: every workout's first colour is a light one.
            color = when {
                trained -> colors.background
                ahead -> colors.textFaint.copy(alpha = 0.3f)
                isToday -> colors.text
                else -> colors.textFaint
            },
            maxLines = 1,
        )
        if (sessions > 1) {
            Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 5.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(sessions.coerceAtMost(3)) { Box(Modifier.size(4.dp).clip(CircleShape).background(colors.background)) }
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(5.dp))
        Text(label, style = FitnessTheme.type.micro, color = FitnessTheme.colors.textFaint, maxLines = 1)
    }
}

/** The colours a session is drawn in: its workout's, or amber for sets logged outside one. */
@Composable
private fun sessionTints(session: WorkoutSession): Pair<Color, Color> {
    val colors = FitnessTheme.colors
    return session.focus?.let { colors.focus(it) } ?: (colors.amber to colors.ember)
}

@Composable
private fun sessionName(session: WorkoutSession): String = session.focus?.let { stringResource(it.label) } ?: stringResource(Res.string.fitness_history_loose)

/** A session in the list: what it was and when, how long it ran, and what it came to in sets, exercises, pounds and records. */
@Composable
private fun SessionCard(session: WorkoutSession, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val colors = FitnessTheme.colors
    val type = FitnessTheme.type
    val tints = sessionTints(session)
    val name = sessionName(session)
    val day = FitnessFormat.day(session.epochDay).resolve()
    val sets = pluralStringResource(Res.plurals.fitness_history_sets, session.setCount, session.setCount)
    val exercises = pluralStringResource(Res.plurals.fitness_history_exercises, session.lifts.size, session.lifts.size)
    Row(
        modifier
            .fillMaxWidth()
            .clip(FitnessCardShape)
            .background(colors.surface)
            .border(1.dp, colors.hairline, FitnessCardShape)
            .clickable(role = Role.Button, onClickLabel = stringResource(Res.string.fitness_history_open, name, day), onClick = onOpen)
            .testTag(session.workout?.let { "fitness_session_${it.id}" } ?: "fitness_session_day_${session.epochDay}")
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(5.dp).height(54.dp).clip(CircleShape).background(Brush.verticalGradient(listOf(tints.first, tints.second))))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = type.headline, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(Res.string.fitness_history_when, day, FitnessFormat.timeOfDay(session.startedAtEpochSeconds, session.utcOffsetSeconds).resolve()),
                style = type.label,
                color = colors.textFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (session.pounds > 0.0) {
                    stringResource(Res.string.fitness_history_row_stats, sets, exercises, FitnessFormat.volume(session.pounds).resolve())
                } else {
                    stringResource(Res.string.fitness_history_row_stats_short, sets, exercises)
                },
                style = type.label,
                color = colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            val duration = session.durationSeconds
            when {
                session.inProgress -> Text(stringResource(Res.string.fitness_history_in_progress), style = type.label, color = colors.good, maxLines = 1)
                duration != null -> Text(FitnessFormat.duration(duration).resolve(), style = type.bodyStrong, color = colors.text, maxLines = 1)
            }
            if (session.recordCount > 0) {
                val described = pluralStringResource(Res.plurals.fitness_history_records, session.recordCount, session.recordCount)
                Row(Modifier.padding(top = 4.dp).clearAndSetSemantics { contentDescription = described }, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.EmojiEvents, contentDescription = null, tint = colors.gold, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(3.dp))
                    Text(session.recordCount.toString(), style = type.label, color = colors.gold, maxLines = 1)
                }
            }
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = colors.textFaint, modifier = Modifier.padding(start = 4.dp).size(22.dp))
    }
}

/**
 * A workout looked back on: when it was and what it came to, its heart if a sensor was on for
 * it ([heart]), and every set of it under its exercise, the records among them marked. The
 * button at the foot deletes it, sets and all, on a second tap: it is the only way anything
 * logged in a workout leaves the log in one go.
 */
@Composable
internal fun SessionScreen(
    session: WorkoutSession,
    state: FitnessUiState,
    padding: PaddingValues,
    onOpenExercise: (String) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    heart: HeartSummary? = null,
) {
    val colors = FitnessTheme.colors
    val type = FitnessTheme.type
    val focus = session.focus
    val today = (state.nowEpochSeconds + state.utcOffsetSeconds).floorDiv(SECONDS_PER_DAY)
    var deleting by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = FitnessGutter).testTag("fitness_session"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column {
            Text(
                focus?.let { stringResource(Res.string.fitness_workout_title, stringResource(it.label)) } ?: stringResource(Res.string.fitness_history_loose),
                style = type.hero,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(
                    Res.string.fitness_history_when,
                    // The year, once it is no longer plain which one.
                    FitnessFormat.day(session.epochDay, withYear = today - session.epochDay > 300).resolve(),
                    FitnessFormat.timeOfDay(session.startedAtEpochSeconds, session.utcOffsetSeconds).resolve(),
                ),
                style = type.body,
                color = colors.textMuted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        FitnessCard(Modifier.testTag("fitness_session_stats")) {
            val none = stringResource(Res.string.fitness_history_none)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat(session.durationSeconds?.let { FitnessFormat.duration(it).resolve() } ?: none, stringResource(Res.string.fitness_history_stat_time), Modifier.weight(1f))
                Stat(session.setCount.toString(), stringResource(Res.string.fitness_history_stat_sets), Modifier.weight(1f))
                Stat(session.repCount.toString(), stringResource(Res.string.fitness_history_stat_reps), Modifier.weight(1f))
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat(if (session.pounds > 0.0) FitnessFormat.volume(session.pounds).resolve() else none, stringResource(Res.string.fitness_history_stat_volume), Modifier.weight(1f))
                Stat(session.lifts.size.toString(), stringResource(Res.string.fitness_history_stat_exercises), Modifier.weight(1f))
                Stat(session.recordCount.toString(), stringResource(Res.string.fitness_history_stat_records), Modifier.weight(1f), color = if (session.recordCount > 0) colors.gold else colors.text)
            }
            if (session.lifts.any { it.exercise.loadKind == LoadKind.LEVEL }) {
                Text(stringResource(Res.string.fitness_session_volume_caption), style = type.label, color = colors.textFaint, modifier = Modifier.padding(top = 12.dp))
            }
        }

        if (heart != null && !heart.isEmpty) {
            FitnessCard(Modifier.testTag("fitness_session_heart"), title = stringResource(Res.string.fitness_session_heart)) { HeartSummaryBody(heart) }
        }

        FitnessCard(Modifier.testTag("fitness_session_lifts"), title = stringResource(Res.string.fitness_session_lifts)) {
            if (session.lifts.isEmpty()) Text(stringResource(Res.string.fitness_session_empty), style = type.body, color = colors.textMuted)
            session.lifts.forEachIndexed { index, lift ->
                if (index > 0) Spacer(Modifier.height(14.dp))
                // An exercise since put away has no page of its own to open.
                SessionLiftBlock(lift, state.phase.kind, onOpen = if (state.board(lift.exercise.id) != null) ({ onOpenExercise(lift.exercise.id) }) else null)
            }
        }

        GhostButton(
            stringResource(
                if (deleting) {
                    Res.string.fitness_session_delete_confirm
                } else if (session.workout == null) {
                    Res.string.fitness_session_delete_sets
                } else {
                    Res.string.fitness_session_delete
                },
            ),
            { if (deleting) onDelete() else deleting = true },
            Modifier.fillMaxWidth().testTag("fitness_session_delete"),
            icon = Icons.Filled.Delete,
            tint = colors.danger,
        )
    }
}

/** An exercise as it was done that day: its name, which opens its page, and its sets in the order they were logged. */
@Composable
private fun SessionLiftBlock(lift: SessionLift, phase: PhaseKind, onOpen: (() -> Unit)?, modifier: Modifier = Modifier) {
    val colors = FitnessTheme.colors
    val type = FitnessTheme.type
    val exercise = lift.exercise
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .then(if (onOpen != null) Modifier.clickable(role = Role.Button, onClickLabel = stringResource(Res.string.fitness_session_open_exercise, exercise.name), onClick = onOpen) else Modifier)
            .testTag("fitness_session_lift_${exercise.id}"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(exercise.name, style = type.bodyStrong, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (onOpen != null) Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = colors.textFaint, modifier = Modifier.size(20.dp))
        }
        lift.sets.forEachIndexed { index, set ->
            val line = FitnessFormat.set(exercise.loadKind, set.weight, set.reps).resolve()
            val record = lift.records[set.id]
            val recordName = record?.let { stringResource(FitnessFormat.record(it, phase)) }
            val tint = when (record?.scope) {
                null -> colors.textMuted
                RecordScope.ALL_TIME -> colors.gold
                RecordScope.PHASE -> colors.phase(phase).first
            }
            val described = if (recordName != null) stringResource(Res.string.fitness_logger_set_record, line, recordName) else line
            Row(Modifier.fillMaxWidth().padding(top = 6.dp).clearAndSetSemantics { contentDescription = described }, verticalAlignment = Alignment.CenterVertically) {
                Text((index + 1).toString(), style = type.label, color = colors.textFaint, modifier = Modifier.width(22.dp))
                Text(line, style = type.body, color = if (set.id == lift.top.id) colors.text else colors.textMuted, maxLines = 1)
                if (recordName != null) {
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Filled.EmojiEvents, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(recordName, style = type.label, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
