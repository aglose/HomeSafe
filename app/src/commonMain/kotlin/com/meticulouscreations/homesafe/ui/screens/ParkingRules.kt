package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.domain.model.ParkingAlert
import com.meticulouscreations.homesafe.domain.model.ParkingCurb
import com.meticulouscreations.homesafe.domain.model.ParkingRule
import com.meticulouscreations.homesafe.domain.model.ParkingStreet
import com.meticulouscreations.homesafe.text.resolve
import com.meticulouscreations.homesafe.weather.domain.WeatherFormat
import com.meticulouscreations.homesafe.weather.ui.radar.MapLine
import com.meticulouscreations.homesafe.weather.ui.radar.MapPin
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.common_list_separator
import homesafe.shared.generated.resources.household_cars_select
import homesafe.shared.generated.resources.parking_map_limit_hours
import homesafe.shared.generated.resources.parking_map_limit_minutes
import homesafe.shared.generated.resources.parking_rules_all_day
import homesafe.shared.generated.resources.parking_rules_day_date
import homesafe.shared.generated.resources.parking_rules_days_in_weeks
import homesafe.shared.generated.resources.parking_rules_every_day
import homesafe.shared.generated.resources.parking_rules_header
import homesafe.shared.generated.resources.parking_rules_label_now
import homesafe.shared.generated.resources.parking_rules_lifted
import homesafe.shared.generated.resources.parking_rules_lifted_short
import homesafe.shared.generated.resources.parking_rules_line
import homesafe.shared.generated.resources.parking_rules_next
import homesafe.shared.generated.resources.parking_rules_no_parking
import homesafe.shared.generated.resources.parking_rules_none_soon
import homesafe.shared.generated.resources.parking_rules_now
import homesafe.shared.generated.resources.parking_rules_range
import homesafe.shared.generated.resources.parking_rules_schedule
import homesafe.shared.generated.resources.parking_rules_schedule_months
import homesafe.shared.generated.resources.parking_rules_side_east
import homesafe.shared.generated.resources.parking_rules_side_north
import homesafe.shared.generated.resources.parking_rules_side_south
import homesafe.shared.generated.resources.parking_rules_side_west
import homesafe.shared.generated.resources.parking_rules_today
import homesafe.shared.generated.resources.parking_rules_tomorrow
import homesafe.shared.generated.resources.parking_rules_week_1
import homesafe.shared.generated.resources.parking_rules_week_2
import homesafe.shared.generated.resources.parking_rules_week_3
import homesafe.shared.generated.resources.parking_rules_week_4
import homesafe.shared.generated.resources.parking_rules_week_5
import homesafe.shared.generated.resources.parking_rules_week_last
import homesafe.shared.generated.resources.parking_rules_weeks_separator
import homesafe.shared.generated.resources.weather_month_apr
import homesafe.shared.generated.resources.weather_month_aug
import homesafe.shared.generated.resources.weather_month_dec
import homesafe.shared.generated.resources.weather_month_feb
import homesafe.shared.generated.resources.weather_month_jan
import homesafe.shared.generated.resources.weather_month_jul
import homesafe.shared.generated.resources.weather_month_jun
import homesafe.shared.generated.resources.weather_month_mar
import homesafe.shared.generated.resources.weather_month_may
import homesafe.shared.generated.resources.weather_month_nov
import homesafe.shared.generated.resources.weather_month_oct
import homesafe.shared.generated.resources.weather_month_sep
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot

/*
 * The cars' map's parking rules: each watched street's curbs drawn along the street in a colour
 * for how soon their signs next hold, a label on each saying which side it is and when, and under
 * the cars a card per curb with its signs spelt out, so the household can check what the server
 * wrote down against the signs on the street (docs/parking.md).
 */

/** One curb of one street, as the map and the list show it; [key] is its label's pin on the map. */
internal data class CurbSigns(val key: String, val street: ParkingStreet, val curb: ParkingCurb)

internal fun curbSigns(streets: List<ParkingStreet>): List<CurbSigns> = streets.flatMapIndexed { s, street ->
    street.curbs.mapIndexed { c, curb -> CurbSigns("curb:$s:$c", street, curb) }
}

/**
 * The sign on [curb] that holds soonest (or is on now), leaving out those a permit the household
 * holds lifts, and a window that has ended since the relay last answered: its next one is the
 * relay's to say, on the next read.
 */
