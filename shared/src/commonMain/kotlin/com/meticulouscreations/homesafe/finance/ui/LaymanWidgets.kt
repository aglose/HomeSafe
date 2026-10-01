package com.meticulouscreations.homesafe.finance.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.finance.domain.AccountCategory
import com.meticulouscreations.homesafe.finance.domain.IndicatorReading
import com.meticulouscreations.homesafe.finance.domain.PersonalFinance
import com.meticulouscreations.homesafe.finance.domain.StressScore
import com.meticulouscreations.homesafe.finance.ui.components.CascadeIn
import com.meticulouscreations.homesafe.finance.ui.components.rememberShaderClock
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A little animated sky for [weather]: a sun whose rays turn, clouds that drift, a storm cloud
 * with a flickering bolt. Drawn rather than an icon so it can move; the motion is read only in
 * the draw phase.
 */
@Composable
internal fun WeatherGlyph(weather: Weather, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val colors = FinanceTheme.colors
    val time = rememberShaderClock()
    Canvas(modifier.size(size).clearAndSetSemantics { contentDescription = weather.label }) {
        val t = time.value
        when (weather) {
            Weather.SUNNY -> drawSun(colors.accent, center, this.size.minDimension * 0.22f, t)

            Weather.PARTLY_CLOUDY -> {
                drawSun(colors.watch, Offset(this.size.width * 0.38f, this.size.height * 0.38f), this.size.minDimension * 0.17f, t)
                drawCloud(Color(0xFFD9DEE2), Offset(this.size.width * 0.58f + sin(t * 0.8f) * 1.5f, this.size.height * 0.62f), this.size.minDimension * 0.36f)
            }

            Weather.CLOUDY -> {
                drawCloud(Color(0xFF8E979D), Offset(this.size.width * 0.42f - sin(t * 0.7f) * 1.5f, this.size.height * 0.45f), this.size.minDimension * 0.32f)
                drawCloud(Color(0xFFD9DEE2), Offset(this.size.width * 0.58f + sin(t * 0.9f) * 1.5f, this.size.height * 0.6f), this.size.minDimension * 0.36f)
            }

            Weather.STORMY -> {
                drawCloud(Color(0xFF6B7378), Offset(this.size.width * 0.5f, this.size.height * 0.42f), this.size.minDimension * 0.4f)
                val flash = ((t * 1.3f) % 2.4f) < 0.18f
                val bolt = Path().apply {
                    moveTo(this@Canvas.size.width * 0.52f, this@Canvas.size.height * 0.55f)
                    lineTo(this@Canvas.size.width * 0.42f, this@Canvas.size.height * 0.75f)
                    lineTo(this@Canvas.size.width * 0.52f, this@Canvas.size.height * 0.75f)
                    lineTo(this@Canvas.size.width * 0.45f, this@Canvas.size.height * 0.95f)
                    lineTo(this@Canvas.size.width * 0.62f, this@Canvas.size.height * 0.68f)
                    lineTo(this@Canvas.size.width * 0.52f, this@Canvas.size.height * 0.68f)
                    lineTo(this@Canvas.size.width * 0.6f, this@Canvas.size.height * 0.55f)
                    close()
                }
                drawPath(bolt, if (flash) Color.White else colors.watch)
            }
        }
    }
}

private fun DrawScope.drawSun(color: Color, at: Offset, r: Float, t: Float) {
    drawCircle(color.copy(alpha = 0.18f), r * 1.9f, at)
    rotate(t * 20f, at) {
        for (i in 0 until 8) {
            val a = i * PI.toFloat() / 4
            drawLine(color, Offset(at.x + cos(a) * r * 1.35f, at.y + sin(a) * r * 1.35f), Offset(at.x + cos(a) * r * 1.8f, at.y + sin(a) * r * 1.8f), r * 0.22f, StrokeCap.Round)
        }
    }
    drawCircle(color, r, at)
}

private fun DrawScope.drawCloud(color: Color, at: Offset, w: Float) {
    drawCircle(color, w * 0.34f, Offset(at.x - w * 0.28f, at.y + w * 0.06f))
    drawCircle(color, w * 0.44f, Offset(at.x + w * 0.02f, at.y - w * 0.1f))
    drawCircle(color, w * 0.32f, Offset(at.x + w * 0.34f, at.y + w * 0.08f))
    drawRoundRect(color, Offset(at.x - w * 0.6f, at.y + w * 0.02f), Size(w * 1.2f, w * 0.38f), CornerRadius(w * 0.19f))
}

/**
 * The economy as a weather report: one headline sky, a sentence of summary, and a line per part
 * of the economy — prices, jobs, borrowing, markets, recession signs, government debt — each with
 * its own sky and an ⓘ for what it means.
 */
