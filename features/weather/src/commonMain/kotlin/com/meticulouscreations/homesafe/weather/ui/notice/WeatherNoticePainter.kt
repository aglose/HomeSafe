package com.meticulouscreations.homesafe.weather.ui.notice

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meticulouscreations.homesafe.ui.notification.NotificationGlyph
import com.meticulouscreations.homesafe.ui.notification.drawGlyph
import com.meticulouscreations.homesafe.weather.domain.HourForecast
import com.meticulouscreations.homesafe.weather.domain.WeatherNoticeArt
import com.meticulouscreations.homesafe.weather.domain.WeatherNoticeChart
import com.meticulouscreations.homesafe.weather.domain.WeatherNoticeIcon
import com.meticulouscreations.homesafe.weather.domain.WeatherReport
import com.meticulouscreations.homesafe.weather.ui.WeatherPalette
import com.meticulouscreations.homesafe.weather.ui.smoothPath
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * How one kind of weather notification looks: its glyph, the two colours of the tile the glyph
 * sits on ([light] at the top left running to [deep]), and the colour of the glyph's own
 * coloured part (the drops, the bolt, the mercury) where the rest is white.
 */
class WeatherNoticeLook(val glyph: NotificationGlyph, val light: Color, val deep: Color, val accent: Color = Color.White) {
    /** One colour to stand for it where the platform takes only one (Android tints the small icon and the buttons with it). */
    val tintArgb: Int get() = lerp(light, deep, 0.45f).toArgb()
}

/** Blues for what falls, violet for a storm, warm for heat, red for what is dangerous: told apart before a word is read. */
val WeatherNoticeIcon.look: WeatherNoticeLook
    get() = when (this) {
        WeatherNoticeIcon.RAIN -> WeatherNoticeLook(NotificationGlyph.RAIN, Color(0xFF4C9BF5), Color(0xFF1F5FCB), Color(0xFFB9E0FF))
        WeatherNoticeIcon.SNOW -> WeatherNoticeLook(NotificationGlyph.SNOW, Color(0xFF8DB7E8), Color(0xFF4A76B4))
        WeatherNoticeIcon.MIX -> WeatherNoticeLook(NotificationGlyph.MIX, Color(0xFF6FA8C9), Color(0xFF35688C), Color(0xFFC9ECFF))
        WeatherNoticeIcon.STORM -> WeatherNoticeLook(NotificationGlyph.STORM, Color(0xFF7B6CE6), Color(0xFF382A92), Color(0xFFFFD84D))
        WeatherNoticeIcon.FOG -> WeatherNoticeLook(NotificationGlyph.FOG, Color(0xFF9AA8B8), Color(0xFF596678), Color(0xFFDCE4EC))
        WeatherNoticeIcon.WIND -> WeatherNoticeLook(NotificationGlyph.WIND, Color(0xFF3CBFB4), Color(0xFF157A78))
        WeatherNoticeIcon.SUN -> WeatherNoticeLook(NotificationGlyph.SUN, Color(0xFFFFB737), Color(0xFFEE7A12), Color(0xFFFFF6D0))
        WeatherNoticeIcon.HEAT -> WeatherNoticeLook(NotificationGlyph.HEAT, Color(0xFFFF8E4A), Color(0xFFD9382A), Color(0xFFFFE08A))
        WeatherNoticeIcon.COLD -> WeatherNoticeLook(NotificationGlyph.COLD, Color(0xFF5BB8F5), Color(0xFF2264C6), Color(0xFFD6F0FF))
        WeatherNoticeIcon.FREEZE -> WeatherNoticeLook(NotificationGlyph.FREEZE, Color(0xFF79C7F2), Color(0xFF2B7AC0))
        WeatherNoticeIcon.WARMER -> WeatherNoticeLook(NotificationGlyph.WARMER, Color(0xFFFFA24D), Color(0xFFE5592A), Color(0xFFFFE9A8))
        WeatherNoticeIcon.COLDER -> WeatherNoticeLook(NotificationGlyph.COLDER, Color(0xFF62B0F2), Color(0xFF2A5FC2), Color(0xFFD6F0FF))
        WeatherNoticeIcon.AIR -> WeatherNoticeLook(NotificationGlyph.AIR, Color(0xFFC49A62), Color(0xFF825C38))
        WeatherNoticeIcon.TORNADO -> WeatherNoticeLook(NotificationGlyph.TORNADO, Color(0xFFF0565B), Color(0xFFA51B2D))
        WeatherNoticeIcon.FLOOD -> WeatherNoticeLook(NotificationGlyph.FLOOD, Color(0xFF3FA7C9), Color(0xFF125F8B), Color(0xFFB8ECFF))
        WeatherNoticeIcon.HURRICANE -> WeatherNoticeLook(NotificationGlyph.HURRICANE, Color(0xFFE8527A), Color(0xFF981D4C))
        WeatherNoticeIcon.FIRE -> WeatherNoticeLook(NotificationGlyph.FIRE, Color(0xFFFF7A3D), Color(0xFFC6281B))
        WeatherNoticeIcon.WARNING -> WeatherNoticeLook(NotificationGlyph.WARNING, Color(0xFFFFA63D), Color(0xFFDF541E))
    }