internal fun soonest(curb: ParkingCurb, nowEpochSeconds: Long): ParkingRule? =
    curb.rules.filter { !it.lifted && !ended(it, nowEpochSeconds) }.minByOrNull { it.nextFromEpochSeconds!! }

/** Whether [rule] has no window to show at [nowEpochSeconds]: none within weeks, or the one the relay gave is over. */
private fun ended(rule: ParkingRule, nowEpochSeconds: Long): Boolean = rule.nextFromEpochSeconds == null || (rule.nextUntilEpochSeconds ?: Long.MAX_VALUE) <= nowEpochSeconds

/** How soon a curb's signs next hold, for its colour: on now or within a day, within the week, later, or never (lifted, or none for weeks). */
private enum class Urgency { SOON, WEEK, LATER, NONE }

private fun urgency(curb: ParkingCurb, nowEpochSeconds: Long): Urgency {
    val from = soonest(curb, nowEpochSeconds)?.nextFromEpochSeconds ?: return Urgency.NONE
    return when {
        from - nowEpochSeconds <= 86_400 -> Urgency.SOON
        from - nowEpochSeconds <= 7 * 86_400 -> Urgency.WEEK
        else -> Urgency.LATER
    }
}

@Composable
@ReadOnlyComposable
private fun urgencyColor(urgency: Urgency): Color = when (urgency) {
    Urgency.SOON -> MaterialTheme.colorScheme.error
    Urgency.WEEK -> MaterialTheme.colorScheme.tertiary
    Urgency.LATER -> MaterialTheme.colorScheme.primary
    Urgency.NONE -> MaterialTheme.colorScheme.outline
}

/** A band along each curb, a little in from it, in its [urgencyColor]: the one in focus wider. */
@Composable
@ReadOnlyComposable
internal fun curbLines(curbs: List<CurbSigns>, nowEpochSeconds: Long, focus: String?): List<MapLine> = curbs.map { (key, street, curb) ->
    val inFromMiddle = street.widthMetres / 2 - CURB_INSET_METRES
    MapLine(
        points = street.line.map { it.latitude to it.longitude },
        color = urgencyColor(urgency(curb, nowEpochSeconds)),
        width = if (key == focus) 7.dp else 4.dp,
        offsetMetres = if (curb.left) inFromMiddle else -inFromMiddle,
        minOffset = 4.dp,
    )
}

/**
 * Where each curb's label goes: a third of the way along the street for the left curb and two
 * thirds for the right, so a street's two labels don't sit on each other, and out past its curb.
 */
internal fun curbPins(curbs: List<CurbSigns>): List<MapPin> = curbs.map { (key, street, curb) ->
    val (latitude, longitude) = alongLine(street.line, if (curb.left) 1.0 / 3 else 2.0 / 3, (street.widthMetres / 2 + LABEL_OUT_METRES) * if (curb.left) 1 else -1)
    MapPin(key, latitude, longitude)
}

/** The point [fraction] of the way along [line], moved [leftMetres] to its left (negative to the right). */
private fun alongLine(line: List<ParkingStreet.Point>, fraction: Double, leftMetres: Double): Pair<Double, Double> {
    // Flat metres east and north of the line's start: a few blocks are flat enough.
    val origin = line.first()
    val metresPerLongitude = METRES_PER_DEGREE * cos(origin.latitude * PI / 180)
    val xy = line.map { (it.longitude - origin.longitude) * metresPerLongitude to (it.latitude - origin.latitude) * METRES_PER_DEGREE }
    val lengths = xy.zipWithNext { a, b -> hypot(b.first - a.first, b.second - a.second) }
    var left = lengths.sum() * fraction
    for ((i, length) in lengths.withIndex()) {
        if (left <= length || i == lengths.lastIndex) {
            val (a, b) = xy[i] to xy[i + 1]
            val t = if (length > 0) (left / length).coerceIn(0.0, 1.0) else 0.0
            val x = a.first + (b.first - a.first) * t
            val y = a.second + (b.second - a.second) * t
            // The left of (east, north) is (-north, east).
            val (nx, ny) = if (length > 0) -(b.second - a.second) / length to (b.first - a.first) / length else 0.0 to 0.0
            return origin.latitude + (y + ny * leftMetres) / METRES_PER_DEGREE to origin.longitude + (x + nx * leftMetres) / metresPerLongitude
        }
        left -= length
    }
    return origin.latitude to origin.longitude
}

