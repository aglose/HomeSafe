package com.meticulouscreations.homesafe.atlas.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.EventRepeat
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timelapse
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.atlas.AtlasUiState
import com.meticulouscreations.homesafe.atlas.domain.Commute
import com.meticulouscreations.homesafe.atlas.domain.CommuteLeg
import com.meticulouscreations.homesafe.atlas.domain.Insight
import com.meticulouscreations.homesafe.atlas.domain.Records
import com.meticulouscreations.homesafe.atlas.domain.Routine
import com.meticulouscreations.homesafe.atlas.domain.TravelMode
import com.meticulouscreations.homesafe.atlas.domain.YearStat
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.atlas_commute_leave
import homesafe.shared.generated.resources.atlas_commute_minutes
import homesafe.shared.generated.resources.atlas_commute_title
import homesafe.shared.generated.resources.atlas_commute_to_home
import homesafe.shared.generated.resources.atlas_commute_to_work
import homesafe.shared.generated.resources.atlas_commute_trend
import homesafe.shared.generated.resources.atlas_insight_active_less
import homesafe.shared.generated.resources.atlas_insight_active_more
import homesafe.shared.generated.resources.atlas_insight_better_departure
import homesafe.shared.generated.resources.atlas_insight_commute_cost
import homesafe.shared.generated.resources.atlas_insight_days_in
import homesafe.shared.generated.resources.atlas_insight_drifted
import homesafe.shared.generated.resources.atlas_insight_habit
import homesafe.shared.generated.resources.atlas_insight_home_share
import homesafe.shared.generated.resources.atlas_insight_late_weekday
import homesafe.shared.generated.resources.atlas_insight_new_places_fewer
import homesafe.shared.generated.resources.atlas_insight_new_places_more
import homesafe.shared.generated.resources.atlas_insight_range_narrower
import homesafe.shared.generated.resources.atlas_insight_range_wider
import homesafe.shared.generated.resources.atlas_insight_regularity
import homesafe.shared.generated.resources.atlas_insight_shape_explorer
import homesafe.shared.generated.resources.atlas_insight_shape_returner
import homesafe.shared.generated.resources.atlas_insight_short_drives
import homesafe.shared.generated.resources.atlas_insights_title
import homesafe.shared.generated.resources.atlas_modes_air
import homesafe.shared.generated.resources.atlas_modes_description
import homesafe.shared.generated.resources.atlas_modes_laps
import homesafe.shared.generated.resources.atlas_modes_title
import homesafe.shared.generated.resources.atlas_record_busiest
import homesafe.shared.generated.resources.atlas_record_busiest_value
import homesafe.shared.generated.resources.atlas_record_farthest
import homesafe.shared.generated.resources.atlas_record_home_streak
import homesafe.shared.generated.resources.atlas_record_home_streak_value
import homesafe.shared.generated.resources.atlas_record_longest_day
import homesafe.shared.generated.resources.atlas_records_title
import homesafe.shared.generated.resources.atlas_role_elsewhere
import homesafe.shared.generated.resources.atlas_role_home
import homesafe.shared.generated.resources.atlas_role_moving
import homesafe.shared.generated.resources.atlas_role_work
import homesafe.shared.generated.resources.atlas_routine_description
import homesafe.shared.generated.resources.atlas_routine_subtitle
import homesafe.shared.generated.resources.atlas_routine_title
import homesafe.shared.generated.resources.atlas_years_new_places
import homesafe.shared.generated.resources.atlas_years_range
import homesafe.shared.generated.resources.atlas_years_title
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

private const val EARTH_METERS = 40_075_017.0

/**
 * What the record adds up to: the things it shows that its owner may not have noticed, the usual
 * week, the commute, how the miles were covered, each year against the others, and the records.
 */
@Composable
internal fun PatternsScreen(state: AtlasUiState, padding: PaddingValues, onOpenPlace: (String) -> Unit, onOpenDay: (Int) -> Unit, modifier: Modifier = Modifier) {
    val analysis = state.analysis
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = AtlasGutter).testTag("atlas_patterns"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (analysis.insights.isNotEmpty()) {
            AtlasCard(title = stringResource(Res.string.atlas_insights_title)) {
                analysis.insights.forEachIndexed { index, insight ->
                    if (index > 0) Spacer(Modifier.height(14.dp))
                    InsightRow(insight, state, onOpenPlace)
                }
            }
        }
        if (analysis.routine.slots.any { it.known }) RoutineCard(analysis.routine)
        analysis.commute?.let { CommuteCard(it) }
        if (analysis.years.isNotEmpty()) {
            ModesCard(analysis.years, analysis.records)
            YearsCard(analysis.years)
        }
        RecordsCard(analysis.records, state, onOpenDay)
    }
}

