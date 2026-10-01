package com.meticulouscreations.homesafe.finance.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meticulouscreations.homesafe.finance.domain.Series
import com.meticulouscreations.homesafe.finance.ui.FinanceTheme
import kotlin.math.abs
import kotlin.math.roundToInt

/** One line on a [LineChart]. The first line given is the one a finger scrubs. */
@Immutable
data class ChartLine(
    val series: Series,
    val color: Color,
    /** A gradient wash under the line, fading to nothing at the bottom. */
    val fill: Boolean = false,
    val width: Float = 2.5f,
    val label: String = "",
    val dashed: Boolean = false,
)

/** A horizontal band of value, e.g. the danger zone above a threshold; a null end runs off the chart. */
@Immutable
data class ChartZone(val from: Double?, val to: Double?, val color: Color, val label: String = "")

/** A horizontal reference line (the Fed's 2% target, 0% inversion); [always] keeps it in view however far the data is. */
@Immutable
data class ChartRule(val value: Double, val color: Color, val label: String = "", val always: Boolean = false)

/** A stretch of time to shade behind the lines (a recession), for time-axis charts. */
@Immutable
data class ChartPeriod(val start: Long, val end: Long, val label: String)

/** How a chart writes values and times on its axis, when it shows one. */
@Immutable
data class ChartAxis(val formatValue: (Double) -> String, val formatTime: (Long) -> String)

private const val SAMPLES = 240

/**
 * The finance app's line chart, drawn the way Robinhood draws one: no grid, a glowing line with
 * a wash beneath it, and the whole thing a scrubber — touch and slide sideways (or hold) and
 * [onScrub] reports the point under the finger, with a haptic tick for each new point, while
 * the line after it dims. Release and it reports null.
 *
 * Every change of data morphs: each line is resampled to the same number of points and the old
 * shape slides into the new one (range and all), so switching 1D → 1Y or a curve from today to
 * a year ago is one continuous motion. The first data draws itself on from the left.
 *
 * [timeAxis] places points by time, which overlaid series of different cadence need; otherwise
 * points are spaced evenly, which closes nights and weekends the way price charts do. [extent]
 * is how much of the width the line spans — less than 1 for a trading day still in progress.
 * [live] pulses a dot on the last point.
 */