/**
 * Paints a weather notification's pictures as bitmaps, with no composition: this runs in a
 * background job with no screen. Two of them. The **badge** is the glyph on its coloured tile,
 * square: Android's large icon. The **card** is the whole picture: the tile, the words beside
 * it, the facts as marks, and a chart of the forecast behind the notification (the next two
 * hours of rain by the quarter hour, or the day by the hour). Android shows it as the expanded
 * notification's big picture; iOS attaches it, and cuts its thumbnail from [tileBounds].
 *
 * Everything is laid out in dp on a card [CARD_WIDTH_DP] wide and scaled to the pixels asked for.
 */
object WeatherNoticePainter {
    const val CARD_WIDTH_DP = 360

    /** A card with a chart on it, as Android shows one. */
    const val CARD_HEIGHT_DP = 200

    /** A card with no chart: the tile and its words. */
    const val STRIP_HEIGHT_DP = 72

    fun badge(icon: WeatherNoticeIcon, sizePx: Int): ImageBitmap = paint(sizePx, sizePx, density = sizePx / BADGE_DP) {
        drawNoticeTile(icon.look, Offset.Zero, size.width)
    }

    /** [art] as a card [widthPx] by [heightPx]; text is measured with [fonts] (the platform's own resolver: there is no composition to ask). */
    fun card(art: WeatherNoticeArt, widthPx: Int, heightPx: Int, fonts: FontFamily.Resolver): ImageBitmap {
        val density = Density(widthPx / CARD_WIDTH_DP.toFloat())
        val measurer = TextMeasurer(fonts, density, LayoutDirection.Ltr)
        return paint(widthPx, heightPx, density.density) { drawNoticeCard(art, measurer) }
    }

    /** The height that suits [art] on a card [widthPx] wide: a strip when there is no chart to draw. */
    fun cardHeight(art: WeatherNoticeArt, widthPx: Int, chartHeightDp: Int = CARD_HEIGHT_DP): Int =
        (widthPx * (if (art.chart == null) STRIP_HEIGHT_DP else chartHeightDp) / CARD_WIDTH_DP.toFloat()).roundToInt()

    /** Where the tile sits on a card of that size, as fractions of it: the square iOS cuts the notification's thumbnail from. */
    fun tileBounds(widthPx: Int, heightPx: Int): Rect {
        val scale = widthPx / CARD_WIDTH_DP.toFloat()
        val edge = PAD_DP * scale
        val side = TILE_DP * scale
        return Rect(edge / widthPx, edge / heightPx, (edge + side) / widthPx, (edge + side) / heightPx)
    }

    private fun paint(widthPx: Int, heightPx: Int, density: Float, draw: DrawScope.() -> Unit): ImageBitmap {
        val bitmap = ImageBitmap(widthPx, heightPx)
        CanvasDrawScope().draw(Density(density), LayoutDirection.Ltr, Canvas(bitmap), Size(widthPx.toFloat(), heightPx.toFloat()), draw)
        return bitmap
    }

    private const val BADGE_DP = 48f
}

/** [this] as a PNG file's bytes, for a platform that takes a notification's picture as a file (iOS); null if it can't be encoded. */
expect fun ImageBitmap.toPng(): ByteArray?

private const val PAD_DP = 14f
private const val TILE_DP = 44f

