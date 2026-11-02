package com.meticulouscreations.homesafe.weather.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.text.resolve
import com.meticulouscreations.homesafe.weather.domain.DayForecast
import com.meticulouscreations.homesafe.weather.domain.WeatherFormat
import com.meticulouscreations.homesafe.weather.domain.WeatherReport
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.weather_timeline_day_amount
import homesafe.shared.generated.resources.weather_timeline_day_chance
import homesafe.shared.generated.resources.weather_timeline_day_chance_amount
import homesafe.shared.generated.resources.weather_timeline_day_dry
import homesafe.shared.generated.resources.weather_today
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/** The narrowest a day is drawn: room for its date and for a wind speed with its unit and arrow. */
private val DayColumnMin = 64.dp

/** Above the curves: the day's name, its date, its sky, the chance of rain. */
private val DayHead = 87.dp

/** The band the two curves move in, a row at its top and its foot left for the highs' and lows' numbers. */
private val DayCurve = 132.dp
private val DayCurveInset = 28.dp

/** Under the curves: the chance of what falls, on a wet day. */
private val DayBars = 26.dp

/** And under that: the amount in figures, then the wind. */
private val DayFoot = 50.dp

/**
 * The days ahead as one strip to scroll along, a column a day: its name and date, its sky and
 * chance of rain, its high and low as figures on the two curves that run through the week, how
 * much falls, and the wind. A day worth planning around is lit from top to bottom, rain drawn
 * across its column, so the wet days can be counted at a glance.
 *
 * A tap picks a day ([selected] and what [onSelect] is told count from today, the first of
 * [WeatherReport.daysFromToday]; -1 is no day). The one picked sits in a blob of clear jelly in
 * that day's own colours, which slides to the next day picked, stretching as it goes and
 * wobbling as it lands.
 */