@Composable
private fun InsightRow(insight: Insight, state: AtlasUiState, onOpenPlace: (String) -> Unit) {
    val colors = AtlasTheme.colors
    val (icon, tint) = when (insight) {
        is Insight.HomeShare, is Insight.DaysIn -> Icons.Filled.Home to colors.home
        is Insight.CommuteCost, is Insight.LateWeekday, is Insight.BetterDeparture -> Icons.Filled.Schedule to colors.work
        is Insight.Drifted -> Icons.Filled.History to colors.air
        is Insight.Habit -> Icons.Filled.EventRepeat to colors.place
        is Insight.NewPlaces, is Insight.Shape, is Insight.Range -> Icons.Filled.Explore to colors.trail
        is Insight.Regularity -> Icons.Filled.Timelapse to colors.place
        is Insight.ShortDrives, is Insight.ActiveDistance -> Icons.AutoMirrored.Filled.DirectionsWalk to colors.good
    }
    val place = (insight as? Insight.Drifted)?.place ?: (insight as? Insight.Habit)?.place
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).then(if (place != null) Modifier.clickable { onOpenPlace(place.key) } else Modifier),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(tint.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(insightText(insight, state), style = AtlasTheme.type.body, color = colors.text, modifier = Modifier.weight(1f).padding(top = 6.dp))
    }
}

/** An insight as a sentence. Each is one whole string, chosen between rather than built up. */
@Composable
internal fun insightText(insight: Insight, state: AtlasUiState): String = when (insight) {
    is Insight.HomeShare -> stringResource(Res.string.atlas_insight_home_share, insight.percent, ((insight.hoursPerDay * 10).roundToInt() / 10.0).toString())

    is Insight.Regularity -> stringResource(Res.string.atlas_insight_regularity, insight.percent)

    is Insight.CommuteCost -> stringResource(Res.string.atlas_insight_commute_cost, insight.hoursPerYear, insight.minutesEachWay)

    is Insight.LateWeekday -> stringResource(Res.string.atlas_insight_late_weekday, stringResource(AtlasFormat.weekdayLong(insight.weekday)), insight.minutesLater)

    is Insight.BetterDeparture -> stringResource(Res.string.atlas_insight_better_departure, clockText(insight.departMinute), insight.minutesSaved)

    is Insight.Drifted -> stringResource(Res.string.atlas_insight_drifted, placeTitle(insight.place, state.history), insight.visits, insight.gapDays, agoText(insight.daysSince))

    is Insight.Habit -> stringResource(Res.string.atlas_insight_habit, placeTitle(insight.place, state.history), stringResource(AtlasFormat.weekdayLong(insight.weekday)), insight.visits, clockText(insight.arrivalMinute))

    is Insight.NewPlaces -> stringResource(
        if (insight.thisYear >= insight.lastYear) Res.string.atlas_insight_new_places_more else Res.string.atlas_insight_new_places_fewer,
        insight.thisYear,
        insight.year,
        insight.lastYear,
    )

    is Insight.Shape -> stringResource(if (insight.returner) Res.string.atlas_insight_shape_returner else Res.string.atlas_insight_shape_explorer, insight.topTwoShare)

    is Insight.Range -> stringResource(
        if (insight.km >= insight.lastYearKm) Res.string.atlas_insight_range_wider else Res.string.atlas_insight_range_narrower,
        insight.year,
        distanceText(insight.km * 1000.0),
        distanceText(insight.lastYearKm * 1000.0),
    )

    is Insight.ShortDrives -> stringResource(Res.string.atlas_insight_short_drives, insight.count, insight.year, distanceText(insight.km * 1000.0))

    is Insight.ActiveDistance -> stringResource(
        if (insight.km >= insight.lastYearKm) Res.string.atlas_insight_active_more else Res.string.atlas_insight_active_less,
        distanceText(insight.km * 1000.0),
        insight.year,
        distanceText(insight.lastYearKm * 1000.0),
    )

    is Insight.DaysIn -> pluralStringResource(Res.plurals.atlas_insight_days_in, insight.count, insight.count, insight.year)
}

@Composable
private fun RoutineCard(routine: Routine) {
    val colors = AtlasTheme.colors
    AtlasCard(title = stringResource(Res.string.atlas_routine_title)) {
        Text(pluralStringResource(Res.plurals.atlas_routine_subtitle, routine.weeks, routine.weeks), style = AtlasTheme.type.label, color = colors.textFaint)
        Spacer(Modifier.height(12.dp))
        WeekGrid(stringResource(Res.string.atlas_routine_description)) { weekday, hour ->
            val slot = routine.slot(weekday, hour)
            if (!slot.known) {
                colors.surfaceRaised
            } else {
                // The colour of whatever the hour is mostly spent on, firmer the more of it that is.
                val (share, tint) = listOf(slot.home to colors.home, slot.work to colors.work, slot.other to colors.place, slot.moving to colors.trail).maxBy { it.first }
                lerp(colors.surfaceRaised, tint, 0.25f + 0.75f * share)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Legend(colors.home, stringResource(Res.string.atlas_role_home))
            Legend(colors.work, stringResource(Res.string.atlas_role_work))
            Legend(colors.place, stringResource(Res.string.atlas_role_elsewhere))
            Legend(colors.trail, stringResource(Res.string.atlas_role_moving))
        }
    }
}

@Composable
private fun Legend(tint: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(RoundedCornerShape(3.dp)).background(tint))
        Spacer(Modifier.width(6.dp))
        Text(label, style = AtlasTheme.type.label, color = AtlasTheme.colors.textMuted, maxLines = 1)
    }
}