@Composable
internal fun EconomyWeatherCard(briefing: Briefing, modifier: Modifier = Modifier) {
    val colors = FinanceTheme.colors
    val type = FinanceTheme.type
    val sky = when {
        briefing.items.any { it.weather == Weather.STORMY } && briefing.items.count { it.weather == Weather.STORMY } >= 3 -> Weather.STORMY
        briefing.items.any { it.weather == Weather.STORMY || it.weather == Weather.CLOUDY } -> Weather.PARTLY_CLOUDY
        briefing.items.isEmpty() -> Weather.PARTLY_CLOUDY
        else -> Weather.SUNNY
    }
    FinanceCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            WeatherGlyph(sky, size = 56.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("TODAY'S ECONOMIC WEATHER", style = type.micro, color = colors.textSecondary)
                Text(briefing.headline, style = type.title, color = colors.textPrimary)
            }
            InfoButton("stress")
        }
        if (briefing.summary.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(briefing.summary, style = type.label, color = colors.textSecondary)
        }
        briefing.items.forEachIndexed { i, item ->
            Spacer(Modifier.height(10.dp))
            Hairline()
            CascadeIn(i) {
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    WeatherGlyph(item.weather, size = 30.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.topic, style = type.bodyStrong, color = colors.textPrimary)
                        Text(item.sentence, style = type.label, color = colors.textSecondary)
                    }
                    InfoButton(item.explainerId)
                }
            }
        }
    }
}

/**
 * The stress gauge's scale spelled out under it: calm, elevated, high and severe as four bands,
 * a marker where today's score sits, and what that band means in a sentence.
 */
@Composable
internal fun StressScale(stress: StressScore?, modifier: Modifier = Modifier) {
    val colors = FinanceTheme.colors
    val type = FinanceTheme.type
    val bands = listOf(Triple("Calm", 0.0 to 30.0, colors.gain), Triple("Elevated", 30.0 to 50.0, colors.watch), Triple("High", 50.0 to 70.0, Color(0xFFFF8A3D)), Triple("Severe", 70.0 to 100.0, colors.loss))
    val marker = remember { Animatable(0f) }
    LaunchedEffect(stress?.score) { marker.animateTo(((stress?.score ?: 0.0) / 100).toFloat(), tween(1100, easing = FastOutSlowInEasing)) }
    Column(modifier.fillMaxWidth().padding(horizontal = PageGutter)) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(22.dp)) {
            val w = maxWidth
            Row(Modifier.fillMaxWidth().height(8.dp).align(Alignment.Center).clip(CircleShape)) {
                bands.forEach { (_, range, c) ->
                    Box(Modifier.weight((range.second - range.first).toFloat()).height(8.dp).background(c.copy(alpha = 0.75f)))
                }
            }
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = (w - 16.dp) * marker.value)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(colors.textPrimary)
                    .border(3.dp, colors.background, CircleShape),
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            bands.forEach { (label, range, c) ->
                Text(label, style = type.micro, color = if (stress != null && stress.score >= range.first && stress.score < range.second + (if (range.second == 100.0) 1 else 0)) c else colors.textTertiary, modifier = Modifier.weight((range.second - range.first).toFloat()))
            }
        }
        if (stress != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    stress.score < 30 -> "Calm: almost every warning light is off. The economy isn't showing the strains that have come before past downturns."
                    stress.score < 50 -> "Elevated: a few warning lights are on — worth keeping an eye on, but most signs are still calm. Past recessions came with many lights on at once."
                    stress.score < 70 -> "High: many warning lights are on at once, a pattern seen in the run-up to past downturns."
                    else -> "Severe: most warning lights are flashing, as they did in 2008 and 2020."
                },
                style = type.body,
                color = colors.textSecondary,
            )
        }
    }
}

/** One line of the money checkup: a pass or a flag, what it is, the household's number, and a tip. */
private data class CheckLine(val ok: Boolean, val title: String, val detail: String, val tip: String, val explainerId: String)

/**
 * A plain-language health check of the household's finances, from the sheet: an emergency fund,
 * a savings rate, costly debt, debt against what's owned, and saving for later — each a pass or a
 * flag against a common rule of thumb, with what to do about a flag. Rules of thumb, not advice.
 */