@Composable
internal fun DayTimeline(report: WeatherReport, nowEpochSeconds: Long, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val days = remember(report, nowEpochSeconds) { report.daysFromToday(nowEpochSeconds) }
    if (days.size < 2) return
    val colors = WeatherTheme.colors
    val low = remember(days) { days.minOf { it.lowC } }
    val high = remember(days) { days.maxOf { it.highC } }
    val scroll = rememberScrollState()
    val jelly = remember { DayJelly(selected) }
    LaunchedEffect(selected) {
        launch { jelly.moveTo(selected) }
        scroll.show(selected, days.size)
    }
    Layout(
        content = {
            days.forEachIndexed { i, day ->
                DayColumn(
                    day = day,
                    index = i,
                    utcOffsetSeconds = report.utcOffsetSeconds,
                    highAt = curvePoint(day.highC, low, high),
                    lowAt = curvePoint(day.lowC, low, high),
                    selected = i == selected,
                    jiggle = jelly::jiggle,
                    onSelect = { onSelect(i) },
                )
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .testTag("weather_timeline")
            .horizontalScroll(scroll)
            .drawWithCache {
                val column = size.width / days.size
                val curveTop = DayHead.toPx()
                val barsFoot = curveTop + DayCurve.toPx() + DayBars.toPx()
                val highs = days.mapIndexed { i, day -> Offset((i + 0.5f) * column, curveTop + curvePoint(day.highC, low, high).toPx()) }
                val lows = days.mapIndexed { i, day -> Offset((i + 0.5f) * column, curveTop + curvePoint(day.lowC, low, high).toPx()) }
                val highLine = smoothPath(highs)
                val lowLine = smoothPath(lows)
                val band = Path().apply {
                    addPath(highLine)
                    lineTo(lows.last().x, lows.last().y)
                    // Back along the lows, the same curve the other way.
                    smoothThrough(lows.reversed())
                    close()
                }

                // A day a week off is drawn fainter: the model's reach, not a promise.
                fun stops(celsius: (DayForecast) -> Double) = days.mapIndexed { i, day -> (i + 0.5f) / days.size to colors.temperature(celsius(day)).copy(alpha = certainty(i)) }.toTypedArray()
                val highBrush = Brush.horizontalGradient(*stops { it.highC }, startX = 0f, endX = size.width)
                val lowBrush = Brush.horizontalGradient(*stops { it.lowC }, startX = 0f, endX = size.width)
                val bandBrush = Brush.horizontalGradient(*days.mapIndexed { i, _ -> (i + 0.5f) / days.size to Color.White.copy(alpha = 0.11f * certainty(i)) }.toTypedArray(), startX = 0f, endX = size.width)
                // Each wet day's column: a tint with rain drawn across it, all the way down the strip.
                val inset = 3.dp.toPx()
                val lanes = days.mapIndexedNotNull { i, day ->
                    wetColor(day, colors)?.let { color ->
                        val area = Rect(i * column + inset, 0f, (i + 1) * column - inset, size.height)
                        val cell = Path().apply { addRoundRect(RoundRect(area, CornerRadius(18.dp.toPx()))) }
                        Triple(cell, color.copy(alpha = certainty(i)), streaks(area, 9.dp.toPx(), 0.27f))
                    }
                }
                onDrawBehind {
                    lanes.forEach { (cell, color, streak) ->
                        drawPath(cell, color.copy(alpha = color.alpha * 0.2f))
                        clipPath(cell) { drawPath(streak, color.copy(alpha = color.alpha * 0.42f), style = Stroke(2.dp.toPx())) }
                        drawPath(cell, color.copy(alpha = color.alpha * 0.75f), style = Stroke(1.dp.toPx()))
                    }
                    drawPath(band, bandBrush)
                    days.forEachIndexed { i, day ->
                        // The day's own span, low to high: what ties its two figures together.
                        drawLine(
                            Brush.verticalGradient(listOf(colors.temperature(day.highC), colors.temperature(day.lowC)), startY = highs[i].y, endY = lows[i].y),
                            highs[i],
                            lows[i],
                            2.5.dp.toPx(),
                            StrokeCap.Round,
                            alpha = 0.3f * certainty(i),
                        )
                    }
                    drawPath(highLine, highBrush, style = Stroke(2.8.dp.toPx(), cap = StrokeCap.Round))
                    drawPath(lowLine, lowBrush, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round), alpha = 0.85f)
                    highs.forEachIndexed { i, p ->
                        drawCircle(Color.White, 4.4.dp.toPx(), p, alpha = certainty(i))
                        drawCircle(colors.temperature(days[i].highC), 2.8.dp.toPx(), p, alpha = certainty(i))
                    }
                    lows.forEachIndexed { i, p -> drawCircle(colors.temperature(days[i].lowC), 3.dp.toPx(), p, alpha = certainty(i)) }
                }
            }
            // Over the curves and under the figures: the lines are seen through it.
            .drawBehind {
                val column = size.width / days.size
                val at = jelly.at.value.coerceIn(0f, days.lastIndex.toFloat())
                val from = days[floor(at).toInt()]
                val to = days[minOf(floor(at).toInt() + 1, days.lastIndex)]
                val mix = at - floor(at)
                drawJelly(
                    centerX = (jelly.at.value + 0.5f) * column,
                    width = column - 6.dp.toPx(),
                    presence = jelly.presence.value,
                    squash = jelly.squash(),
                    lean = jelly.lean(),
                    top = lerp(colors.temperature(from.highC), colors.temperature(to.highC), mix),
                    bottom = lerp(colors.temperature(from.lowC), colors.temperature(to.lowC), mix),
                )
            },
    ) { measurables, constraints ->
        // Under horizontalScroll there is no most width, and the least is the window on the strip.
        val viewport = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        val column = dayColumnWidth(viewport, DayColumnMin.roundToPx(), measurables.size)
        val placeables = measurables.map { it.measure(Constraints.fixedWidth(column)) }
        layout(column * placeables.size, placeables.maxOf { it.height }) {
            // Not mirrored: what is drawn behind the columns isn't either.
            placeables.forEachIndexed { i, placeable -> placeable.place(i * column, 0) }
        }
    }
}

/** A day with less than this (millimetres of rain, centimetres of snow) is a dry one: no figure. */
private const val WET_DAY_AMOUNT = 0.2

/** The colour a day worth planning around is lit in, in the strip and on its pill; null for any other. */
private fun wetColor(day: DayForecast, colors: WeatherPalette): Color? = when {
    !day.isWet -> null
    day.isSnowy -> colors.snow
    day.kind.isStorm -> colors.violet
    else -> colors.rain
}

/** Parallel lines across [area], [gap] apart, leaning [lean] of their height to the left as they fall: rain, standing still. */
private fun streaks(area: Rect, gap: Float, lean: Float): Path = Path().apply {
    val run = area.height * lean
    var x = area.left
    while (x < area.right + run) {
        moveTo(x, area.top)
        lineTo(x - run, area.bottom)
        x += gap
    }
}

/** How firmly a day [index] days off is drawn: fainter from [LESS_CERTAIN_FROM] on. */
private fun certainty(index: Int): Float = if (index >= LESS_CERTAIN_FROM) 0.62f else 1f

/** How far down the curves' band a temperature is drawn, between the rows kept for the figures: the week's high at the top, its low at the foot. */
private fun curvePoint(celsius: Double, weekLowC: Double, weekHighC: Double): Dp {
    val rise = if (weekHighC - weekLowC < 0.5) 0.5f else ((celsius - weekLowC) / (weekHighC - weekLowC)).toFloat()
    return DayCurveInset + (DayCurve - DayCurveInset * 2) * (1f - rise)
}

/**
 * How wide to draw each of [days] in a strip [viewport] wide, none narrower than [least]. When
 * they all fit they share the width. When they don't, the window ends halfway through a day, so
 * the cut-off one says there are more to scroll to.
 */
internal fun dayColumnWidth(viewport: Int, least: Int, days: Int): Int {
    if (days <= 0 || least <= 0 || viewport <= 0) return least.coerceAtLeast(0)
    if (days * least <= viewport) return viewport / days
    val shown = (floor(viewport / least.toFloat() + 0.5f) - 0.5f).coerceAtLeast(1f)
    return (viewport / shown).roundToInt()
}

/** Scrolls just far enough that the day at [index] of [count] is whole, with a little of its neighbour. */
private suspend fun ScrollState.show(index: Int, count: Int) {
    if (index < 0) return
    // A day picked before the strip was first measured (one kept from last time) waits for its width.
    snapshotFlow { viewportSize > 0 && maxValue != Int.MAX_VALUE }.first { it }
    val column = (viewportSize + maxValue) / count
    val start = index * column - column / 3
    val end = (index + 1) * column + column / 3
    val target = when {
        start < value -> start
        end > value + viewportSize -> end - viewportSize
        else -> return
    }
    animateScrollTo(target.coerceIn(0, maxValue))
}

/** One day of the strip. [highAt] and [lowAt] are where its two points sit in the curves' band, from the band's top. */
@Composable
private fun DayColumn(
    day: DayForecast,
    index: Int,
    utcOffsetSeconds: Int,
    highAt: Dp,
    lowAt: Dp,
    selected: Boolean,
    jiggle: () -> Float,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WeatherTheme.colors
    val type = WeatherTheme.type
    val units = WeatherTheme.units
    val small = type.micro.copy(letterSpacing = type.label.letterSpacing)
    val haptics = LocalHapticFeedback.current
    val midday = day.epochSeconds + 12 * 3600
    val name = if (index == 0) stringResource(Res.string.weather_today) else stringResource(WeatherFormat.weekday(midday, utcOffsetSeconds))
    val high = WeatherFormat.degrees(day.highC, units)
    val low = WeatherFormat.degrees(day.lowC, units)
    val chance = day.precipitationProbability?.takeIf { it >= 25 && (day.kind.isPrecipitation || it >= 45) }
    val lit = wetColor(day, colors)
    val fall = when {
        max(day.precipitationMm, day.snowfallCm) < WET_DAY_AMOUNT -> null
        day.isSnowy -> WeatherFormat.snow(day.snowfallCm, units)
        else -> WeatherFormat.precipitation(day.precipitationMm, units)
    }
    val date = WeatherFormat.date(midday, utcOffsetSeconds).resolve()
    val wind = WeatherFormat.speed(day.windMaxKmh, units).resolve()
    val sky = stringResource(day.kind.label())
    val percent = chance?.let { WeatherFormat.percent(it) }
    val amount = fall?.resolve()
    // Everything the column shows, the date first among it: a weekday's name comes round twice in ten days.
    val description = when {
        percent != null && amount != null -> stringResource(Res.string.weather_timeline_day_chance_amount, name, date, sky, high, low, percent, amount, wind)
        percent != null -> stringResource(Res.string.weather_timeline_day_chance, name, date, sky, high, low, percent, wind)
        amount != null -> stringResource(Res.string.weather_timeline_day_amount, name, date, sky, high, low, amount, wind)
        else -> stringResource(Res.string.weather_timeline_day_dry, name, date, sky, high, low, wind)
    }
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    // Gives under the finger, and springs back past where it was.
    val give by animateFloatAsState(if (pressed) 0.92f else 1f, spring(dampingRatio = 0.4f, stiffness = 520f), label = "dayGive")
    Column(
        modifier
            .height(DayHead + DayCurve + DayBars + DayFoot)
            .selectable(
                selected = selected,
                interactionSource = press,
                // The jelly is the answer to a tap.
                indication = null,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onSelect()
                },
            )
            .semantics(mergeDescendants = true) { contentDescription = description }
            .testTag("weather_timeline_day_$index")
            .graphicsLayer {
                // The day picked shakes with its jelly, a little less than it.
                val shake = if (selected) jiggle() * 0.05f else 0f
                scaleX = give * (1f + shake)
                scaleY = give * (1f - shake)
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.height(DayHead), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(7.dp))
            Text(
                name,
                style = type.label,
                // Today in the sun's colour; the weekend a little brighter than the working days.
                color = when {
                    index == 0 -> colors.sun
                    selected || WeatherFormat.isWeekend(midday, utcOffsetSeconds) -> colors.onSky
                    else -> colors.onSkyMuted
                },
                maxLines = 1,
            )
            Text(date, style = small, color = colors.onSkyFaint, maxLines = 1, softWrap = false)
            Spacer(Modifier.height(8.dp))
            WeatherGlyph(day.kind, isDay = true, Modifier.size(if (lit != null) 30.dp else 26.dp))
            // A lit day has its chance on a pill under the curves.
            if (lit == null) Text(percent ?: "", style = small, color = colors.rain, maxLines = 1)
        }
        Box(Modifier.height(DayCurve).fillMaxWidth()) {
            // Each sits clear of its point on its curve: the high above, the low below.
            Text(high, style = type.bodyStrong, color = colors.onSky, maxLines = 1, modifier = Modifier.align(Alignment.TopCenter).offset(y = highAt - 26.dp))
            Text(low, style = type.bodyStrong, color = colors.onSkyMuted, maxLines = 1, modifier = Modifier.align(Alignment.TopCenter).offset(y = lowAt + 6.dp))
        }
        Box(Modifier.height(DayBars + 4.dp), contentAlignment = Alignment.BottomCenter) {
            if (lit != null && percent != null) {
                Text(
                    percent,
                    style = small,
                    color = OnWeatherColor,
                    maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(lit).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        val amountColor = when {
            lit != null -> colors.onSky
            day.isSnowy -> colors.snow
            else -> colors.rain
        }
        Text(amount ?: "", style = small, color = amountColor, maxLines = 1, softWrap = false, modifier = Modifier.height(15.dp))
        Spacer(Modifier.height(5.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // A wind worth noticing is written brighter.
            val windColor = if (day.windMaxKmh >= WINDY_KMH) colors.onSky else colors.onSkyFaint
            Canvas(Modifier.size(9.dp)) {
                // The arrow points where the wind is going: the far side from where it comes.
                rotate(day.windDirectionDeg + 180f) {
                    val head = Path().apply {
                        moveTo(size.width / 2, 0f)
                        lineTo(size.width * 0.92f, size.height)
                        lineTo(size.width / 2, size.height * 0.72f)
                        lineTo(size.width * 0.08f, size.height)
                        close()
                    }
                    drawPath(head, windColor)
                }
            }
            Spacer(Modifier.width(3.dp))
            Text(wind, style = small, color = windColor, maxLines = 1, softWrap = false)
        }
    }
}

/** The speed the wind tile calls "windy" from. */
private const val WINDY_KMH = 32.0

// ---- The jelly ---------------------------------------------------------------------------------

/**
 * The blob the picked day sits in, as three springs: where along the strip it is (in days, so
 * 2.5 is halfway from the third to the fourth), how much of it is there (0 gone, 1 whole, and
 * past 1 for a moment as it pops in), and a wobble that rings down to nothing after each move.
 * All three are read while drawing only.
 */
@Stable
private class DayJelly(private var day: Int) {
    val at = Animatable(day.coerceAtLeast(0).toFloat())
    val presence = Animatable(if (day >= 0) 1f else 0f)
    private val wobble = Animatable(0f)

    suspend fun moveTo(index: Int) {
        if (index == day) return
        day = index
        if (index < 0) {
            presence.animateTo(0f, tween(180))
            return
        }
        // From nothing it grows where it is wanted; otherwise it travels there.
        if (presence.value < 0.05f) at.snapTo(index.toFloat())
        coroutineScope {
            launch { at.animateTo(index.toFloat(), spring(dampingRatio = 0.6f, stiffness = 210f)) }
            launch { presence.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 300f)) }
            launch { wobble.animateTo(0f, spring(dampingRatio = 0.22f, stiffness = 260f), initialVelocity = WOBBLE_KICK) }
        }
    }

    /** The wobble alone: what the day's figures shake with. */
    fun jiggle(): Float = wobble.value

    /** How flattened it is: above 0 wider and shorter, below 0 the other way. Travelling flattens it along its way. */
    fun squash(): Float = wobble.value + (abs(at.velocity) * 0.035f).coerceAtMost(0.5f)

    /** How far its middle trails behind its ends while it travels, as a share of its width: against the way it is going. */
    fun lean(): Float = (-at.velocity * 0.012f).coerceIn(-0.22f, 0.22f)

    private companion object {
        const val WOBBLE_KICK = 7f
    }
}

/**
 * A blob of clear jelly the height of the strip, centred on [centerX]: a soft-cornered lozenge
 * whose sides bow out as it is flattened ([squash]) and trail as it moves ([lean]), tinted
 * [top] to [bottom] and thin enough to see through, with a lit rim and a gloss along its top.
 */
private fun DrawScope.drawJelly(centerX: Float, width: Float, presence: Float, squash: Float, lean: Float, top: Color, bottom: Color) {
    if (presence <= 0.01f) return
    val alpha = presence.coerceIn(0f, 1f)
    val inset = 5.dp.toPx()
    val w = width * (0.72f + 0.28f * presence) * (1f + 0.2f * squash)
    val h = (size.height - inset * 2) * (0.9f + 0.1f * presence) * (1f - 0.03f * squash)
    val x0 = centerX - w / 2
    val x1 = centerX + w / 2
    val y0 = (size.height - h) / 2
    val y1 = y0 + h
    val cy = size.height / 2
    val r = minOf(20.dp.toPx(), w / 2)
    // The sides bow out by twice what their control points are moved by half of: a quadratic's middle goes halfway.
    val bow = width * 0.3f * squash
    val trail = width * 2f * lean
    val dip = 5.dp.toPx() * squash
    val blob = Path().apply {
        moveTo(x0 + r, y0)
        quadraticTo(centerX, y0 + dip, x1 - r, y0)
        quadraticTo(x1, y0, x1, y0 + r)
        quadraticTo(x1 + bow + trail, cy, x1, y1 - r)
        quadraticTo(x1, y1, x1 - r, y1)
        quadraticTo(centerX, y1 - dip, x0 + r, y1)
        quadraticTo(x0, y1, x0, y1 - r)
        quadraticTo(x0 - bow + trail, cy, x0, y0 + r)
        quadraticTo(x0, y0, x0 + r, y0)
        close()
    }
    drawPath(
        blob,
        Brush.verticalGradient(0f to top.copy(alpha = 0.3f), 0.5f to Color.White.copy(alpha = 0.1f), 1f to bottom.copy(alpha = 0.28f), startY = y0, endY = y1),
        alpha = alpha,
    )
    // Light pooling low in it, as it does in anything clear and thick.
    drawPath(blob, Brush.radialGradient(listOf(Color.White.copy(alpha = 0.16f), Color.Transparent), Offset(centerX, y1 - w * 0.2f), w * 0.9f), alpha = alpha)
    drawPath(
        blob,
        Brush.verticalGradient(0f to Color.White.copy(alpha = 0.75f), 0.35f to Color.White.copy(alpha = 0.16f), 1f to Color.White.copy(alpha = 0.42f), startY = y0, endY = y1),
        alpha = alpha,
        style = Stroke(1.25.dp.toPx()),
    )
    val gloss = Rect(x0 + w * 0.16f, y0 + 4.dp.toPx(), x1 - w * 0.16f, y0 + 4.dp.toPx() + 13.dp.toPx())
    drawOval(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.42f), Color.Transparent), startY = gloss.top, endY = gloss.bottom), gloss.topLeft, gloss.size, alpha = alpha)
    // And a streak down the side the light comes from, bowing with that side.
    val streak = Path().apply {
        moveTo(x0 + 5.dp.toPx(), y0 + r)
        quadraticTo(x0 + 5.dp.toPx() - bow + trail, cy, x0 + 5.dp.toPx(), y1 - r)
    }
    drawPath(
        streak,
        Brush.verticalGradient(0f to Color.White.copy(alpha = 0.34f), 0.6f to Color.White.copy(alpha = 0.05f), 1f to Color.Transparent, startY = y0 + r, endY = y1 - r),
        alpha = alpha,
        style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round),
    )
}
