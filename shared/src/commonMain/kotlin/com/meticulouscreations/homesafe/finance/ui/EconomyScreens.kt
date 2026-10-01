package com.meticulouscreations.homesafe.finance.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.finance.FinanceUiState
import com.meticulouscreations.homesafe.finance.domain.Explainers
import com.meticulouscreations.homesafe.finance.domain.Indicator
import com.meticulouscreations.homesafe.finance.domain.IndicatorCatalog
import com.meticulouscreations.homesafe.finance.domain.IndicatorGroup
import com.meticulouscreations.homesafe.finance.domain.IndicatorReading
import com.meticulouscreations.homesafe.finance.domain.Recessions
import com.meticulouscreations.homesafe.finance.domain.Series
import com.meticulouscreations.homesafe.finance.domain.Signal
import com.meticulouscreations.homesafe.finance.domain.Thresholds
import com.meticulouscreations.homesafe.finance.domain.YieldCurve
import com.meticulouscreations.homesafe.finance.ui.components.AuroraBackground
import com.meticulouscreations.homesafe.finance.ui.components.CascadeIn
import com.meticulouscreations.homesafe.finance.ui.components.ChartAxis
import com.meticulouscreations.homesafe.finance.ui.components.ChartLine
import com.meticulouscreations.homesafe.finance.ui.components.ChartPeriod
import com.meticulouscreations.homesafe.finance.ui.components.ChartRule
import com.meticulouscreations.homesafe.finance.ui.components.ChartZone
import com.meticulouscreations.homesafe.finance.ui.components.LineChart
import com.meticulouscreations.homesafe.finance.ui.components.RangeSelector
import com.meticulouscreations.homesafe.finance.ui.components.RollingNumber
import com.meticulouscreations.homesafe.finance.ui.components.Shimmer
import com.meticulouscreations.homesafe.finance.ui.components.Sparkline
import com.meticulouscreations.homesafe.finance.ui.components.StressRing
import kotlin.math.roundToInt

/** How far back an economic chart looks. */
internal enum class EconRange(val label: String, val years: Int?) {
    Y1("1Y", 1),
    Y3("3Y", 3),
    Y5("5Y", 5),
    Y10("10Y", 10),
    MAX("MAX", null),
}

internal fun Series.within(range: EconRange): Series {
    val years = range.years ?: return this
    val end = lastTime ?: return this
    return since(end - years * Series.YEAR_SECONDS)
}

private val econAxis = ChartAxis(formatValue = { FinanceFormat.grouped(it, 1) + "%" }, formatTime = { FinanceFormat.monthYear(it) })