/** A curb's label on the map: a dot in its colour, which side it is, and when its signs next hold. Tapping it flies the map to it. */
@Composable
internal fun CurbLabel(signs: CurbSigns, nowEpochSeconds: Long, selected: Boolean, onClick: () -> Unit) {
    val next = soonest(signs.curb, nowEpochSeconds)
    Box(Modifier.wrapContentSize(unbounded = true)) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
            shadowElevation = 2.dp,
            modifier = Modifier
                .widthIn(max = 148.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable(role = Role.Button, onClickLabel = stringResource(Res.string.household_cars_select), onClick = onClick)
                .semantics { this.selected = selected }
                .testTag("cars_curb_${signs.key}"),
        ) {
            Row(Modifier.padding(horizontal = 6.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(urgencyColor(urgency(signs.curb, nowEpochSeconds))))
                Column {
                    Text(curbSide(signs.curb.side) ?: signs.street.street, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                    val from = next?.nextFromEpochSeconds
                    val until = next?.nextUntilEpochSeconds
                    Text(
                        when {
                            next == null && signs.curb.rules.any { it.lifted } -> stringResource(Res.string.parking_rules_lifted_short)
                            from == null -> stringResource(Res.string.parking_rules_none_soon)
                            from <= nowEpochSeconds && until != null -> stringResource(Res.string.parking_rules_label_now, clockOf(until))
                            else -> parkingWhen(from, nowEpochSeconds)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

/** Under the cars: a card per curb, each sign on it spelt out with when it next holds. A tap flies the map to the curb. */
@Composable
internal fun CurbSignsList(curbs: List<CurbSigns>, nowEpochSeconds: Long, focus: String?, onSelect: (String) -> Unit) {
    Text(
        stringResource(Res.string.parking_rules_header),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 8.dp),
    )
    curbs.forEach { signs ->
        val selected = signs.key == focus
        val shape = RoundedCornerShape(16.dp)
        Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)
                .clickable(onClickLabel = stringResource(Res.string.household_cars_select)) { onSelect(signs.key) }
                .semantics { this.selected = selected }
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .testTag("cars_signs_${signs.key}"),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.padding(top = 6.dp).size(10.dp).clip(CircleShape).background(urgencyColor(urgency(signs.curb, nowEpochSeconds))))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(parkingPlace(signs.street.street, signs.curb.side), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                signs.curb.rules.forEach { rule ->
                    Text(ruleText(rule), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    val permit = rule.permit
                    if (rule.lifted && permit != null) {
                        Text(stringResource(Res.string.parking_rules_lifted, permit), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                    } else {
                        ruleNext(rule, nowEpochSeconds)?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = if (rule.on(nowEpochSeconds)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun curbSide(side: ParkingAlert.Side?): String? = when (side) {
    ParkingAlert.Side.NORTH -> stringResource(Res.string.parking_rules_side_north)
    ParkingAlert.Side.EAST -> stringResource(Res.string.parking_rules_side_east)
    ParkingAlert.Side.SOUTH -> stringResource(Res.string.parking_rules_side_south)
    ParkingAlert.Side.WEST -> stringResource(Res.string.parking_rules_side_west)
    null -> null
}

/** "Street sweeping · 1st & 3rd Thu of the month, 8:00 AM–11:00 AM", "2-hour parking · Mon–Fri, 8:00 AM–6:00 PM (Apr–Oct)". */
@Composable
internal fun ruleText(rule: ParkingRule): String {
    val note = rule.note
    val limit = rule.limitMinutes
    val what = when {
        note != null -> note
        limit != null && limit % 60 == 0 -> pluralStringResource(Res.plurals.parking_map_limit_hours, limit / 60, limit / 60)
        limit != null -> pluralStringResource(Res.plurals.parking_map_limit_minutes, limit, limit)
        else -> stringResource(Res.string.parking_rules_no_parking)
    }
    val days = if (rule.days.isEmpty() || rule.days.size == 7) {
        stringResource(Res.string.parking_rules_every_day)
    } else {
        runs(rule.days) { stringResource(weekdayName(it)) }
    }
    val inWeeks = if (rule.weeks.isEmpty()) {
        days
    } else {
        val weeks = rule.weeks.map { stringResource(WEEKS[it] ?: Res.string.parking_rules_week_last) }.joinToString(stringResource(Res.string.parking_rules_weeks_separator))
        stringResource(Res.string.parking_rules_days_in_weeks, weeks, days)
    }
    val hours = if (rule.startMinutes == 0 && rule.endMinutes >= 1440) {
        stringResource(Res.string.parking_rules_all_day)
    } else {
        stringResource(Res.string.parking_rules_range, clockOfMinutes(rule.startMinutes), clockOfMinutes(rule.endMinutes))
    }
    val schedule = if (rule.months.isEmpty() || rule.months.size == 12) {
        stringResource(Res.string.parking_rules_schedule, inWeeks, hours)
    } else {
        stringResource(Res.string.parking_rules_schedule_months, inWeeks, hours, runs(rule.months.map { it - 1 }) { stringResource(MONTHS[it]) })
    }
    return stringResource(Res.string.parking_rules_line, what, schedule)
}

/**
 * "Next: Thu, Nov 5, 8:00 AM–11:00 AM", "On now, until 11:00 AM", or that it doesn't hold for
 * weeks; null once the window the relay gave has ended, until it says when the next one is.
 */
@Composable
internal fun ruleNext(rule: ParkingRule, nowEpochSeconds: Long): String? {
    val from = rule.nextFromEpochSeconds
    val until = rule.nextUntilEpochSeconds
    return when {
        from == null || until == null -> stringResource(Res.string.parking_rules_none_soon)
        until <= nowEpochSeconds -> null
        from <= nowEpochSeconds -> stringResource(Res.string.parking_rules_now, clockOf(until))
        else -> stringResource(Res.string.parking_rules_next, dayOf(from, nowEpochSeconds), clockOf(from), clockOf(until))
    }
}

/** "today", "tomorrow", "Thu" later this week, "Thu, Nov 5" a week or more away. */
@Composable
private fun dayOf(epochSeconds: Long, nowEpochSeconds: Long): String {
    val offset = utcOffset(epochSeconds)
    val days = daysAway(epochSeconds, nowEpochSeconds)
    val weekday = stringResource(WeatherFormat.weekday(epochSeconds, offset))
    return when {
        days <= 0 -> stringResource(Res.string.parking_rules_today)
        days == 1 -> stringResource(Res.string.parking_rules_tomorrow)
        days < 7 -> weekday
        else -> stringResource(Res.string.parking_rules_day_date, weekday, WeatherFormat.date(epochSeconds, offset).resolve())
    }
}

@Composable
private fun clockOf(epochSeconds: Long): String = WeatherFormat.clock(epochSeconds, utcOffset(epochSeconds)).resolve()

/** A sign's own clock time, "8:00 AM": minutes after midnight, as the sign says, whatever the phone's zone. */
@Composable
private fun clockOfMinutes(minutes: Int): String = WeatherFormat.clock(minutes % 1440 * 60L, 0).resolve()

/** Sorted [items] in runs: three or more in a row as "Mon–Fri", the rest listed ("Tue, Thu"). */
@Composable
private fun runs(items: List<Int>, name: @Composable (Int) -> String): String {
    val groups = mutableListOf<IntRange>()
    for (item in items.sorted()) {
        val last = groups.lastOrNull()
        if (last != null && item == last.last + 1) groups[groups.lastIndex] = last.first..item else groups += item..item
    }
    val parts = groups.flatMap { run ->
        if (run.last - run.first >= 2) listOf(stringResource(Res.string.parking_rules_range, name(run.first), name(run.last))) else run.map { name(it) }
    }
    return parts.joinToString(stringResource(Res.string.common_list_separator))
}

/** Monday is 0, as the relay counts: 5 January 1970 was a Monday. */
private fun weekdayName(day: Int): StringResource = WeatherFormat.weekday((4L + day) * 86_400, 0)

private val WEEKS = mapOf(
    1 to Res.string.parking_rules_week_1,
    2 to Res.string.parking_rules_week_2,
    3 to Res.string.parking_rules_week_3,
    4 to Res.string.parking_rules_week_4,
    5 to Res.string.parking_rules_week_5,
)

private val MONTHS = listOf(
    Res.string.weather_month_jan,
    Res.string.weather_month_feb,
    Res.string.weather_month_mar,
    Res.string.weather_month_apr,
    Res.string.weather_month_may,
    Res.string.weather_month_jun,
    Res.string.weather_month_jul,
    Res.string.weather_month_aug,
    Res.string.weather_month_sep,
    Res.string.weather_month_oct,
    Res.string.weather_month_nov,
    Res.string.weather_month_dec,
)

/** How far in from the curb the band is drawn: down the middle of where the cars park. */
private const val CURB_INSET_METRES = 1.5

/** How far out past the curb a label sits, so it covers the sidewalk rather than the band. */
private const val LABEL_OUT_METRES = 12.0

private const val METRES_PER_DEGREE = 111_320.0