@Composable
private fun CommuteCard(commute: Commute) {
    val colors = AtlasTheme.colors
    AtlasCard(title = stringResource(Res.string.atlas_commute_title)) {
        Row(Modifier.fillMaxWidth()) {
            commute.toWork?.let { CommuteLegBlock(stringResource(Res.string.atlas_commute_to_work), it, Modifier.weight(1f)) }
            commute.toHome?.let { CommuteLegBlock(stringResource(Res.string.atlas_commute_to_home), it, Modifier.weight(1f)) }
        }
        val byWeekday = commute.toWork?.byWeekday.orEmpty()
        if (byWeekday.size >= 3) {
            Spacer(Modifier.height(16.dp))
            val longest = byWeekday.maxOf { it.minutes }.coerceAtLeast(1)
            Row(Modifier.fillMaxWidth().height(92.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                byWeekday.forEach { day ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(Res.string.atlas_commute_minutes, day.minutes), style = AtlasTheme.type.micro, color = colors.textMuted, maxLines = 1)
                        Box(Modifier.fillMaxWidth().height((52f * day.minutes / longest).dp).clip(RoundedCornerShape(8.dp)).background(if (day.minutes == longest) colors.ember else colors.work))
                        Text(stringResource(AtlasFormat.weekday(day.weekday)), style = AtlasTheme.type.micro, color = colors.textFaint, maxLines = 1)
                    }
                }
            }
        }
        if (commute.trend.size >= 4) {
            Spacer(Modifier.height(16.dp))
            Text(stringResource(Res.string.atlas_commute_trend), style = AtlasTheme.type.label, color = colors.textFaint)
            Spacer(Modifier.height(8.dp))
            val description = stringResource(Res.string.atlas_commute_trend)
            val low = commute.trend.minOf { it.second }
            val high = commute.trend.maxOf { it.second }.coerceAtLeast(low + 1)
            Canvas(Modifier.fillMaxWidth().height(56.dp).semantics { contentDescription = description }) {
                val line = Path()
                commute.trend.forEachIndexed { i, (_, minutes) ->
                    val x = size.width * i / (commute.trend.size - 1)
                    val y = size.height * (1f - (minutes - low).toFloat() / (high - low)) * 0.84f + size.height * 0.08f
                    if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
                }
                drawPath(line, colors.work, alpha = 0.3f, style = Stroke(6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(line, colors.work, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(monthText(commute.trend.first().first), style = AtlasTheme.type.micro, color = colors.textFaint)
                Text(monthText(commute.trend.last().first), style = AtlasTheme.type.micro, color = colors.textFaint)
            }
        }
    }
}

@Composable
private fun CommuteLegBlock(label: String, leg: CommuteLeg, modifier: Modifier = Modifier) {
    val colors = AtlasTheme.colors
    Column(modifier) {
        Text(label, style = AtlasTheme.type.label, color = colors.textFaint)
        Text(stringResource(Res.string.atlas_commute_minutes, leg.minutes), style = AtlasTheme.type.hero, color = colors.text, maxLines = 1)
        Text(stringResource(Res.string.atlas_commute_leave, clockText(leg.departMinute)), style = AtlasTheme.type.label, color = colors.textMuted, maxLines = 1)
    }
}

// The modes worth a colour of their own in the yearly bars, in the order they are stacked.
private val STACKED = listOf(TravelMode.WALK, TravelMode.RUN, TravelMode.CYCLE, TravelMode.DRIVE, TravelMode.BUS, TravelMode.TRAIN, TravelMode.SUBWAY, TravelMode.FERRY, TravelMode.OTHER)

@Composable
private fun ModesCard(years: List<YearStat>, records: Records) {
    val colors = AtlasTheme.colors
    AtlasCard(title = stringResource(Res.string.atlas_modes_title)) {
        Row(Modifier.fillMaxWidth()) {
            AtlasStat(distanceText(records.groundMeters), stringResource(Res.string.atlas_modes_laps, (((records.groundMeters + records.airMeters) / EARTH_METERS * 10).roundToInt() / 10.0).toString()), Modifier.weight(1f), tint = colors.trail)
            AtlasStat(distanceText(records.airMeters), stringResource(Res.string.atlas_modes_air), Modifier.weight(1f), tint = colors.air)
        }
        Spacer(Modifier.height(16.dp))
        val most = years.maxOf { it.groundMeters }.coerceAtLeast(1.0)
        val description = stringResource(Res.string.atlas_modes_description)
        years.forEach { year ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(year.year.toString(), style = AtlasTheme.type.label, color = colors.textFaint, modifier = Modifier.width(44.dp))
                Canvas(Modifier.weight(1f).height(14.dp).semantics { contentDescription = description }) {
                    var x = 0f
                    STACKED.forEach { mode ->
                        // Everything not picked out above is counted with "other".
                        val meters = if (mode == TravelMode.OTHER) {
                            year.modes.entries.sumOf { (each, total) -> if (each !in STACKED.dropLast(1) && each != TravelMode.FLY) total.meters else 0.0 }
                        } else {
                            year.modes[mode]?.meters ?: 0.0
                        }
                        val width = (meters / most * size.width).toFloat()
                        if (width > 0.5f) {
                            drawRoundRect(colors.mode(mode), Offset(x, 0f), Size(width, size.height), androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
                            x += width
                        }
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(distanceText(year.groundMeters), style = AtlasTheme.type.label, color = colors.textMuted, maxLines = 1)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Legend(colors.mode(TravelMode.DRIVE), stringResource(TravelMode.DRIVE.label))
            Legend(colors.mode(TravelMode.WALK), stringResource(TravelMode.WALK.label))
            Legend(colors.mode(TravelMode.CYCLE), stringResource(TravelMode.CYCLE.label))
            Legend(colors.mode(TravelMode.TRAIN), stringResource(TravelMode.TRAIN.label))
        }
    }
}

@Composable
private fun YearsCard(years: List<YearStat>) {
    val colors = AtlasTheme.colors
    AtlasCard(title = stringResource(Res.string.atlas_years_title)) {
        years.asReversed().forEachIndexed { index, year ->
            if (index > 0) Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(year.year.toString(), style = AtlasTheme.type.headline, color = colors.text, modifier = Modifier.width(56.dp))
                Column(Modifier.weight(1f)) {
                    // Where the year's time went: home, work, elsewhere, on the move.
                    Row(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape)) {
                        listOf(year.homeShare to colors.home, year.workShare to colors.work, year.otherShare to colors.place, year.movingShare to colors.trail).forEach { (share, tint) ->
                            if (share > 0.001f) Box(Modifier.weight(share).height(10.dp).background(tint))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(pluralStringResource(Res.plurals.atlas_years_new_places, year.newPlaces, year.newPlaces), style = AtlasTheme.type.label, color = colors.textMuted, maxLines = 1)
                        Text(stringResource(Res.string.atlas_years_range, distanceText(year.rangeKm * 1000)), style = AtlasTheme.type.label, color = colors.textFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordsCard(records: Records, state: AtlasUiState, onOpenDay: (Int) -> Unit) {
    AtlasCard(title = stringResource(Res.string.atlas_records_title)) {
        records.longestDay?.let { RecordRow(Icons.AutoMirrored.Filled.TrendingUp, stringResource(Res.string.atlas_record_longest_day), distanceText(records.longestDayMeters), it, onOpenDay) }
        records.farthestDay?.let { day ->
            val where = records.farthestPlace?.let { place -> localityText(state.history.locality(place)) ?: placeTitle(place, state.history) }
            RecordRow(Icons.Filled.Explore, stringResource(Res.string.atlas_record_farthest), where ?: distanceText(records.farthestKm * 1000), day, onOpenDay)
        }
        records.busiestDay?.let { RecordRow(Icons.Filled.AutoAwesome, stringResource(Res.string.atlas_record_busiest), pluralStringResource(Res.plurals.atlas_record_busiest_value, records.busiestDayPlaces, records.busiestDayPlaces), it, onOpenDay) }
        records.homeStreakStart?.let { RecordRow(Icons.Filled.Home, stringResource(Res.string.atlas_record_home_streak), pluralStringResource(Res.plurals.atlas_record_home_streak_value, records.homeStreakDays, records.homeStreakDays), it, onOpenDay) }
    }
}

@Composable
private fun RecordRow(icon: ImageVector, label: String, value: String, day: Int, onOpenDay: (Int) -> Unit) {
    val colors = AtlasTheme.colors
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onOpenDay(day) }.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = colors.trail, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = AtlasTheme.type.label, color = colors.textFaint, maxLines = 1)
            Text(value, style = AtlasTheme.type.bodyStrong, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(dateText(day), style = AtlasTheme.type.label, color = colors.textMuted, maxLines = 1)
    }
}