@Composable
internal fun EconomyScreen(
    state: FinanceUiState,
    listState: LazyListState,
    contentPadding: PaddingValues,
    onOpenIndicator: (String) -> Unit,
    onOpenConnections: () -> Unit = {},
) {
    val colors = FinanceTheme.colors
    val briefing = remember(state.readings, state.quotes) { Narrator.briefing(state.readings, state.stress, state.quotes) }
    LazyColumn(state = listState, contentPadding = contentPadding) {
        item(key = "weather") { CascadeIn(0) { EconomyWeatherCard(briefing, Modifier.padding(top = 8.dp)) } }
        item(key = "connect") { CascadeIn(1) { ConnectionsEntryCard(onOpenConnections, Modifier.padding(top = 12.dp)) } }
        item(key = "inflation-h") { SectionHeader("Inflation", subtitle = "How much more things cost than a year ago", info = "cpi") }
        item(key = "inflation") { InflationBlock(state) }
        item(key = "curve-h") {
            SectionHeader("The yield curve", subtitle = "What the government pays to borrow, from 1 month (left) to 30 years (right)", info = "yieldcurve")
        }
        item(key = "curve") { CascadeIn(0) { YieldCurveBlock(state) } }
        item(key = "yields-h") { SectionHeader("Government borrowing costs", subtitle = "2, 10 and 30 years — mortgages follow the 10-year", info = "treasuries") }
        item(key = "yields") {
            CascadeIn(1) {
                OverlayBlock(
                    lines = listOf(
                        Triple("2Y", state.readings[IndicatorCatalog.twoYear.id], colors.cool),
                        Triple("10Y", state.readings[IndicatorCatalog.tenYear.id], colors.accent),
                        Triple("30Y", state.readings[IndicatorCatalog.thirtyYear.id], colors.violet),
                    ),
                    rules = emptyList(),
                    defaultRange = EconRange.Y5,
                    howToRead = "Higher lines mean borrowing costs more for everyone. Grey bands are past recessions.",
                )
            }
        }
        item(key = "real-h") { SectionHeader("Is your cash keeping up?", subtitle = "The Fed's rate against inflation: above zero, savings beat rising prices", info = "realrate") }
        item(key = "real") {
            CascadeIn(2) {
                val ff = state.readings[IndicatorCatalog.fedFunds.id]
                val cpi = state.readings[IndicatorCatalog.cpi.id]
                Column {
                    val real = if (ff?.latest != null && cpi?.latest != null) ff.latest!! - cpi.latest!! else null
                    if (real != null) {
                        Text(
                            FinanceFormat.grouped(real, 2) + "% real",
                            style = FinanceTheme.type.title,
                            color = if (real >= 0) colors.gain else colors.loss,
                            modifier = Modifier.padding(horizontal = PageGutter),
                        )
                    }
                    OverlayBlock(
                        lines = listOf(Triple("Fed funds", ff, colors.accent), Triple("CPI inflation", cpi, colors.loss)),
                        rules = listOf(ChartRule(0.0, colors.textTertiary)),
                        defaultRange = EconRange.Y10,
                        howToRead = "When the yellow line (the Fed's rate) is above the red one (inflation), cash in savings grows faster than prices.",
                    )
                }
            }
        }
        item(key = "rates-h") { SectionHeader("Key rates", subtitle = "Tap any for the full story", info = "dff") }
        item(key = "rates") {
            val rates = listOf(IndicatorCatalog.fedFunds, IndicatorCatalog.twoYear, IndicatorCatalog.tenYear, IndicatorCatalog.thirtyYear, IndicatorCatalog.mortgage, IndicatorCatalog.corePce)
            Column {
                rates.forEach { ind -> IndicatorRow(ind, state.readings[ind.id], state.failedIndicators.contains(ind.id)) { onOpenIndicator(ind.id) } }
            }
        }
        item(key = "fine") { FinePrint("Economic data from the Federal Reserve Bank of St. Louis (FRED). Inflation is the change from a year earlier.") }
    }
}

/** The headline: CPI inflation, with core CPI and core PCE overlaid against the Fed's 2% target. */
@Composable
private fun InflationBlock(state: FinanceUiState) {
    val colors = FinanceTheme.colors
    val cpi = state.readings[IndicatorCatalog.cpi.id]
    val core = state.readings[IndicatorCatalog.coreCpi.id]
    val pce = state.readings[IndicatorCatalog.corePce.id]
    var range by rememberSaveable { mutableStateOf(EconRange.Y5) }
    var scrub by remember { mutableStateOf<Int?>(null) }
    val tint = colors.signal(cpi?.signal)
    // Sliced once per range, not on every recomposition a scrub causes.
    val cpiSeries = remember(cpi, range) { cpi?.history?.within(range) }
    val coreSeries = remember(core, range) { core?.history?.within(range) }
    val pceSeries = remember(pce, range) { pce?.history?.within(range) }

    val shown = scrub?.let { i -> cpiSeries?.takeIf { i in 0 until it.size }?.let { it.times[i] to it.values[i] } }
    val value = shown?.second ?: cpi?.latest
    val changeText = when {
        shown != null -> "CPI in ${FinanceFormat.monthYear(shown.first)}"
        cpi?.lastChange != null -> "${FinanceFormat.indicatorChange(cpi.lastChange!!, cpi.indicator.unit)} from the month before · ${cpi.latestEpochSeconds?.let { FinanceFormat.monthYear(it) }}"
        else -> " "
    }
    Box(Modifier.fillMaxWidth()) {
        AuroraBackground(tint, Modifier.matchParentSize(), secondary = colors.watch, intensity = if (cpi != null) 1f else 0.3f)
        Column(Modifier.padding(top = 16.dp)) {
            HeroNumber(
                caption = "Prices vs a year ago",
                value = value?.let { FinanceFormat.grouped(it, 2) + "%" } ?: "—",
                change = changeText,
                changeColor = tint,
                trailing = { if (cpi != null) SignalChip(cpi.signal) },
            )
            Spacer(Modifier.height(12.dp))
            if (cpiSeries != null) {
                val lines = listOfNotNull(
                    ChartLine(cpiSeries, colors.loss, fill = true, label = "CPI"),
                    coreSeries?.let { ChartLine(it, colors.watch, width = 1.8f, label = "Core CPI") },
                    pceSeries?.let { ChartLine(it, colors.cool, width = 1.8f, label = "Core PCE") },
                )
                LineChart(
                    lines = lines,
                    rules = listOf(ChartRule(2.0, colors.accent, "Fed target 2%", always = true)),
                    timeAxis = true,
                    axis = econAxis,
                    periods = recessionBands,
                    contentDescription = "Inflation chart",
                    onScrub = { scrub = it },
                    modifier = Modifier.fillMaxWidth().height(230.dp),
                )
                Legend(
                    lines.map { line ->
                        val v = shown?.first?.let { t -> line.series.valueAtOrBefore(t) } ?: line.series.lastValue
                        Triple(line.label, v?.let { FinanceFormat.grouped(it, 2) + "%" } ?: "—", line.color)
                    },
                )
                cpi?.let { r ->
                    Text(Narrator.verdict(r), style = FinanceTheme.type.body, color = colors.textPrimary.copy(alpha = 0.88f), modifier = Modifier.padding(horizontal = PageGutter))
                }
                HowToRead("Each line is how much prices rose over the previous 12 months. Red counts everything; yellow and blue leave out jumpy food and gas prices. The dashed line is the Fed's 2% goal, and grey bands are past recessions.")
            } else {
                Shimmer(Modifier.fillMaxWidth().height(230.dp).padding(horizontal = PageGutter), corner = 14.dp)
            }
            Spacer(Modifier.height(8.dp))
            RangeSelector(EconRange.entries, range, { it.label }, tint, { range = it }, Modifier.padding(horizontal = PageGutter - 4.dp))
        }
    }
}