@Composable
fun LineChart(
    lines: List<ChartLine>,
    modifier: Modifier = Modifier,
    baseline: Double? = null,
    zones: List<ChartZone> = emptyList(),
    rules: List<ChartRule> = emptyList(),
    timeAxis: Boolean = false,
    extent: Float = 1f,
    live: Boolean = false,
    axis: ChartAxis? = null,
    fitZones: Boolean = false,
    periods: List<ChartPeriod> = emptyList(),
    contentDescription: String = "",
    onScrub: (Int?) -> Unit = {},
) {
    val colors = FinanceTheme.colors
    val haptics = LocalHapticFeedback.current
    val textMeasurer = rememberTextMeasurer()
    val currentOnScrub by rememberUpdatedState(onScrub)

    val target = remember(lines, baseline, zones, rules, timeAxis, extent, fitZones) { geometryOf(lines, baseline, zones, rules, timeAxis, extent, fitZones) }
    var from by remember { mutableStateOf<Geometry?>(null) }
    var to by remember { mutableStateOf(target) }
    val progress = remember { Animatable(1f) }
    var firstReveal by remember { mutableStateOf(true) }

    LaunchedEffect(target) {
        if (target.isEmpty) {
            to = target
            return@LaunchedEffect
        }
        // Whatever is on screen right now — mid-morph included — is where the next morph starts.
        val current = to.takeIf { !it.isEmpty }?.let { t -> from?.let { f -> lerp(f, t, progress.value) } ?: t }
        from = current
        to = target
        firstReveal = current == null
        progress.snapTo(0f)
        progress.animateTo(1f, tween(if (firstReveal) 700 else 420, easing = FastOutSlowInEasing))
    }

    var scrubX by remember { mutableStateOf<Float?>(null) }
    var scrubIndex by remember { mutableIntStateOf(-1) }
    val currentPrimary by rememberUpdatedState(lines.firstOrNull()?.series)
    val currentTarget by rememberUpdatedState(target)
    val currentTimeAxis by rememberUpdatedState(timeAxis)
    val currentExtent by rememberUpdatedState(extent)
    val clock = rememberShaderClock(running = live)

    Canvas(
        modifier
            .semantics { if (contentDescription.isNotEmpty()) this.contentDescription = contentDescription }
            // One gesture handler for the chart's life, reading the data as it stands: new data
            // landing mid-scrub (the day's chart refreshes every minute) mustn't cancel the
            // gesture and leave the finger's hairline stuck on the chart.
            .pointerInput(Unit) {
                fun report(x: Float) {
                    val primary = currentPrimary ?: return
                    if (primary.size < 2) return
                    val w = size.width.toFloat()
                    val clamped = x.coerceIn(0f, w)
                    val idx = indexAt(clamped / w, primary, currentTarget, currentTimeAxis, currentExtent)
                    scrubX = xOfIndex(idx, primary, currentTarget, currentTimeAxis, currentExtent) * w
                    if (idx != scrubIndex) {
                        scrubIndex = idx
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                        currentOnScrub(idx)
                    }
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // Checked after the touch, not before: returning without awaiting one would
                    // have the gesture loop spin.
                    if ((currentPrimary?.size ?: 0) < 2) return@awaitEachGesture
                    var decided = false
                    var cancelled = false
                    val slop = withTimeoutOrNull(220) {
                        val crossed = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                        decided = true
                        cancelled = crossed == null
                        crossed
                    }
                    // A sideways slide, or a hold that outlasted the timeout: scrub until lifted.
                    if (!cancelled && (slop != null || !decided)) {
                        try {
                            report((slop ?: down).position.x)
                            drag(down.id) { change ->
                                change.consume()
                                report(change.position.x)
                            }
                        } finally {
                            scrubX = null
                            scrubIndex = -1
                            currentOnScrub(null)
                        }
                    }
                }
            },
    ) {
        val geo = from?.let { lerp(it, to, progress.value) } ?: to
        if (geo.isEmpty) return@Canvas
        val w = size.width
        val h = size.height
        val top = 6.dp.toPx()
        val bottom = h - (if (axis != null) 18.dp.toPx() else 6.dp.toPx())
        val plotH = bottom - top
        fun y(n: Float) = top + n * plotH

        // Shaded periods first (recessions), so a reader can see what the line did around them.
        if (geo.timeMax > geo.timeMin) {
            periods.forEach { p ->
                if (p.end < geo.timeMin || p.start > geo.timeMax) return@forEach
                val x0 = ((p.start - geo.timeMin).toDouble() / (geo.timeMax - geo.timeMin) * w).toFloat().coerceIn(0f, w)
                val x1 = ((p.end - geo.timeMin).toDouble() / (geo.timeMax - geo.timeMin) * w).toFloat().coerceIn(0f, w)
                val bandW = (x1 - x0).coerceAtLeast(2.dp.toPx())
                drawRect(colors.textSecondary.copy(alpha = 0.13f), Offset(x0, top), Size(bandW, plotH))
                val label = textMeasurer.measure(p.label, TextStyle(color = colors.textSecondary, fontSize = 9.sp))
                if (x0 + label.size.width < w) drawText(label, topLeft = Offset(x0 + 2.dp.toPx(), top))
            }
        }

        // Zones and rules next, behind the lines.
        geo.zones.forEachIndexed { i, (a, b) ->
            val zone = zones.getOrNull(i) ?: return@forEachIndexed
            val y0 = y(minOf(a, b)).coerceIn(0f, bottom)
            val y1 = y(maxOf(a, b)).coerceIn(0f, bottom)
            if (y1 - y0 > 0.5f) {
                drawRect(zone.color.copy(alpha = 0.10f), Offset(0f, y0), Size(w, y1 - y0))
                if (zone.label.isNotEmpty()) {
                    drawText(textMeasurer, zone.label, Offset(8.dp.toPx(), y0 + 4.dp.toPx()), TextStyle(color = zone.color.copy(alpha = 0.8f), fontSize = 10.sp))
                }
            }
        }
        geo.rules.forEachIndexed { i, ry ->
            val rule = rules.getOrNull(i) ?: return@forEachIndexed
            val yy = y(ry)
            if (yy in top..bottom) {
                drawLine(rule.color.copy(alpha = 0.55f), Offset(0f, yy), Offset(w, yy), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
                if (rule.label.isNotEmpty()) {
                    val layout = textMeasurer.measure(rule.label, TextStyle(color = rule.color, fontSize = 10.sp))
                    drawText(layout, topLeft = Offset(w - layout.size.width - 4.dp.toPx(), yy - layout.size.height - 2.dp.toPx()))
                }
            }
        }
        geo.baselineY?.let { by ->
            val yy = y(by)
            // Robinhood's dotted previous-close line.
            drawLine(
                colors.textTertiary,
                Offset(0f, yy),
                Offset(w, yy),
                1.5.dp.toPx(),
                cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.1f, 5.dp.toPx())),
            )
        }

        val reveal = if (firstReveal) progress.value else 1f
        clipRect(right = w * reveal) {
            geo.lines.indices.reversed().forEach { li ->
                val line = lines.getOrNull(li) ?: return@forEach
                val lg = geo.lines[li]
                val path = buildPath(lg, w) { y(it) }
                val dimFrom = if (li == 0) scrubX else null
                drawChartLine(path, lg, line, w, bottom, dimFrom) { y(it) }
            }
        }

        // The finger: a hairline down the chart and a ringed dot on the line.
        val sx = scrubX
        val primary = lines.firstOrNull()
        if (sx != null && primary != null && scrubIndex >= 0 && scrubIndex < primary.series.size) {
            drawLine(colors.textTertiary, Offset(sx, top), Offset(sx, bottom), 1.dp.toPx())
            val v = primary.series.values[scrubIndex]
            val sy = y(geo.normalise(v))
            drawCircle(primary.color.copy(alpha = 0.25f), 11.dp.toPx(), Offset(sx, sy))
            drawCircle(primary.color, 5.dp.toPx(), Offset(sx, sy))
            drawCircle(colors.background, 2.dp.toPx(), Offset(sx, sy))
        } else if (live && primary != null && reveal >= 1f) {
            val lg = geo.lines.firstOrNull()
            if (lg != null && lg.xs.isNotEmpty()) {
                val lx = lg.xs.last() * w
                val ly = y(lg.ys.last())
                val phase = (clock.value % 1.6f) / 1.6f
                drawCircle(primary.color.copy(alpha = 0.35f * (1f - phase)), (5 + 14 * phase).dp.toPx(), Offset(lx, ly))
                drawCircle(primary.color, 4.5.dp.toPx(), Offset(lx, ly))
            }
        }

        if (axis != null) drawAxis(geo, axis, lines, textMeasurer, w, top, bottom, colors.textTertiary)
    }
}

