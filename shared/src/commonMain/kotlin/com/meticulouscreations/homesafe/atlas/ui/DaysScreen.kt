package com.meticulouscreations.homesafe.atlas.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.atlas.AtlasUiState
import com.meticulouscreations.homesafe.atlas.domain.CivilDate
import com.meticulouscreations.homesafe.atlas.domain.DayStats
import com.meticulouscreations.homesafe.atlas.domain.weekdayOf
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.atlas_day_nothing
import homesafe.shared.generated.resources.atlas_day_summary
import homesafe.shared.generated.resources.atlas_days_day_state
import homesafe.shared.generated.resources.atlas_days_legend_less
import homesafe.shared.generated.resources.atlas_days_legend_more
import homesafe.shared.generated.resources.atlas_days_measure_distance
import homesafe.shared.generated.resources.atlas_days_measure_far
import homesafe.shared.generated.resources.atlas_days_measure_places
import homesafe.shared.generated.resources.atlas_days_on_this_day
import homesafe.shared.generated.resources.atlas_days_open_day
import homesafe.shared.generated.resources.atlas_days_year_grid
import homesafe.shared.generated.resources.atlas_days_years_ago
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** What the calendar's squares are coloured by. */
internal enum class DayMeasure(val label: StringResource) {
    DISTANCE(Res.string.atlas_days_measure_distance),
    PLACES(Res.string.atlas_days_measure_places),
    FAR(Res.string.atlas_days_measure_far),
    ;

    fun of(days: DayStats, index: Int): Float = when (this) {
        DISTANCE -> days.groundMeters[index] + days.airMeters[index]

        // One place is a day at home: nothing to light a square for.
        PLACES -> (days.places[index] - 1).coerceAtLeast(0).toFloat()

        FAR -> days.farthestKm[index]
    }
}

/**
 * Every day on record as one square, a year to a block, the newest first: a life at a glance.
 * The squares are lit by how far the day went, how many places it took in, or how far from home
 * it got; a tap on one opens that day. Above them, this date in other years.
 */
@Composable
internal fun DaysScreen(state: AtlasUiState, padding: PaddingValues, onOpenDay: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = AtlasTheme.colors
    val days = state.analysis.days
    var measure by rememberSaveable { mutableStateOf(DayMeasure.DISTANCE) }
    // A square is full when it is among the top twentieth of days; a single flight shouldn't dim every other day.
    val full = remember(days, measure) {
        val values = (0 until days.size).map { measure.of(days, it) }.filter { it > 0f }.sorted()
        if (values.isEmpty()) 1f else values[(values.size * 95) / 100].coerceAtLeast(0.001f)
    }
    val years = remember(state.history) { (CivilDate.of(state.history.firstDay).year..CivilDate.of(state.history.lastDay).year).reversed().toList() }
    val today = CivilDate.of(state.today)
    val anniversaries = remember(days, state.today) {
        years.filter { it < today.year }.mapNotNull { year ->
            // The 29th of February has only itself to look back on.
            if (today.month == 2 && today.dayOfMonth == 29 && CivilDate.daysIn(year, 2) < 29) return@mapNotNull null
            val day = CivilDate.dayOf(year, today.month, today.dayOfMonth)
            days.index(day)?.takeIf { days.trackedSeconds[it] > 0 }?.let { day }
        }
    }

    LazyColumn(modifier.fillMaxSize().testTag("atlas_days"), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (anniversaries.isNotEmpty()) {
            item(key = "on_this_day") {
                Column {
                    Text(
                        stringResource(Res.string.atlas_days_on_this_day).uppercase(),
                        style = AtlasTheme.type.micro,
                        color = colors.textFaint,
                        modifier = Modifier.padding(horizontal = AtlasGutter + 4.dp).semantics { heading() },
                    )
                    Spacer(Modifier.height(10.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = AtlasGutter), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(anniversaries, key = { it }) { day -> AnniversaryCard(state, day, onOpenDay) }
                    }
                }
            }
        }
        item(key = "measure") {
            Column(Modifier.fillMaxWidth().padding(horizontal = AtlasGutter)) {
                AtlasChips(DayMeasure.entries.map { it to stringResource(it.label) }, measure, { measure = it })
                Spacer(Modifier.height(10.dp))
                Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Res.string.atlas_days_legend_less), style = AtlasTheme.type.micro, color = colors.textFaint)
                    Row(Modifier.padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        listOf(0f, 0.25f, 0.55f, 0.8f, 1f).forEach { Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(colors.heat(it))) }
                    }
                    Text(stringResource(Res.string.atlas_days_legend_more), style = AtlasTheme.type.micro, color = colors.textFaint)
                }
            }
        }
        items(years, key = { it }) { year ->
            AtlasCard(Modifier.padding(horizontal = AtlasGutter)) {
                Text(year.toString(), style = AtlasTheme.type.headline, color = colors.text, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(10.dp))
                YearGrid(year, days, measure, full, state.today, onOpenDay)
            }
        }
    }
}

