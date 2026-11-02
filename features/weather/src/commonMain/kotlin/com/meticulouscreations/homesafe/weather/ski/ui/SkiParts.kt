package com.meticulouscreations.homesafe.weather.ski.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.resolve
import com.meticulouscreations.homesafe.ui.handCursor
import com.meticulouscreations.homesafe.weather.domain.WeatherFormat
import com.meticulouscreations.homesafe.weather.ski.domain.ChainLevel
import com.meticulouscreations.homesafe.weather.ski.domain.SkiAlerts
import com.meticulouscreations.homesafe.weather.ski.domain.SkiEntry
import com.meticulouscreations.homesafe.weather.ski.domain.SkiForecast
import com.meticulouscreations.homesafe.weather.ski.domain.SkiNote
import com.meticulouscreations.homesafe.weather.ski.domain.SkiOpenState
import com.meticulouscreations.homesafe.weather.ski.domain.SkiOrigin
import com.meticulouscreations.homesafe.weather.ski.domain.SkiPass
import com.meticulouscreations.homesafe.weather.ski.domain.SkiPriceTier
import com.meticulouscreations.homesafe.weather.ski.domain.SkiReport
import com.meticulouscreations.homesafe.weather.ski.domain.SkiResortRef
import com.meticulouscreations.homesafe.weather.ski.domain.SkiStripDay
import com.meticulouscreations.homesafe.weather.ui.WeatherTheme
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.ski_chain_r1
import homesafe.shared.generated.resources.ski_chain_r2
import homesafe.shared.generated.resources.ski_chain_r3
import homesafe.shared.generated.resources.ski_day_snow_description
import homesafe.shared.generated.resources.ski_drive_hours
import homesafe.shared.generated.resources.ski_drive_minutes
import homesafe.shared.generated.resources.ski_pass_epic
import homesafe.shared.generated.resources.ski_pass_ikon
import homesafe.shared.generated.resources.ski_pass_indy
import homesafe.shared.generated.resources.ski_pass_mountain_collective
import homesafe.shared.generated.resources.ski_pass_powder_alliance
import homesafe.shared.generated.resources.ski_state_closed
import homesafe.shared.generated.resources.ski_state_limited
import homesafe.shared.generated.resources.ski_state_no_recent_report
import homesafe.shared.generated.resources.ski_state_not_operating
import homesafe.shared.generated.resources.ski_state_open
import homesafe.shared.generated.resources.ski_state_opens
import homesafe.shared.generated.resources.ski_tier_any_day
import homesafe.shared.generated.resources.ski_tier_non_peak
import homesafe.shared.generated.resources.ski_tier_peak
import homesafe.shared.generated.resources.ski_tier_weekday
import homesafe.shared.generated.resources.ski_tier_weekend
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Everything the Ski tab can ask for: the view model's side of it. */
@Immutable
class SkiActions(
    val onActive: (Boolean) -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onPickDay: (Int) -> Unit = {},
    val onOpenResort: (SkiResortRef) -> Unit = {},
    val onPlanTrip: (SkiResortRef) -> Unit = {},
    val onOpenRegion: (String) -> Unit = {},
    val onFollow: (SkiResortRef, Boolean) -> Unit = { _, _ -> },
    val onFollowRegion: (String, Boolean) -> Unit = { _, _ -> },
    val onParty: (Int, Int) -> Unit = { _, _ -> },
    val onPass: (SkiPass, Boolean) -> Unit = { _, _ -> },
    val onOrigin: (SkiOrigin) -> Unit = {},
    val onOwnPrice: (String, Int?) -> Unit = { _, _ -> },
    val onAlerts: (SkiAlerts) -> Unit = {},
)

/** A calendar day (counted from 1970) as an instant the weather's date formats can read, on their zero-offset clock. */
internal fun Int.asInstant(): Long = this * SkiForecast.DAY_SECONDS

@Composable
internal fun SkiPass.label(): String = stringResource(labelRes())

internal fun SkiPass.labelRes(): StringResource = when (this) {
    SkiPass.EPIC -> Res.string.ski_pass_epic
    SkiPass.IKON -> Res.string.ski_pass_ikon
    SkiPass.INDY -> Res.string.ski_pass_indy
    SkiPass.MOUNTAIN_COLLECTIVE -> Res.string.ski_pass_mountain_collective
    SkiPass.POWDER_ALLIANCE -> Res.string.ski_pass_powder_alliance
}

/** "3 h 25 min", or "45 min" under the hour. To the nearest five minutes: nobody times a mountain road closer. */
internal fun driveText(seconds: Long): UiText {
    val minutes = WeatherFormat.roundedMinutes(seconds)
    return if (minutes < 60) UiText.of(Res.string.ski_drive_minutes, minutes) else UiText.of(Res.string.ski_drive_hours, minutes / 60, minutes % 60)
}