/** Coloured dots with a label and value each, under an overlaid chart. */
@Composable
internal fun Legend(items: List<Triple<String, String, Color>>, modifier: Modifier = Modifier) {
    FlowRow(
        modifier.fillMaxWidth().padding(horizontal = PageGutter, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.forEach { (label, value, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(6.dp))
                Text(label, style = FinanceTheme.type.label, color = FinanceTheme.colors.textSecondary)
                Spacer(Modifier.width(6.dp))
                Text(value, style = FinanceTheme.type.label, color = FinanceTheme.colors.textPrimary)
            }
        }
    }
}

/** When to compare today's curve with. */
private enum class CurveCompare(val label: String, val daysBack: Long) {
    MONTH("1M ago", 30),
    HALF("6M ago", 182),
    YEAR("1Y ago", 365),
    TWO("2Y ago", 730),
}

/**
 * Today's Treasury curve across the tenors, with the curve as it stood [CurveCompare] ago dashed
 * behind it; switching the comparison morphs the old curve into place. An inverted curve (3-month
 * bills paying more than the 10-year) is called out, since that's the recession signal.
 */
@Composable
private fun YieldCurveBlock(state: FinanceUiState) {
    val colors = FinanceTheme.colors
    var compare by rememberSaveable { mutableStateOf(CurveCompare.YEAR) }
    var scrub by remember { mutableStateOf<Int?>(null) }
    val latestTime = YieldCurve.tenors.mapNotNull { state.curve[it.fredId]?.lastTime }.maxOrNull()
    if (state.failedTenors.isNotEmpty() && state.curveSettled) {
        FinePrint("Some of the curve's maturities didn't load from FRED. Pull down to try again.")
        return
    }
    if (latestTime == null || !state.curveSettled) {
        Shimmer(Modifier.fillMaxWidth().height(260.dp).padding(horizontal = PageGutter), corner = 14.dp)
        return
    }
    fun curveAt(t: Long): Series? {
        val values = YieldCurve.tenors.map { state.curve[it.fredId]?.valueAtOrBefore(t) }
        if (values.any { it == null }) return null
        return Series(LongArray(values.size) { it.toLong() }, DoubleArray(values.size) { values[it]!! })
    }
    val today = curveAt(latestTime)
    if (today == null) {
        FinePrint("Some of the curve's maturities didn't load. Pull down to try again.")
        return
    }
    val then = curveAt(latestTime - compare.daysBack * Series.DAY_SECONDS)
    val inverted = today.values[1] > today.values[8]
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = PageGutter), verticalAlignment = Alignment.CenterVertically) {
            val i = scrub
            Column(Modifier.weight(1f)) {
                if (i != null) {
                    Text("${YieldCurve.tenors[i].label} Treasury", style = FinanceTheme.type.label, color = colors.textSecondary)
                    Text(
                        FinanceFormat.grouped(today.values[i], 2) + "%" + (then?.let { "  vs " + FinanceFormat.grouped(it.values[i], 2) + "%" } ?: ""),
                        style = FinanceTheme.type.title,
                        color = colors.textPrimary,
                    )
                } else {
                    Text("10Y − 3M spread", style = FinanceTheme.type.label, color = colors.textSecondary)
                    val spread = today.values[8] - today.values[1]
                    Text(FinanceFormat.signedPercent(spread), style = FinanceTheme.type.title, color = if (spread >= 0) colors.gain else colors.loss)
                }
            }
            SignalChip(if (inverted) Signal.DANGER else Signal.CALM)
            Spacer(Modifier.width(6.dp))
            Text(if (inverted) "Inverted" else "Normal", style = FinanceTheme.type.label, color = if (inverted) colors.loss else colors.textSecondary)
        }
        Spacer(Modifier.height(8.dp))
        LineChart(
            lines = listOfNotNull(
                ChartLine(today, colors.accent, fill = true, width = 3f, label = "Today"),
                then?.let { ChartLine(it, colors.textSecondary, width = 2f, dashed = true, label = compare.label) },
            ),
            contentDescription = "Treasury yield curve",
            onScrub = { scrub = it },
            modifier = Modifier.fillMaxWidth().height(200.dp),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp)) {
            YieldCurve.tenors.forEachIndexed { i, t ->
                Text(
                    t.label,
                    style = FinanceTheme.type.micro,
                    color = if (scrub == i) colors.textPrimary else colors.textTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        ChipRow(CurveCompare.entries, compare, { "vs " + it.label }, colors.accent, { compare = it })
        HowToRead(
            if (inverted) {
                "Short loans are on the left, long ones on the right. Normally the line climbs — lending for longer earns more. Right now short-term rates are higher than long-term ones, an upside-down shape that has come before every recession since 1970."
            } else {
                "Short loans are on the left, long ones on the right. Normally the line climbs — lending for longer earns more — and it does today. If the left end ever rises above the right, that's a classic recession warning. The dashed line is the curve as it was before."
            },
        )
    }
}

/** Several readings overlaid on one chart with their own range row, and a legend that follows the finger. */
@Composable
private fun OverlayBlock(lines: List<Triple<String, IndicatorReading?, Color>>, rules: List<ChartRule>, defaultRange: EconRange, howToRead: String? = null) {
    var range by rememberSaveable { mutableStateOf(defaultRange) }
    var scrub by remember { mutableStateOf<Int?>(null) }
    val present = remember(lines, range) {
        lines.mapNotNull { (label, r, c) -> r?.history?.within(range)?.takeIf { it.size > 1 }?.let { ChartLine(it, c, width = 2f, label = label) } }
    }
    if (present.isEmpty()) {
        Shimmer(Modifier.fillMaxWidth().height(200.dp).padding(horizontal = PageGutter), corner = 14.dp)
        return
    }
    val at = scrub?.let { i -> present.first().series.takeIf { i in 0 until it.size }?.times?.get(i) }
    Column {
        LineChart(
            lines = present,
            rules = rules,
            timeAxis = true,
            axis = econAxis,
            periods = recessionBands,
            onScrub = { scrub = it },
            modifier = Modifier.fillMaxWidth().height(200.dp),
        )
        Legend(
            present.map { l ->
                val v = at?.let { l.series.valueAtOrBefore(it) } ?: l.series.lastValue
                Triple(l.label, v?.let { FinanceFormat.grouped(it, 2) + "%" } ?: "—", l.color)
            } + listOfNotNull(at?.let { Triple(FinanceFormat.date(it), "", FinanceTheme.colors.textTertiary) }),
        )
        RangeSelector(EconRange.entries, range, { it.label }, FinanceTheme.colors.accent, { range = it }, Modifier.padding(horizontal = PageGutter - 4.dp))
        if (howToRead != null) HowToRead(howToRead)
    }
}

/** The past US recessions, shaded on every economic chart. */
internal val recessionBands = Recessions.us.map { ChartPeriod(it.startEpochSeconds, it.endEpochSeconds, it.label) }

/** A small "how to read this chart" note under a chart. */
@Composable
internal fun HowToRead(text: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = PageGutter, vertical = 8.dp)) {
        Text("HOW TO READ IT", style = FinanceTheme.type.micro, color = FinanceTheme.colors.accent)
        Spacer(Modifier.height(2.dp))
        Text(text, style = FinanceTheme.type.label, color = FinanceTheme.colors.textSecondary)
    }
}