private val Night = Color(0xFF0A111E)
private val RainBlue = Color(0xFF5EB6FF)
private val SnowWhite = Color(0xFFEAF2FF)
private val Ink = Color.White
private val InkMuted = Color(0xC7FFFFFF)
private val InkFaint = Color(0x99FFFFFF)
private val Hairline = Color(0x24FFFFFF)
private val Chip = Color(0x1FFFFFFF)

/** The glyph in white on its tile: a rounded square, [look]'s light colour at the top left running to its deep one. */
internal fun DrawScope.drawNoticeTile(look: WeatherNoticeLook, topLeft: Offset, side: Float) {
    drawRoundRect(
        brush = Brush.linearGradient(listOf(look.light, look.deep), start = topLeft, end = topLeft + Offset(side, side)),
        topLeft = topLeft,
        size = Size(side, side),
        cornerRadius = CornerRadius(side * 0.28f),
    )
    // A little light along the top edge, as on glass.
    drawRoundRect(
        brush = Brush.verticalGradient(listOf(Color(0x33FFFFFF), Color.Transparent), startY = topLeft.y, endY = topLeft.y + side * 0.55f),
        topLeft = topLeft,
        size = Size(side, side),
        cornerRadius = CornerRadius(side * 0.28f),
    )
    val inset = side * 0.18f
    drawGlyph(look.glyph, topLeft + Offset(inset, inset), side - inset * 2, Color.White, look.accent)
}

/**
 * The whole card, to the size of the scope: a night sky leaning toward the notice's colour, the
 * tile with [WeatherNoticeArt.caption] and [WeatherNoticeArt.headline] beside it, the facts as a
 * row of marks, and the chart over its ticks across the foot.
 */
internal fun DrawScope.drawNoticeCard(art: WeatherNoticeArt, measurer: TextMeasurer) {
    val look = art.icon.look
    val pad = PAD_DP.dp.toPx()
    val tile = TILE_DP.dp.toPx()

    drawRect(Brush.verticalGradient(listOf(lerp(Night, look.deep, if (art.isDay) 0.62f else 0.4f), lerp(Night, look.deep, 0.12f))))
    drawCircle(
        Brush.radialGradient(listOf(look.light.copy(alpha = if (art.isDay) 0.34f else 0.2f), Color.Transparent), center = Offset(pad + tile / 2, pad + tile / 2), radius = size.width * 0.55f),
        radius = size.width * 0.55f,
        center = Offset(pad + tile / 2, pad + tile / 2),
    )
    drawNoticeTile(look, Offset(pad, pad), tile)

    // The words beside the tile, centred on it together.
    val textLeft = pad + tile + 12.dp.toPx()
    val textWidth = (size.width - textLeft - pad).toInt().coerceAtLeast(0)
    val caption = art.caption?.let { measurer.line(it, CaptionStyle, textWidth) }
    val headline = art.headline?.let { measurer.line(it, HeadlineStyle, textWidth) }
    val wordsHeight = (caption?.size?.height ?: 0) + (headline?.size?.height ?: 0)
    var y = pad + (tile - wordsHeight) / 2
    caption?.let {
        drawText(it, InkMuted, Offset(textLeft, y))
        y += it.size.height
    }
    headline?.let { drawText(it, Ink, Offset(textLeft, y)) }

    var top = pad + tile + 10.dp.toPx()
    if (art.facts.isNotEmpty() && top + FACT_DP.dp.toPx() <= size.height - pad) {
        drawFacts(art.facts, measurer, Offset(pad, top), size.width - pad * 2)
        top += FACT_DP.dp.toPx() + 10.dp.toPx()
    }

    val chart = art.chart ?: return
    val ticks = art.ticks.map { measurer.line(it, TickStyle, (size.width / 3).toInt()) }
    val ticksHeight = ticks.maxOfOrNull { it.size.height } ?: 0
    val bottom = size.height - 10.dp.toPx() - ticksHeight - 4.dp.toPx()
    val area = Rect(pad, top, size.width - pad, bottom)
    if (area.height < 16.dp.toPx()) return
    when (chart) {
        is WeatherNoticeChart.Soon -> drawSoon(chart, area, ticks)
        is WeatherNoticeChart.Day -> drawDay(chart, area, ticks)
    }
}

private const val FACT_DP = 22f