internal fun ChainLevel.labelRes(): StringResource = when (this) {
    ChainLevel.R1 -> Res.string.ski_chain_r1
    ChainLevel.R2 -> Res.string.ski_chain_r2
    ChainLevel.R3 -> Res.string.ski_chain_r3
}

internal fun SkiPriceTier.Kind.labelRes(): StringResource = when (this) {
    SkiPriceTier.Kind.ANY_DAY -> Res.string.ski_tier_any_day
    SkiPriceTier.Kind.SUNDAY_TO_FRIDAY -> Res.string.ski_tier_weekday
    SkiPriceTier.Kind.SATURDAY -> Res.string.ski_tier_weekend
    SkiPriceTier.Kind.NON_PEAK -> Res.string.ski_tier_non_peak
    SkiPriceTier.Kind.PEAK -> Res.string.ski_tier_peak
}

/** How a resort is doing today, as a word or two. */
internal fun SkiReport.stateText(todayEpochDay: Int, nowEpochSeconds: Long): UiText = when {
    state == SkiOpenState.NOT_OPERATING -> UiText.of(Res.string.ski_state_not_operating)

    runningNow(nowEpochSeconds) -> UiText.of(if (state == SkiOpenState.LIMITED) Res.string.ski_state_limited else Res.string.ski_state_open)

    (openingEpochDay ?: 0) > todayEpochDay -> UiText.of(Res.string.ski_state_opens, WeatherFormat.date(openingEpochDay!!.asInstant(), 0))

    // It last said it was open, too long ago to say so now, and that doesn't make it closed.
    state != SkiOpenState.CLOSED -> UiText.of(Res.string.ski_state_no_recent_report)

    else -> UiText.of(Res.string.ski_state_closed)
}