/** A reading as a list row: title, a three-year sparkline in its signal's colour, the value and its signal. */
@Composable
internal fun IndicatorRow(indicator: Indicator, reading: IndicatorReading?, failed: Boolean, onClick: () -> Unit) {
    val colors = FinanceTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = PageGutter, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(Narrator.plainTitle(indicator.id), style = FinanceTheme.type.bodyStrong, color = colors.textPrimary)
            val change = reading?.yearChange
            Text(
                when {
                    failed -> "Couldn't load"
                    change != null -> FinanceFormat.indicatorChange(change, indicator.unit) + " in a year"
                    else -> indicator.cadence.label
                },
                style = FinanceTheme.type.label,
                color = colors.textSecondary,
            )
        }
        if (reading != null && reading.history.size > 1) {
            val recent = reading.history.within(EconRange.Y3)
            Sparkline(recent, colors.signal(reading.signal).takeIf { reading.signal != null } ?: colors.cool, Modifier.width(72.dp).height(28.dp))
            Spacer(Modifier.width(16.dp))
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(88.dp)) {
                Text(reading.latest?.let { FinanceFormat.indicator(it, indicator.unit) } ?: "—", style = FinanceTheme.type.bodyStrong, color = colors.textPrimary)
                if (reading.signal != null) {
                    Text(reading.signal!!.label, style = FinanceTheme.type.micro, color = colors.signal(reading.signal))
                }
            }
        } else if (!failed) {
            Shimmer(Modifier.width(176.dp).height(32.dp))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Risk
// ---------------------------------------------------------------------------------------------

/**
 * The collapse radar: every warning light the app follows, blended into one 0–100 stress score
 * on a plasma gauge, then each light in its group with where it sits against its own calm,
 * watch and danger lines.
 */
@Composable
internal fun RiskScreen(state: FinanceUiState, listState: LazyListState, contentPadding: PaddingValues, onOpenIndicator: (String) -> Unit) {
    val colors = FinanceTheme.colors
    val stress = state.stress
    val readings = IndicatorCatalog.radar.mapNotNull { state.readings[it.id] }
    LazyColumn(state = listState, contentPadding = contentPadding) {
        item(key = "gauge") {
            val level = (stress?.score ?: 0.0) / 100.0
            val tint = when {
                stress == null -> colors.textTertiary
                stress.score >= 50 -> colors.loss
                stress.score >= 30 -> colors.watch
                else -> colors.gain
            }
            Box(Modifier.fillMaxWidth()) {
                AuroraBackground(tint, Modifier.matchParentSize(), secondary = colors.violet, intensity = if (stress != null) 1f else 0.3f)
                Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(260.dp), contentAlignment = Alignment.Center) {
                        StressRing(level.toFloat(), Modifier.matchParentSize())
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("STRESS", style = FinanceTheme.type.micro, color = colors.textSecondary)
                                InfoButton("stress", size = 14.dp)
                            }
                            RollingNumber(stress?.score?.roundToInt()?.toString() ?: "0", FinanceTheme.type.hero, colors.textPrimary)
                            Text(stress?.label ?: "Reading the gauges…", style = FinanceTheme.type.bodyStrong, color = tint)
                        }
                    }
                    if (stress != null) {
                        Text(
                            "${stress.dangers} in danger · ${stress.watches} to watch · ${stress.counted - stress.dangers - stress.watches} calm",
                            style = FinanceTheme.type.label,
                            color = colors.textSecondary,
                        )
                    }
                    if (state.economyLoading) {
                        Text("${readings.size} of ${IndicatorCatalog.radar.size} readings in", style = FinanceTheme.type.micro, color = colors.textTertiary)
                    }
                    Spacer(Modifier.height(16.dp))
                    StressScale(stress)
                }
            }
        }
        item(key = "risk-read") {
            HowToRead(
                "Each card below is one warning sign that has turned before past downturns. The big number is today's reading, " +
                    "the sentence says what it means, and the colored bar shows where it sits between calm (green), worth watching (amber) and danger (red). " +
                    "One sign on its own means little — trouble has come when many light up together.",
                Modifier.padding(top = 8.dp),
            )
        }
        val flashing = readings.filter { it.signal == Signal.DANGER || it.signal == Signal.WATCH }.sortedByDescending { it.stress ?: 0.0 }
        if (flashing.isNotEmpty()) {
            item(key = "flash-h") { SectionHeader("Flashing now", subtitle = "Signs past their 'watch' or 'danger' line — tap one to see why") }
            item(key = "flash") {
                FlowRow(
                    Modifier.fillMaxWidth().padding(horizontal = PageGutter),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    flashing.forEach { r ->
                        val c = colors.signal(r.signal)
                        Row(
                            Modifier
                                .clip(CircleShape)
                                .background(c.copy(alpha = 0.14f))
                                .clickable { onOpenIndicator(r.indicator.id) }
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(c))
                            Spacer(Modifier.width(6.dp))
                            Text(Narrator.plainTitle(r.indicator.id), style = FinanceTheme.type.label, color = colors.textPrimary)
                            Spacer(Modifier.width(6.dp))
                            Text(r.latest?.let { FinanceFormat.indicator(it, r.indicator.unit) } ?: "", style = FinanceTheme.type.label, color = c)
                        }
                    }
                }
            }
        }
        IndicatorGroup.entries.forEach { group ->
            item(key = "group-${group.name}") { SectionHeader(group.title, subtitle = group.blurb) }
            val members = IndicatorCatalog.radar.filter { it.group == group }
            items(members, key = { "risk-${it.id}" }) { ind ->
                CascadeIn(members.indexOf(ind)) {
                    RiskCard(ind, state.readings[ind.id], state.failedIndicators.contains(ind.id)) { onOpenIndicator(ind.id) }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
        item(key = "how") {
            SectionHeader("How the gauge works", info = "stress")
            Text(
                "Each sign scores 0 when it's comfortably calm, 50 at its 'watch' line and 100 at its 'danger' line, and the gauge averages them — " +
                    "the most reliable recession alarms (the yield curve, the jobs alarm, risky companies' borrowing costs and financial stress) count the most. " +
                    "The lines are rules of thumb from past recessions and crises, not forecasts. Nothing here is financial advice.",
                style = FinanceTheme.type.body,
                color = colors.textSecondary,
                modifier = Modifier.padding(horizontal = PageGutter),
            )
        }
    }
}

/** One warning light: value, signal, five-year sparkline, and a track showing where it sits against its lines. */
@Composable
private fun RiskCard(indicator: Indicator, reading: IndicatorReading?, failed: Boolean, onClick: () -> Unit) {
    val colors = FinanceTheme.colors
    FinanceCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(Narrator.plainTitle(indicator.id), style = FinanceTheme.type.bodyStrong, color = colors.textPrimary)
                Text(indicator.title, style = FinanceTheme.type.micro, color = colors.textTertiary)
            }
            if (reading != null) SignalChip(reading.signal)
            InfoButton(indicator.id)
        }
        Spacer(Modifier.height(8.dp))
        when {
            reading != null && reading.latest != null -> {
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Text(FinanceFormat.indicator(reading.latest!!, indicator.unit), style = FinanceTheme.type.title, color = colors.textPrimary)
                        val yc = reading.yearChange
                        Text(
                            listOfNotNull(
                                yc?.let { FinanceFormat.indicatorChange(it, indicator.unit) + " vs a year ago" },
                                reading.latestEpochSeconds?.let { FinanceFormat.monthYear(it) },
                            ).joinToString(" · "),
                            style = FinanceTheme.type.label,
                            color = colors.textSecondary,
                        )
                    }
                    Sparkline(reading.history.within(EconRange.Y5), colors.signal(reading.signal).takeIf { reading.signal != null } ?: colors.cool, Modifier.width(110.dp).height(40.dp))
                }
                Spacer(Modifier.height(8.dp))
                VerdictText(reading)
                indicator.thresholds?.let { th ->
                    Spacer(Modifier.height(12.dp))
                    ThresholdTrack(th, reading.latest!!, reading.history.within(EconRange.Y10))
                }
            }

            failed -> Text("Couldn't load from FRED. Pull down to retry.", style = FinanceTheme.type.label, color = colors.textSecondary)

            else -> Shimmer(Modifier.fillMaxWidth().height(64.dp))
        }
    }
}