/** Each fact as a pill: its glyph and its number. As many as fit across [width]; the rest are the notification's text to tell. */
private fun DrawScope.drawFacts(facts: List<WeatherNoticeArt.Fact>, measurer: TextMeasurer, topLeft: Offset, width: Float) {
    val height = FACT_DP.dp.toPx()
    val glyph = 13.dp.toPx()
    val inner = 8.dp.toPx()
    var x = topLeft.x
    facts.forEach { fact ->
        val text = measurer.line(fact.text, FactStyle, width.toInt())
        val lead = if (fact.icon != null) glyph + 5.dp.toPx() else 0f
        val pill = inner + lead + text.size.width + inner
        if (x + pill > topLeft.x + width) return
        drawRoundRect(Chip, Offset(x, topLeft.y), Size(pill, height), CornerRadius(height / 2))
        fact.icon?.let { drawGlyph(it.look.glyph, Offset(x + inner, topLeft.y + (height - glyph) / 2), glyph, InkMuted, it.look.light.lighten()) }
        drawText(text, Ink, Offset(x + inner + lead, topLeft.y + (height - text.size.height) / 2))
        x += pill + 6.dp.toPx()
    }
}

/**
 * The next two hours by the quarter hour, as the weather app's own chart draws them: how hard it
 * falls on a square-root scale (so a drizzle is still a bar beside a downpour), two faint lines
 * where moderate and heavy begin, and a mark where the shower starts.
 */
private fun DrawScope.drawSoon(chart: WeatherNoticeChart.Soon, area: Rect, ticks: List<TextLayoutResult>) {
    val slices = chart.slices
    if (slices.isEmpty()) return
    val gap = 5.dp.toPx()
    val bar = (area.width - gap * (slices.size - 1)) / slices.size
    fun heightOf(ratePerHour: Double) = sqrt((ratePerHour / FULL_SCALE_MM_PER_HOUR).coerceIn(0.0, 1.0)).toFloat() * area.height
    fun left(i: Int) = area.left + i * (bar + gap)

    listOf(MODERATE_MM_PER_HOUR, HEAVY_MM_PER_HOUR).forEach { threshold ->
        val y = area.bottom - heightOf(threshold)
        drawLine(Hairline, Offset(area.left, y), Offset(area.right, y), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 5.dp.toPx())))
    }
    slices.forEachIndexed { i, slice ->
        val h = heightOf(slice.ratePerHourMm).coerceAtLeast(3.dp.toPx())
        val color = when {
            !slice.isWet -> Hairline
            slice.isSnow -> SnowWhite
            else -> RainBlue
        }
        drawRoundRect(color, Offset(left(i), area.bottom - h), Size(bar, h), CornerRadius(4.dp.toPx()))
    }
    // Where it starts: a line down the gap before the first wet bar, with a dot on top.
    val start = slices.indexOfFirst { it.epochSeconds >= chart.startEpochSeconds }
    if (start > 0) {
        val x = left(start) - gap / 2
        drawLine(InkMuted, Offset(x, area.top + 3.dp.toPx()), Offset(x, area.bottom), 1.5.dp.toPx(), StrokeCap.Round)
        drawCircle(Ink, 3.dp.toPx(), Offset(x, area.top + 3.dp.toPx()))
    }
    // A word under every other bar: now, and each half hour after it.
    ticks.forEachIndexed { n, tick ->
        val centre = left(n * 2) + bar / 2
        drawText(tick, InkFaint, Offset((centre - tick.size.width / 2).coerceIn(area.left, max(area.left, area.right - tick.size.width)), area.bottom + 4.dp.toPx()))
    }
}

/**
 * A day by the hour, as the Forecast tab draws one: the temperature as a curve in its own
 * colours, what falls as bars under it, a faint line each six hours, and on today's the present
 * marked with what is behind it dimmed.
 */