/** A little coloured label: is it open, what does the pass cover. */
@Composable
internal fun SkiPill(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.clip(CircleShape).background(color.copy(alpha = 0.16f)).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text(text, style = WeatherTheme.type.label, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun SkiReport.stateColor(nowEpochSeconds: Long): Color = when {
    !runningNow(nowEpochSeconds) -> WeatherTheme.colors.onSkyFaint
    state == SkiOpenState.LIMITED -> WeatherTheme.colors.watch
    else -> WeatherTheme.colors.good
}

@Composable
internal fun SkiNote.Tone.color(): Color = when (this) {
    SkiNote.Tone.GOOD -> WeatherTheme.colors.good
    SkiNote.Tone.NEUTRAL -> WeatherTheme.colors.accent
    SkiNote.Tone.WATCH -> WeatherTheme.colors.watch
}

/** A number and what it's a number of, stacked: the Ski tab's small stats. */
@Composable
internal fun SkiStat(label: String, value: String, modifier: Modifier = Modifier, color: Color = WeatherTheme.colors.onSky) {
    Column(modifier.semantics(mergeDescendants = true) { }) {
        Text(value, style = WeatherTheme.type.bodyStrong, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, style = WeatherTheme.type.label, color = WeatherTheme.colors.onSkyFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The days ahead to plan for, a tap to choose one: its name and date, and a column for how much
 * snow the followed mountains expect then at the most, so the storms stand out. The weekend's days
 * are written brighter, since that's when anyone can go.
 */
@Composable
internal fun SkiDayStrip(days: List<SkiStripDay>, selected: Int, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = WeatherTheme.colors
    val type = WeatherTheme.type
    val units = WeatherTheme.units
    val most = (days.maxOfOrNull { it.mostSnowCm } ?: 0.0).coerceAtLeast(MIN_BAR_SCALE_CM)
    val list = rememberLazyListState()
    LaunchedEffect(selected, days.size) {
        val index = days.indexOfFirst { it.epochDay == selected }
        if (index >= 0) list.animateScrollToItem((index - 2).coerceAtLeast(0))
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // A wide card has room for every day at once, with nothing to scroll sideways with a mouse.
        val shared = (maxWidth - STRIP_EDGE * 2 - STRIP_GAP * (days.size - 1).coerceAtLeast(0)) / days.size.coerceAtLeast(1)
        val dayWidth = if (shared >= STRIP_DAY_NARROWEST) shared else STRIP_DAY_WIDTH
        LazyRow(Modifier.fillMaxWidth().testTag("ski_day_strip"), state = list, contentPadding = PaddingValues(horizontal = STRIP_EDGE), horizontalArrangement = Arrangement.spacedBy(STRIP_GAP)) {
            items(days, key = { it.epochDay }) { day ->
                val isSelected = day.epochDay == selected
                val instant = day.epochDay.asInstant()
                val weekend = WeatherFormat.isWeekend(instant, 0)
                val date = WeatherFormat.date(instant, 0).resolve()
                val weekday = stringResource(WeatherFormat.weekday(instant, 0))
                val amount = WeatherFormat.snow(day.mostSnowCm, units).resolve()
                val description = stringResource(Res.string.ski_day_snow_description, weekday, date, amount)
                Column(
                    Modifier
                        .width(dayWidth)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (isSelected) colors.accent.copy(alpha = 0.24f) else Color.Transparent)
                        .clickable(role = Role.Tab) { onPick(day.epochDay) }
                        .handCursor()
                        .semantics(mergeDescendants = true) {
                            this.selected = isSelected
                            contentDescription = description
                        }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(weekday, style = type.bodyStrong, color = if (isSelected || weekend) colors.onSky else colors.onSkyMuted)
                    Text(date, style = type.micro, color = colors.onSkyFaint, maxLines = 1)
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.height(BAR_HEIGHT).width(14.dp), contentAlignment = Alignment.BottomCenter) {
                        val fraction = (day.mostSnowCm / most).toFloat().coerceIn(0f, 1f)
                        Box(Modifier.fillMaxWidth().fillMaxHeight(fraction.coerceAtLeast(MIN_BAR_FRACTION)).clip(RoundedCornerShape(4.dp)).background(if (day.mostSnowCm >= VISIBLE_SNOW_CM) colors.snow else colors.hairline))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(if (day.mostSnowCm >= VISIBLE_SNOW_CM) amount else " ", style = type.micro, color = colors.onSkyMuted, maxLines = 1)
                }
            }
        }
    }
}

/**
 * Each day's snow at a resort as a column with the day being planned for lit, and a whisker up to
 * the most any model expects: how far to trust a tall column is how far its whisker goes.
 */
@Composable
internal fun SkiSnowBars(entry: SkiEntry, days: List<SkiStripDay>, selected: Int, modifier: Modifier = Modifier) {
    val colors = WeatherTheme.colors
    val forecast = entry.forecast
    val values = days.map { day ->
        val at = forecast?.day(day.epochDay)
        Triple(at?.snowCm ?: entry.latest?.outlook?.firstOrNull { it.epochDay == day.epochDay }?.cm ?: 0.0, at?.snowHighCm, day.epochDay == selected)
    }
    val scale = (values.maxOfOrNull { (snow, high, _) -> high ?: snow } ?: 0.0).coerceAtLeast(MIN_BAR_SCALE_CM)
    val snowColor = colors.snow
    val quiet = colors.onSkyFaint
    val hairline = colors.hairline
    val accent = colors.accent
    Canvas(modifier.fillMaxWidth().height(34.dp)) {
        val slot = size.width / values.size.coerceAtLeast(1)
        val bar = slot * 0.62f
        val radius = CornerRadius(2.5.dp.toPx())
        values.forEachIndexed { i, (snow, high, lit) ->
            val x = i * slot + (slot - bar) / 2
            if (lit) drawRoundRect(accent.copy(alpha = 0.22f), Offset(i * slot, 0f), Size(slot, size.height), radius)
            val height = (snow / scale).toFloat() * size.height
            if (high != null && high > snow) {
                val top = size.height * (1f - (high / scale).toFloat())
                drawRect(quiet.copy(alpha = 0.6f), Offset(x + bar / 2 - 0.75f, top), Size(1.5f, size.height - top))
            }
            if (height > 0.5f) {
                drawRoundRect(snowColor.copy(alpha = if (lit) 1f else 0.72f), Offset(x, size.height - height), Size(bar, height), radius)
            } else {
                drawRect(hairline, Offset(x, size.height - 1.5f), Size(bar, 1.5f))
            }
        }
    }
}

/** How full something is: lifts turning, runs open. */
@Composable
internal fun SkiProgress(fraction: Float, modifier: Modifier = Modifier) {
    val colors = WeatherTheme.colors
    Box(modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(colors.hairline)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(CircleShape).background(colors.good))
    }
}

private val BAR_HEIGHT = 34.dp
private val STRIP_EDGE = 12.dp
private val STRIP_GAP = 4.dp
private val STRIP_DAY_WIDTH = 58.dp

/** A day is no narrower than this to fit them all in: its date and its snow still have to fit. */
private val STRIP_DAY_NARROWEST = 44.dp

/** The strip's scale never goes below this, so a dusting doesn't draw as a blizzard. */
private const val MIN_BAR_SCALE_CM = 10.0

/** Less than this at the top of a mountain isn't worth a figure. */
private const val VISIBLE_SNOW_CM = 0.5
private const val MIN_BAR_FRACTION = 0.04f