/**
 * Calm, watch and danger as three coloured stretches of a track, with a marker at today's reading,
 * scaled to cover the past ten years' range as well as both lines.
 */
@Composable
private fun ThresholdTrack(th: Thresholds, value: Double, context: Series) {
    val colors = FinanceTheme.colors
    var lo = minOf(th.watch, th.danger, value, context.min())
    var hi = maxOf(th.watch, th.danger, value, context.max())
    val pad = (hi - lo) * 0.08
    lo -= pad
    hi += pad
    fun f(v: Double) = ((v - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f)
    val marker = remember { Animatable(0f) }
    LaunchedEffect(value) {
        marker.animateTo(f(value), tween(900, easing = FastOutSlowInEasing))
    }
    Canvas(Modifier.fillMaxWidth().height(14.dp)) {
        val h = 5.dp.toPx()
        val y = (size.height - h) / 2
        val w = size.width
        val r = CornerRadius(h / 2)
        val watchX = f(th.watch) * w
        val dangerX = f(th.danger) * w
        if (th.higherIsWorse) {
            drawRoundRect(colors.gain.copy(alpha = 0.55f), Offset(0f, y), Size(watchX, h), r)
            drawRoundRect(colors.watch.copy(alpha = 0.55f), Offset(watchX, y), Size(dangerX - watchX, h), r)
            drawRoundRect(colors.loss.copy(alpha = 0.55f), Offset(dangerX, y), Size(w - dangerX, h), r)
        } else {
            drawRoundRect(colors.loss.copy(alpha = 0.55f), Offset(0f, y), Size(dangerX, h), r)
            drawRoundRect(colors.watch.copy(alpha = 0.55f), Offset(dangerX, y), Size(watchX - dangerX, h), r)
            drawRoundRect(colors.gain.copy(alpha = 0.55f), Offset(watchX, y), Size(w - watchX, h), r)
        }
        val mx = marker.value * w
        drawCircle(colors.background, 7.dp.toPx(), Offset(mx, size.height / 2))
        drawCircle(colors.textPrimary, 5.dp.toPx(), Offset(mx, size.height / 2))
    }
}

// ---------------------------------------------------------------------------------------------
// Indicator detail
// ---------------------------------------------------------------------------------------------

@Composable
internal fun IndicatorDetailScreen(id: String, state: FinanceUiState, contentPadding: PaddingValues) {
    val indicator = IndicatorCatalog.byId(id) ?: return
    val reading = state.readings[id]
    val colors = FinanceTheme.colors
    val uriHandler = LocalUriHandler.current
    var range by rememberSaveable(id) { mutableStateOf(EconRange.Y10) }
    var scrub by remember(id, range) { mutableStateOf<Int?>(null) }
    val series = remember(reading, range) { reading?.history?.within(range) }
    val tint = colors.signal(reading?.signal).takeIf { reading?.signal != null } ?: colors.cool
    val shown = scrub?.let { i -> series?.takeIf { i in 0 until it.size }?.let { it.times[i] to it.values[i] } }

    LazyColumn(contentPadding = contentPadding) {
        item {
            Box(Modifier.fillMaxWidth()) {
                AuroraBackground(tint, Modifier.matchParentSize(), intensity = if (reading != null) 1f else 0.3f)
                Column(Modifier.padding(top = 12.dp)) {
                    HeroNumber(
                        caption = Narrator.plainTitle(indicator.id) + " · " + indicator.title,
                        value = (shown?.second ?: reading?.latest)?.let { FinanceFormat.indicator(it, indicator.unit) } ?: "—",
                        change = when {
                            shown != null -> FinanceFormat.date(shown.first)
                            reading?.yearChange != null -> "${FinanceFormat.indicatorChange(reading.yearChange!!, indicator.unit)} vs a year ago · ${reading.latestEpochSeconds?.let { FinanceFormat.date(it) }}"
                            else -> " "
                        },
                        changeColor = tint,
                        trailing = {
                            if (reading != null && indicator.thresholds != null) SignalChip(reading.signal)
                            InfoButton(indicator.id)
                        },
                    )
                    Spacer(Modifier.height(12.dp))
                    if (series != null && series.size > 1) {
                        LineChart(
                            lines = listOf(ChartLine(series, tint, fill = true)),
                            zones = zonesFor(indicator, colors),
                            rules = listOfNotNull(indicator.referenceLine?.let { ChartRule(it, colors.textSecondary, indicator.referenceLabel) }),
                            timeAxis = true,
                            axis = ChartAxis({ FinanceFormat.indicator(it, indicator.unit) }, { FinanceFormat.monthYear(it) }),
                            fitZones = true,
                            periods = recessionBands,
                            contentDescription = "${indicator.title} chart",
                            onScrub = { scrub = it },
                            modifier = Modifier.fillMaxWidth().height(260.dp),
                        )
                    } else {
                        Shimmer(Modifier.fillMaxWidth().height(260.dp).padding(horizontal = PageGutter), corner = 14.dp)
                    }
                    Spacer(Modifier.height(8.dp))
                    RangeSelector(EconRange.entries, range, { it.label }, tint, { range = it }, Modifier.padding(horizontal = PageGutter - 4.dp))
                    HowToRead(
                        buildString {
                            append("Drag along the chart to see any past reading. ")
                            if (indicator.thresholds != null) append("The amber band is where it's worth watching and the red band is the danger zone. ")
                            if (indicator.referenceLine != null && indicator.referenceLabel.isNotEmpty()) append("The dashed line marks ${indicator.referenceLabel.lowercase()}. ")
                            append("Grey bands are past recessions — see what this did just before them.")
                        },
                    )
                }
            }
        }
        val explainer = Explainers.byId(id)
        item {
            Column(Modifier.padding(horizontal = PageGutter)) {
                if (reading?.latest != null) {
                    Callout("Right now", Narrator.rightNow(reading), tint, Icons.AutoMirrored.Filled.TrendingUp)
                }
                Narrator.forYou(id, state.readings, state.quotes, state.finance)?.let { mine ->
                    Callout("What it means for you", mine, colors.accent, Icons.Filled.Person)
                }
                explainer?.analogy?.takeIf { it.isNotEmpty() }?.let { Callout("An everyday comparison", it, colors.violet, Icons.Outlined.Lightbulb) }
            }
        }
        item { SectionHeader("Why it matters") }
        item { Text(explainer?.whyYou ?: indicator.why, style = FinanceTheme.type.body, color = colors.textSecondary, modifier = Modifier.padding(horizontal = PageGutter)) }
        explainer?.normal?.takeIf { it.isNotEmpty() }?.let { normal ->
            item { SectionHeader("What's normal") }
            item { Text(normal, style = FinanceTheme.type.body, color = colors.textSecondary, modifier = Modifier.padding(horizontal = PageGutter)) }
        }
        if (indicator.dangerNote.isNotEmpty()) {
            item { SectionHeader("The danger line") }
            item { Text(indicator.dangerNote, style = FinanceTheme.type.body, color = colors.textSecondary, modifier = Modifier.padding(horizontal = PageGutter)) }
        }
        explainer?.howItWorks?.takeIf { it.isNotEmpty() }?.let { how ->
            item { SectionHeader("How it works") }
            item { Text(how, style = FinanceTheme.type.body, color = colors.textSecondary, modifier = Modifier.padding(horizontal = PageGutter)) }
        }
        if (reading != null && reading.latest != null) {
            val h = reading.history
            val t = reading.latestEpochSeconds!!
            val inRange = series ?: h
            val hiIdx = inRange.values.indices.maxByOrNull { inRange.values[it] }
            val loIdx = inRange.values.indices.minByOrNull { inRange.values[it] }
            item { SectionHeader("Stats") }
            item {
                StatGrid(
                    listOfNotNull(
                        "Latest" to FinanceFormat.indicator(reading.latest!!, indicator.unit),
                        "As of" to FinanceFormat.date(t),
                        h.valueAtOrBefore(t - Series.YEAR_SECONDS)?.let { "1 year ago" to FinanceFormat.indicator(it, indicator.unit) },
                        h.valueAtOrBefore(t - 5 * Series.YEAR_SECONDS)?.let { "5 years ago" to FinanceFormat.indicator(it, indicator.unit) },
                        hiIdx?.let { "${range.label} high" to FinanceFormat.indicator(inRange.values[it], indicator.unit) },
                        loIdx?.let { "${range.label} low" to FinanceFormat.indicator(inRange.values[it], indicator.unit) },
                        indicator.thresholds?.let { "Watch line" to FinanceFormat.indicator(it.watch, indicator.unit) },
                        indicator.thresholds?.let { "Danger line" to FinanceFormat.indicator(it.danger, indicator.unit) },
                        "Updated" to indicator.cadence.label,
                        "Source" to "FRED " + indicator.fredIds.joinToString(" / "),
                    ),
                )
            }
        }
        item {
            Text(
                "Open on FRED",
                style = FinanceTheme.type.bodyStrong,
                color = colors.accent,
                modifier = Modifier
                    .padding(horizontal = PageGutter, vertical = 20.dp)
                    .clickable { uriHandler.openUri("https://fred.stlouisfed.org/series/${indicator.fredIds.first()}") },
            )
        }
    }
}

/** The watch and danger bands an indicator's chart shades. */
internal fun zonesFor(indicator: Indicator, colors: FinancePalette): List<ChartZone> {
    val th = indicator.thresholds ?: return emptyList()
    return if (th.higherIsWorse) {
        listOf(ChartZone(th.watch, th.danger, colors.watch, "Watch"), ChartZone(th.danger, null, colors.loss, "Danger"))
    } else {
        listOf(ChartZone(th.danger, th.watch, colors.watch, "Watch"), ChartZone(null, th.danger, colors.loss, "Danger"))
    }
}