private fun DrawScope.drawDay(chart: WeatherNoticeChart.Day, area: Rect, ticks: List<TextLayoutResult>) {
    val hours = chart.hours
    if (hours.size < 2) return
    val palette = WeatherPalette()
    val step = area.width / hours.size
    val low = hours.minOf { it.temperatureC }
    val high = hours.maxOf { it.temperatureC }
    val wettest = max(1.5, hours.maxOf { it.precipitationMm })
    val curveHeight = area.height * 0.62f

    ticks.forEachIndexed { n, tick ->
        val x = area.left + n * 6 * step
        if (n > 0) drawLine(Hairline, Offset(x, area.top), Offset(x, area.bottom), 1.dp.toPx())
        drawText(tick, InkFaint, Offset((x + if (n > 0) 3.dp.toPx() else 0f).coerceAtMost(max(area.left, area.right - tick.size.width)), area.bottom + 4.dp.toPx()))
    }
    val points = hours.mapIndexed { i, hour ->
        val rise = if (high - low < 0.5) 0.5f else ((hour.temperatureC - low) / (high - low)).toFloat()
        Offset(area.left + (i + 0.5f) * step, area.top + 5.dp.toPx() + (curveHeight - 10.dp.toPx()) * (1f - rise))
    }
    val curve = smoothPath(points)
    val stops = hours.mapIndexed { i, hour -> (i + 0.5f) / hours.size to palette.temperature(hour.temperatureC) }.toTypedArray()

    /** The bars and the curve, at [alpha]. */
    fun forecast(alpha: Float) {
        hours.forEachIndexed { i, hour ->
            if (hour.precipitationMm >= 0.05) {
                val h = (sqrt(hour.precipitationMm / wettest).toFloat() * area.height * 0.42f).coerceAtLeast(2.dp.toPx())
                val sure = 0.4f + 0.6f * ((hour.precipitationProbability ?: 70) / 100f)
                drawRoundRect(
                    (if (hour.kind.isSnow) SnowWhite else RainBlue).copy(alpha = sure * alpha),
                    Offset(area.left + i * step + step * 0.18f, area.bottom - h),
                    Size(step * 0.64f, h),
                    CornerRadius(2.dp.toPx()),
                )
            }
        }
        drawPath(curve, Brush.horizontalGradient(*stops, startX = area.left, endX = area.right), alpha = alpha, style = Stroke(2.6.dp.toPx(), cap = StrokeCap.Round))
    }

    val at = chart.nowEpochSeconds?.let { nowIndex(hours, it) }
    if (at == null) {
        forecast(1f)
        return
    }
    // What is behind the present is history: drawn faint, so the eye goes to what is still to come.
    val x = area.left + at * step
    val reach = 4.dp.toPx()
    clipRect(area.left - reach, area.top - reach, x, area.bottom + reach) { forecast(0.3f) }
    clipRect(x, area.top - reach, area.right + reach, area.bottom + reach) { forecast(1f) }
    drawLine(InkMuted, Offset(x, area.top), Offset(x, area.bottom), 1.5.dp.toPx(), StrokeCap.Round)
    // On the curve at that instant: each point is the middle of its hour, so between two of them.
    val i = (at - 0.5f).toInt().coerceIn(0, points.lastIndex - 1)
    val y = points[i].y + (points[i + 1].y - points[i].y) * (at - 0.5f - i).coerceIn(0f, 1f)
    drawCircle(Night, 4.5.dp.toPx(), Offset(x, y))
    drawCircle(Ink, 3.dp.toPx(), Offset(x, y))
}

/** How far through [hours] the instant [now] falls, in hours and parts of one; null when it is outside them. */
private fun nowIndex(hours: List<HourForecast>, now: Long): Float? {
    val first = hours.first().epochSeconds
    val last = hours.last().epochSeconds + WeatherReport.HOUR_SECONDS
    return if (now < first || now > last) null else (now - first) / WeatherReport.HOUR_SECONDS.toFloat()
}

private fun TextMeasurer.line(text: String, style: TextStyle, maxWidth: Int): TextLayoutResult =
    measure(text, style, overflow = TextOverflow.Ellipsis, softWrap = false, maxLines = 1, constraints = Constraints(maxWidth = maxWidth.coerceAtLeast(0)))

/** Toward white, for a small glyph on dark glass where the tile's own colour would be too dim. */
private fun Color.lighten(): Color = lerp(this, Color.White, 0.45f)

private val CaptionStyle = TextStyle(fontFamily = FontFamily.Default, fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp)
private val HeadlineStyle = TextStyle(fontFamily = FontFamily.Default, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
private val FactStyle = TextStyle(fontFamily = FontFamily.Default, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
private val TickStyle = TextStyle(fontFamily = FontFamily.Default, fontSize = 10.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp)

/** Violent rain: the top of the chart. The same scale and lines as the weather app's own. */
private const val FULL_SCALE_MM_PER_HOUR = 16.0
private const val MODERATE_MM_PER_HOUR = 2.5
private const val HEAVY_MM_PER_HOUR = 7.6