private fun DrawScope.drawChartLine(path: Path, lg: LineGeo, line: ChartLine, w: Float, bottom: Float, dimFrom: Float?, y: (Float) -> Float) {
    val stroke = line.width.dp.toPx()
    if (line.fill && lg.xs.size > 1) {
        val fill = Path().apply {
            addPath(path)
            lineTo(lg.xs.last() * w, bottom)
            lineTo(lg.xs.first() * w, bottom)
            close()
        }
        val topY = lg.ys.minOrNull()?.let(y) ?: 0f
        drawPath(fill, Brush.verticalGradient(listOf(line.color.copy(alpha = 0.28f), line.color.copy(alpha = 0f)), startY = topY, endY = bottom))
    }
    val effect = if (line.dashed) PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())) else null
    fun strokes(alpha: Float) {
        if (!line.dashed) {
            // A cheap glow: two wide, faint passes under the line itself.
            drawPath(path, line.color.copy(alpha = 0.10f * alpha), style = Stroke(stroke * 4.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(path, line.color.copy(alpha = 0.22f * alpha), style = Stroke(stroke * 2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        drawPath(path, line.color.copy(alpha = alpha), style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = effect))
    }
    if (dimFrom == null) {
        strokes(1f)
    } else {
        clipRect(right = dimFrom) { strokes(1f) }
        clipRect(left = dimFrom) { strokes(0.3f) }
    }
}

private fun buildPath(lg: LineGeo, w: Float, y: (Float) -> Float): Path {
    val path = Path()
    val n = lg.xs.size
    if (n == 0) return path
    path.moveTo(lg.xs[0] * w, y(lg.ys[0]))
    for (i in 1 until n) path.lineTo(lg.xs[i] * w, y(lg.ys[i]))
    return path
}

private fun DrawScope.drawAxis(
    geo: Geometry,
    axis: ChartAxis,
    lines: List<ChartLine>,
    measurer: TextMeasurer,
    w: Float,
    top: Float,
    bottom: Float,
    color: Color,
) {
    val style = TextStyle(color = color, fontSize = 10.sp)
    val hi = measurer.measure(axis.formatValue(geo.maxValue), style)
    val lo = measurer.measure(axis.formatValue(geo.minValue), style)
    drawText(hi, topLeft = Offset(w - hi.size.width - 2.dp.toPx(), top))
    drawText(lo, topLeft = Offset(w - lo.size.width - 2.dp.toPx(), bottom - lo.size.height))
    val first = lines.firstOrNull()?.series ?: return
    val t0 = if (geo.timeMin != Long.MAX_VALUE) geo.timeMin else first.times.firstOrNull() ?: return
    val t1 = if (geo.timeMax != Long.MIN_VALUE) geo.timeMax else first.times.lastOrNull() ?: return
    val start = measurer.measure(axis.formatTime(t0), style)
    val end = measurer.measure(axis.formatTime(t1), style)
    val y = bottom + 4.dp.toPx()
    drawText(start, topLeft = Offset(0f, y))
    drawText(end, topLeft = Offset(w - end.size.width, y))
}

/** Where each line's points sit, 0–1 across and 0 (top) to 1 (bottom) down, plus the value range they're scaled to. */
@Immutable
private class Geometry(
    val lines: List<LineGeo>,
    val baselineY: Float?,
    val rules: List<Float>,
    val zones: List<Pair<Float, Float>>,
    val minValue: Double,
    val maxValue: Double,
    val timeMin: Long,
    val timeMax: Long,
) {
    val isEmpty: Boolean get() = lines.isEmpty() || lines.all { it.xs.isEmpty() }

    fun normalise(v: Double): Float = if (maxValue == minValue) 0.5f else (1.0 - (v - minValue) / (maxValue - minValue)).toFloat()
}

@Immutable
private class LineGeo(val xs: FloatArray, val ys: FloatArray)

private fun geometryOf(lines: List<ChartLine>, baseline: Double?, zones: List<ChartZone>, rules: List<ChartRule>, timeAxis: Boolean, extent: Float, fitZones: Boolean): Geometry {
    val present = lines.filter { it.series.size >= 1 }
    if (present.isEmpty()) return Geometry(emptyList(), null, emptyList(), emptyList(), 0.0, 1.0, Long.MAX_VALUE, Long.MIN_VALUE)
    var lo = present.minOf { it.series.min() }
    var hi = present.maxOf { it.series.max() }
    if (baseline != null) {
        lo = minOf(lo, baseline)
        hi = maxOf(hi, baseline)
    }
    rules.forEach { r ->
        // A reference line pulls the range out to it only if it's near the data, so a 2% target
        // doesn't flatten a chart of 30% inflation (or vice versa).
        val span = (hi - lo).coerceAtLeast(1e-9)
        if (r.always || r.value in (lo - span * 0.6)..(hi + span * 0.6)) {
            lo = minOf(lo, r.value)
            hi = maxOf(hi, r.value)
        }
    }
    if (fitZones) {
        // Every zone edge in view, so a reading far from its danger line shows how far.
        zones.forEach { z ->
            listOfNotNull(z.from, z.to).forEach { edge ->
                lo = minOf(lo, edge)
                hi = maxOf(hi, edge)
            }
        }
    }
    if (hi == lo) {
        hi += 1.0
        lo -= 1.0
    }
    val pad = (hi - lo) * 0.08
    lo -= pad
    hi += pad
    val tMin = if (timeAxis) present.minOf { it.series.times.first() } else Long.MAX_VALUE
    val tMax = if (timeAxis) present.maxOf { it.series.times.last() } else Long.MIN_VALUE
    fun norm(v: Double) = (1.0 - (v - lo) / (hi - lo)).toFloat()
    val geos = lines.map { line ->
        val s = line.series
        if (s.size == 0) {
            LineGeo(FloatArray(0), FloatArray(0))
        } else {
            sample(s, timeAxis, tMin, tMax, extent, ::norm)
        }
    }
    fun bound(v: Double?, fallback: Float) = v?.let(::norm) ?: fallback
    return Geometry(
        lines = geos,
        baselineY = baseline?.let(::norm),
        rules = rules.map { norm(it.value) },
        zones = zones.map { z -> bound(z.from, 1.5f) to bound(z.to, -0.5f) },
        minValue = lo + pad,
        maxValue = hi - pad,
        timeMin = tMin,
        timeMax = tMax,
    )
}

/**
 * [s] as [SAMPLES] points. Each output point stands for a bucket of the source, and takes the
 * bucket's more extreme value (its max or min, whichever strays further from the bucket's
 * mean), so a spike — the VIX in March 2020 — survives being thinned from thousands of days.
 */
private fun sample(s: Series, timeAxis: Boolean, tMin: Long, tMax: Long, extent: Float, norm: (Double) -> Float): LineGeo {
    val n = s.size
    val out = SAMPLES
    val xs = FloatArray(out)
    val ys = FloatArray(out)
    if (n == 1) {
        for (i in 0 until out) {
            xs[i] = i / (out - 1f) * extent
            ys[i] = norm(s.values[0])
        }
        return LineGeo(xs, ys)
    }
    val t0 = s.times.first()
    val t1 = s.times.last()
    for (i in 0 until out) {
        val f0 = i / out.toDouble()
        val f1 = (i + 1) / out.toDouble()
        val a = (f0 * (n - 1)).toInt()
        val b = maxOf(a + 1, (f1 * (n - 1)).toInt()).coerceAtMost(n)
        val v = if (b - a <= 1) {
            // Fewer source points than output points: a Catmull-Rom curve through them, so a
            // series of a few dozen snapshots draws as a smooth line rather than a zigzag.
            val pos = i / (out - 1.0) * (n - 1)
            val k = pos.toInt().coerceAtMost(n - 2)
            catmullRom(s.values[maxOf(k - 1, 0)], s.values[k], s.values[k + 1], s.values[minOf(k + 2, n - 1)], pos - k)
        } else {
            var mn = Double.MAX_VALUE
            var mx = -Double.MAX_VALUE
            var sum = 0.0
            for (k in a until b) {
                val x = s.values[k]
                if (x < mn) mn = x
                if (x > mx) mx = x
                sum += x
            }
            val mean = sum / (b - a)
            if (abs(mx - mean) >= abs(mean - mn)) mx else mn
        }
        ys[i] = norm(v)
        xs[i] = if (timeAxis && tMax > tMin) {
            val pos = i / (out - 1.0) * (n - 1)
            val k = pos.toInt().coerceAtMost(n - 2)
            val t = s.times[k] + (s.times[k + 1] - s.times[k]) * (pos - k)
            ((t - tMin) / (tMax - tMin)).toFloat()
        } else {
            i / (out - 1f) * extent
        }
    }
    // The first and last point are exactly the series' ends, so the live dot and the scrubbed
    // value sit on the line.
    ys[0] = norm(s.values.first())
    ys[out - 1] = norm(s.values.last())
    if (timeAxis && tMax > tMin) {
        xs[0] = ((t0 - tMin).toDouble() / (tMax - tMin)).toFloat()
        xs[out - 1] = ((t1 - tMin).toDouble() / (tMax - tMin)).toFloat()
    }
    return LineGeo(xs, ys)
}

private fun catmullRom(p0: Double, p1: Double, p2: Double, p3: Double, t: Double): Double {
    val t2 = t * t
    val t3 = t2 * t
    return 0.5 * (2 * p1 + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3)
}

private fun lerp(a: Geometry, b: Geometry, t: Float): Geometry {
    fun mix(x: Float, y: Float) = x + (y - x) * t
    val lines = b.lines.mapIndexed { i, bl ->
        val al = a.lines.getOrNull(i)
        if (al == null || al.xs.size != bl.xs.size) {
            bl
        } else {
            LineGeo(FloatArray(bl.xs.size) { mix(al.xs[it], bl.xs[it]) }, FloatArray(bl.ys.size) { mix(al.ys[it], bl.ys[it]) })
        }
    }
    return Geometry(
        lines = lines,
        baselineY = b.baselineY?.let { by -> a.baselineY?.let { mix(it, by) } ?: by },
        rules = b.rules.mapIndexed { i, r -> a.rules.getOrNull(i)?.let { mix(it, r) } ?: r },
        zones = b.zones.mapIndexed { i, z -> a.zones.getOrNull(i)?.let { (f, t2) -> mix(f, z.first) to mix(t2, z.second) } ?: z },
        minValue = b.minValue,
        maxValue = b.maxValue,
        timeMin = b.timeMin,
        timeMax = b.timeMax,
    )
}

/** The source index under [fraction] of the width. */
private fun indexAt(fraction: Float, s: Series, geo: Geometry, timeAxis: Boolean, extent: Float): Int {
    if (timeAxis && geo.timeMax > geo.timeMin) {
        val t = geo.timeMin + ((geo.timeMax - geo.timeMin) * fraction).toLong()
        var lo = 0
        var hi = s.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (s.times[mid] < t) lo = mid + 1 else hi = mid
        }
        return if (lo > 0 && abs(s.times[lo - 1] - t) < abs(s.times[lo] - t)) lo - 1 else lo
    }
    return ((fraction / extent).coerceIn(0f, 1f) * (s.size - 1)).roundToInt()
}

private fun xOfIndex(index: Int, s: Series, geo: Geometry, timeAxis: Boolean, extent: Float): Float =
    if (timeAxis && geo.timeMax > geo.timeMin) {
        ((s.times[index] - geo.timeMin).toDouble() / (geo.timeMax - geo.timeMin)).toFloat()
    } else {
        index / (s.size - 1f).coerceAtLeast(1f) * extent
    }