@Composable
internal fun MoneyCheckup(finance: PersonalFinance, fedRate: Double?, modifier: Modifier = Modifier) {
    val colors = FinanceTheme.colors
    val type = FinanceTheme.type
    val lines = buildList {
        finance.runwayMonths?.let { m ->
            add(
                CheckLine(
                    m >= 6,
                    "Emergency fund",
                    "Your cash covers ${FinanceFormat.grouped(m, 1)} months of expenses.",
                    if (m >= 6) "Comfortably past the 3–6 months planners suggest." else "Planners suggest 3–6 months; building cash first protects you against a layoff.",
                    "runway",
                ),
            )
        }
        finance.savingsRate?.let { r ->
            add(
                CheckLine(
                    r >= 0.15,
                    "Saving each month",
                    "You keep ${FinanceFormat.fractionPercent(r)} of take-home pay after expenses.",
                    if (r >= 0.15) "At or above the common 15% goal." else "The common goal is 15–20% (401(k) contributions taken from your paycheck count on top). Trimming a big recurring expense moves this most.",
                    "savingsrate",
                ),
            )
        }
        val hurdle = (fedRate ?: 5.0).coerceAtLeast(5.0)
        val costly = finance.debts.filter { !it.isMortgage && !it.isPaidOff && (it.apr ?: 0.0) > hurdle }
        val anyDebt = finance.debts.any { !it.isMortgage && !it.isPaidOff }
        if (anyDebt) {
            add(
                CheckLine(
                    costly.isEmpty(),
                    "Costly debt",
                    if (costly.isEmpty()) "None of your loans charge more than about ${FinanceFormat.grouped(hurdle, 0)}%." else "${costly.joinToString { it.name }} charge${if (costly.size == 1) "s" else ""} more than ${FinanceFormat.grouped(hurdle, 0)}%.",
                    if (costly.isEmpty()) "Low-rate loans can be paid on schedule while savings earn about as much." else "Paying these down early is a guaranteed return equal to their rate — usually better than savings pay.",
                    "debt",
                ),
            )
        }
        val assets = finance.totalAssets
        if (assets != null && assets > 0) {
            val ratio = finance.consumerDebt / assets
            add(
                CheckLine(
                    ratio < 0.25,
                    "Debt vs what you own",
                    "You owe ${FinanceFormat.fractionPercent(ratio, 0)} as much as you own (not counting the mortgage).",
                    if (ratio < 0.25) "A comfortable margin." else "Over a quarter is worth bringing down.",
                    "networth",
                ),
            )
        }
        val retirement = finance.accounts.filter { it.category == AccountCategory.RETIREMENT }.sumOf { it.balance }
        val total = finance.accounts.sumOf { it.balance }
        if (total > 0) {
            val share = retirement / total
            add(
                CheckLine(
                    share >= 0.25,
                    "Saving for later",
                    "${FinanceFormat.fractionPercent(share, 0)} of what you own is in retirement accounts.",
                    if (share >= 0.25) "Tax-advantaged accounts are doing a lot of the work." else "Retirement accounts grow tax-free or tax-deferred; topping them up is often the cheapest way to invest.",
                    "allocation",
                ),
            )
        }
    }
    if (lines.isEmpty()) return
    val passed = lines.count { it.ok }
    FinanceCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("MONEY CHECKUP", style = type.micro, color = colors.textSecondary)
                Text("$passed of ${lines.size} looking healthy", style = type.title, color = if (passed == lines.size) colors.gain else colors.textPrimary)
            }
        }
        lines.forEachIndexed { i, line ->
            Spacer(Modifier.height(12.dp))
            if (i > 0) Hairline(Modifier.padding(bottom = 12.dp))
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    if (line.ok) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                    contentDescription = if (line.ok) "Healthy" else "Worth a look",
                    tint = if (line.ok) colors.gain else colors.watch,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(line.title, style = type.bodyStrong, color = colors.textPrimary)
                    Text(line.detail, style = type.body, color = colors.textPrimary.copy(alpha = 0.88f))
                    Text(line.tip, style = type.label, color = colors.textSecondary)
                }
                InfoButton(line.explainerId)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("Common rules of thumb, not financial advice.", style = type.micro, color = colors.textTertiary)
    }
}

/** A one-time nudge at the top of a tab: every ⓘ explains, and ? opens the jargon buster. */
@Composable
internal fun ExplainTip(visible: Boolean, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val colors = FinanceTheme.colors
    AnimatedVisibility(visible, exit = fadeOut(tween(160)) + shrinkVertically(tween(220)), modifier = modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = PageGutter, vertical = 8.dp)
                .clip(RoundedCornerShape(14.dp))
                // Solid underneath: it floats over the page, which mustn't show through the text.
                .background(colors.surfaceRaised)
                .background(colors.accent.copy(alpha = 0.10f))
                .border(1.dp, colors.accent.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                .padding(start = 14.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Info, contentDescription = null, tint = colors.accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                "New to this? Tap ⓘ next to anything for a plain-English explanation, or ? at the top for the jargon buster.",
                style = FinanceTheme.type.label,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            Box(Modifier.size(44.dp).clickable(onClickLabel = "Dismiss tip", onClick = onDismiss), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = colors.textSecondary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** An indicator's plain verdict in a row, for cards and lists. */
@Composable
internal fun VerdictText(reading: IndicatorReading?, modifier: Modifier = Modifier) {
    if (reading?.latest == null) return
    Text(Narrator.verdict(reading), style = FinanceTheme.type.label, color = FinanceTheme.colors.textPrimary.copy(alpha = 0.85f), modifier = modifier)
}