/** This date in an earlier year: where the day was mostly spent, and how far it went. */
@Composable
private fun AnniversaryCard(state: AtlasUiState, day: Int, onOpenDay: (Int) -> Unit) {
    val colors = AtlasTheme.colors
    val years = CivilDate.of(state.today).year - CivilDate.of(day).year
    val visits = state.history.visitsOn(day)
    // The place that says most about the day: the longest stay that wasn't home, or home on a day that was all home.
    val home = state.analysis.homeAt(day)?.home
    val main = visits.filter { it.placeKey != home?.key }.maxByOrNull { it.seconds } ?: visits.maxByOrNull { it.seconds }
    val index = state.analysis.days.index(day)
    Column(
        Modifier
            .width(168.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surface)
            .border(1.dp, colors.hairline, RoundedCornerShape(20.dp))
            .clickable { onOpenDay(day) }
            .padding(14.dp),
    ) {
        Text(pluralStringResource(Res.plurals.atlas_days_years_ago, years, years), style = AtlasTheme.type.micro, color = colors.trail)
        Spacer(Modifier.height(6.dp))
        Text(
            main?.let { placeTitle(state.history.place(it.placeKey).let { place -> if (place.key == home?.key) home else place }, state.history) }.orEmpty(),
            style = AtlasTheme.type.bodyStrong,
            color = colors.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(dateText(day, weekday = true), style = AtlasTheme.type.label, color = colors.textFaint, maxLines = 1)
        if (index != null) {
            Text(distanceText((state.analysis.days.groundMeters[index] + state.analysis.days.airMeters[index]).toDouble()), style = AtlasTheme.type.label, color = colors.textMuted)
        }
    }
}

/** One year: a column a week, Monday at the top, the months' initials above. */
@Composable
private fun YearGrid(year: Int, days: DayStats, measure: DayMeasure, full: Float, today: Int, onOpenDay: (Int) -> Unit) {
    val colors = AtlasTheme.colors
    val first = CivilDate.dayOf(year, 1, 1)
    val last = CivilDate.dayOf(year + 1, 1, 1) - 1
    // The column the year's first day sits in is 0; its row is its weekday.
    val lead = weekdayOf(first)
    val weeks = (lead + (last - first) + 7) / 7
    val open by rememberUpdatedState(onOpenDay)
    val description = stringResource(Res.string.atlas_days_year_grid, year)
    // A screen reader can't point at a square, so the grid is also something to step along: one
    // day is the chosen one (the latest on record to begin with), swiping moves it, and the
    // grid's own click opens it.
    val lastOpenable = minOf(last, today)
    var chosen by remember(year, days) {
        mutableStateOf((lastOpenable downTo first).firstOrNull { day -> days.index(day)?.let { days.trackedSeconds[it] > 0 } == true } ?: first)
    }
    var stepped by remember(year) { mutableStateOf(false) }
    val chosenIndex = days.index(chosen)?.takeIf { days.trackedSeconds[it] > 0 }
    val chosenSays = stringResource(
        Res.string.atlas_days_day_state,
        dateText(chosen, weekday = true),
        if (chosenIndex == null) {
            stringResource(Res.string.atlas_day_nothing)
        } else {
            pluralStringResource(Res.plurals.atlas_day_summary, days.places[chosenIndex], distanceText((days.groundMeters[chosenIndex] + days.airMeters[chosenIndex]).toDouble()), days.places[chosenIndex])
        },
    )
    val openLabel = stringResource(Res.string.atlas_days_open_day)
    val monthStyle = AtlasTheme.type.micro.copy(color = colors.textFaint)
    val measurer = rememberTextMeasurer(cacheSize = 12)
    val initials = (1..12).map { stringResource(AtlasFormat.month(it)).take(1) }
    Canvas(
        Modifier
            .fillMaxWidth()
            .aspectRatio(weeks / 8.3f)
            .keptPicture()
            .semantics {
                contentDescription = description
                if (lastOpenable >= first) {
                    stateDescription = chosenSays
                    progressBarRangeInfo = ProgressBarRangeInfo((chosen - first).toFloat(), 0f..(lastOpenable - first).toFloat(), (lastOpenable - first - 1).coerceAtLeast(0))
                    setProgress { to ->
                        chosen = first + to.roundToInt().coerceIn(0, lastOpenable - first)
                        stepped = true
                        true
                    }
                    onClick(openLabel) {
                        open(chosen)
                        true
                    }
                }
            }
            .testTag("atlas_year_$year")
            .pointerInput(year, weeks) {
                detectTapGestures { at ->
                    val cell = size.width / weeks.toFloat()
                    val top = size.height - cell * 7
                    val column = (at.x / cell).toInt()
                    val row = ((at.y - top) / cell).toInt()
                    val day = first - lead + column * 7 + row
                    if (row in 0..6 && day in first..minOf(last, today) && days.index(day) != null) open(day)
                }
            },
    ) {
        val cell = size.width / weeks
        val top = size.height - cell * 7
        val gap = cell * 0.14f
        val corner = CornerRadius(cell * 0.24f)
        for (month in 1..12) {
            val column = (lead + CivilDate.dayOf(year, month, 1) - first) / 7
            drawText(measurer, initials[month - 1], topLeft = Offset(column * cell + gap, 0f), style = monthStyle)
        }
        for (day in first..last) {
            val position = lead + day - first
            val topLeft = Offset((position / 7) * cell + gap / 2, top + (position % 7) * cell + gap / 2)
            val size = Size(cell - gap, cell - gap)
            val index = days.index(day)
            when {
                day > today || index == null || days.trackedSeconds[index] <= 0 -> drawRoundRect(colors.hairline, topLeft, size, corner, style = Stroke(1f))
                else -> drawRoundRect(colors.heat(sqrt(measure.of(days, index) / full).coerceIn(0f, 1f)), topLeft, size, corner)
            }
            if (day == today) drawRoundRect(colors.text, topLeft, size, corner, style = Stroke(cell * 0.1f))
            // Once it has been stepped to, the chosen day is shown as well as said.
            if (stepped && day == chosen) drawRoundRect(colors.place, topLeft, size, corner, style = Stroke(cell * 0.16f))
        }
    }
}
