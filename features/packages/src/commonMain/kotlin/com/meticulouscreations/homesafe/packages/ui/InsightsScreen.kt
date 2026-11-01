package com.meticulouscreations.homesafe.packages.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.packages.PackagesUiState
import com.meticulouscreations.homesafe.packages.domain.CarrierRecord
import com.meticulouscreations.homesafe.packages.domain.MonthCount
import com.meticulouscreations.homesafe.packages.domain.PackagesAnalysis
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.packages_insights_camera
import homesafe.shared.generated.resources.packages_insights_camera_line
import homesafe.shared.generated.resources.packages_insights_carriers
import homesafe.shared.generated.resources.packages_insights_count
import homesafe.shared.generated.resources.packages_insights_days
import homesafe.shared.generated.resources.packages_insights_fewer
import homesafe.shared.generated.resources.packages_insights_months
import homesafe.shared.generated.resources.packages_insights_months_description
import homesafe.shared.generated.resources.packages_insights_more
import homesafe.shared.generated.resources.packages_insights_none
import homesafe.shared.generated.resources.packages_insights_on_time
import homesafe.shared.generated.resources.packages_insights_others
import homesafe.shared.generated.resources.packages_insights_people
import homesafe.shared.generated.resources.packages_insights_same
import homesafe.shared.generated.resources.packages_insights_shops
import homesafe.shared.generated.resources.packages_insights_spent
import homesafe.shared.generated.resources.packages_insights_this_month
import homesafe.shared.generated.resources.packages_insights_this_year
import homesafe.shared.generated.resources.packages_insights_usual
import homesafe.shared.generated.resources.packages_insights_when
import homesafe.shared.generated.resources.packages_insights_when_line
import homesafe.shared.generated.resources.packages_verdict_short_confirmed
import homesafe.shared.generated.resources.packages_verdict_short_not_seen
import homesafe.shared.generated.resources.packages_verdict_short_seen
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** What the household's deliveries add up to: how many, from whom, how each carrier does, and when they turn up. */
@Composable
internal fun InsightsScreen(state: PackagesUiState, padding: PaddingValues) {
    val colors = PackagesTheme.colors
    val insights = remember(state.snapshot.parcels, state.snapshot.today, state.zone) { PackagesAnalysis.insights(state.snapshot.parcels, state.snapshot.today, state.zone) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = 16.dp).testTag("packages_insights"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (insights.deliveredAll == 0) {
            PackagesCard {
                Text(stringResource(Res.string.packages_insights_none), style = PackagesTheme.type.body, color = colors.textMuted)
            }
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard(rememberCounted(insights.deliveredThisMonth).toString(), stringResource(Res.string.packages_insights_this_month), Modifier.weight(1f), caption = trendText(insights.deliveredThisMonth, insights.deliveredLastMonth))
            StatCard(rememberCounted(insights.deliveredThisYear).toString(), stringResource(Res.string.packages_insights_this_year), Modifier.weight(1f), caption = PackagesFormat.money(insights.spentThisYear).takeIf { insights.spentThisYear > 0 }?.let { stringResource(Res.string.packages_insights_spent, it) })
        }
        PackagesCard(title = stringResource(Res.string.packages_insights_months)) { MonthBars(insights.months) }
        PackagesCard(title = stringResource(Res.string.packages_insights_when)) {
            insights.busiestWeekday?.let { day ->
                insights.busiestHour?.let { hour ->
                    Text(stringResource(Res.string.packages_insights_when_line, stringResource(PackagesFormat.WEEKDAYS_LONG[day]), hourText(hour)), style = PackagesTheme.type.bodyStrong, color = colors.text)
                }
            }
            Spacer(Modifier.height(12.dp))
            HeatGrid(insights.heat, insights.heatMax)
        }
        if (insights.carriers.isNotEmpty()) {
            PackagesCard(title = stringResource(Res.string.packages_insights_carriers)) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { insights.carriers.forEach { CarrierRow(it) } }
            }
        }
        val looked = insights.cameraConfirmed + insights.cameraSeen + insights.cameraNotSeen
        if (looked > 0) {
            PackagesCard(title = stringResource(Res.string.packages_insights_camera)) {
                Text(stringResource(Res.string.packages_insights_camera_line, insights.cameraConfirmed + insights.cameraSeen, looked), style = PackagesTheme.type.bodyStrong, color = colors.text)
                Spacer(Modifier.height(10.dp))
                ShareBar(listOf(insights.cameraConfirmed to colors.good, insights.cameraSeen to colors.camera, insights.cameraNotSeen to colors.warn))
                Spacer(Modifier.height(9.dp))
                LegendRow(colors.good, stringResource(Res.string.packages_verdict_short_confirmed), insights.cameraConfirmed)
                LegendRow(colors.camera, stringResource(Res.string.packages_verdict_short_seen), insights.cameraSeen)
                LegendRow(colors.warn, stringResource(Res.string.packages_verdict_short_not_seen), insights.cameraNotSeen)
            }
        }
        if (insights.perPerson.isNotEmpty()) {
            PackagesCard(title = stringResource(Res.string.packages_insights_people)) {
                val most = insights.perPerson.maxOf { it.second }
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) { insights.perPerson.forEach { (person, count) -> CountBar(PackagesFormat.person(person), count, most, colors.tape) } }
            }
        }
        if (insights.topShops.isNotEmpty()) {
            PackagesCard(title = stringResource(Res.string.packages_insights_shops)) {
                val most = insights.topShops.maxOf { it.second }
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) { insights.topShops.forEach { (shop, count) -> CountBar(shop, count, most, colors.kraft) } }
            }
        }
    }
}

/** A value that grows up to [target] as the page opens. A picture of the screen (a preview) has no time for it to grow, so there it is simply there. */
@Composable
private fun rememberGrowth(target: Float, millis: Int): FloatState {
    val still = LocalInspectionMode.current
    val shown = remember { mutableFloatStateOf(if (still) target else 0f) }
    LaunchedEffect(target) {
        if (still) return@LaunchedEffect
        animate(shown.floatValue, target, animationSpec = tween(millis, easing = FastOutSlowInEasing)) { value, _ -> shown.floatValue = value }
    }
    return shown
}

@Composable
private fun trendText(now: Int, before: Int): String? = when {
    before == 0 -> null
    now > before -> pluralStringResource(Res.plurals.packages_insights_more, now - before, now - before)
    now < before -> pluralStringResource(Res.plurals.packages_insights_fewer, before - now, before - now)
    else -> stringResource(Res.string.packages_insights_same)
}

@Composable
private fun StatCard(value: String, label: String, modifier: Modifier = Modifier, caption: String? = null) {
    val colors = PackagesTheme.colors
    PackagesCard(modifier) {
        Text(value, style = PackagesTheme.type.display, color = colors.text)
        Text(label, style = PackagesTheme.type.label, color = colors.textMuted)
        if (caption != null) Text(caption, style = PackagesTheme.type.label, color = colors.textFaint, modifier = Modifier.padding(top = 2.dp))
    }
}

/** A year of months: bars that grow up as the page opens. */
@Composable
private fun MonthBars(months: List<MonthCount>) {
    val colors = PackagesTheme.colors
    val grow = rememberGrowth(1f, 800)
    val most = (months.maxOfOrNull { it.count } ?: 1).coerceAtLeast(1)
    val monthNames = months.map { stringResource(PackagesFormat.MONTHS[it.month - 1]).take(1) }
    val description = stringResource(Res.string.packages_insights_months_description, months.lastOrNull()?.count ?: 0)
    Column {
        Canvas(Modifier.fillMaxWidth().height(96.dp).semantics { contentDescription = description }) {
            val gap = 5.dp.toPx()
            val width = (size.width - gap * (months.size - 1)) / months.size
            months.forEachIndexed { index, month ->
                val h = size.height * (month.count.toFloat() / most) * grow.floatValue
                val tint = if (index == months.lastIndex) colors.tape else colors.kraft.copy(alpha = 0.62f)
                drawRoundRect(tint, Offset(index * (width + gap), size.height - h), Size(width, h.coerceAtLeast(2.dp.toPx())), CornerRadius(4.dp.toPx()))
            }
        }
        Spacer(Modifier.height(5.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            monthNames.forEach { Text(it, style = PackagesTheme.type.micro, color = colors.textFaint) }
        }
    }
}

/** The hours of the week, Monday at the top: when parcels come, the brighter the more. */
@Composable
private fun HeatGrid(heat: List<List<Int>>, most: Int) {
    val colors = PackagesTheme.colors
    if (heat.isEmpty() || most == 0) return
    // From seven in the morning to nine at night: nothing comes when everyone's asleep.
    val first = 7
    val last = 21
    val days = PackagesFormat.WEEKDAYS.map { stringResource(it).take(1) }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        heat.forEachIndexed { day, hours ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(days[day], style = PackagesTheme.type.micro, color = colors.textFaint, modifier = Modifier.width(14.dp))
                Canvas(Modifier.weight(1f).height(16.dp)) {
                    val gap = 2.dp.toPx()
                    val cells = last - first + 1
                    val width = (size.width - gap * (cells - 1)) / cells
                    (first..last).forEachIndexed { index, hour ->
                        drawRoundRect(colors.heat(hours[hour].toFloat() / most), Offset(index * (width + gap), 0f), Size(width, size.height), CornerRadius(3.dp.toPx()))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(first, 12, 17, last).forEach { Text(hourText(it), style = PackagesTheme.type.micro, color = colors.textFaint) }
        }
    }
}

@Composable
private fun CarrierRow(record: CarrierRecord) {
    val colors = PackagesTheme.colors
    val tint = colors.carrier(record.carrier)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(tint))
            Spacer(Modifier.width(8.dp))
            Text(record.carrier.label.ifEmpty { stringResource(Res.string.packages_insights_others) }, style = PackagesTheme.type.bodyStrong, color = colors.text, modifier = Modifier.weight(1f))
            Text(pluralStringResource(Res.plurals.packages_insights_count, record.delivered, record.delivered), style = PackagesTheme.type.label, color = colors.textMuted)
        }
        Row(Modifier.padding(start = 17.dp, top = 3.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            record.onTimePercent?.let { Text(stringResource(Res.string.packages_insights_on_time, it), style = PackagesTheme.type.label, color = if (it >= 85) colors.good else colors.warn) }
            record.medianDays?.let { Text(pluralStringResource(Res.plurals.packages_insights_days, it, it), style = PackagesTheme.type.label, color = colors.textMuted) }
            record.usualHour?.let { Text(stringResource(Res.string.packages_insights_usual, hourText(it)), style = PackagesTheme.type.label, color = colors.textMuted) }
        }
    }
}

@Composable
private fun CountBar(label: String, count: Int, most: Int, tint: androidx.compose.ui.graphics.Color) {
    val colors = PackagesTheme.colors
    val grow = rememberGrowth(count.toFloat() / most.coerceAtLeast(1), 700)
    Column {
        Row {
            Text(label, style = PackagesTheme.type.label, color = colors.text, modifier = Modifier.weight(1f))
            Text(count.toString(), style = PackagesTheme.type.label, color = colors.textMuted)
        }
        Box(Modifier.padding(top = 3.dp).fillMaxWidth().height(5.dp).clip(CircleShape).background(colors.surfaceRaised)) {
            Box(Modifier.fillMaxWidth(grow.floatValue).height(5.dp).clip(CircleShape).background(tint))
        }
    }
}

@Composable
private fun ShareBar(parts: List<Pair<Int, androidx.compose.ui.graphics.Color>>) {
    val total = parts.sumOf { it.first }.coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().height(9.dp).clip(CircleShape)) {
        parts.filter { it.first > 0 }.forEach { (count, tint) -> Box(Modifier.weight(count.toFloat() / total).height(9.dp).background(tint)) }
    }
}

@Composable
private fun LegendRow(tint: androidx.compose.ui.graphics.Color, label: String, count: Int) {
    val colors = PackagesTheme.colors
    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(tint))
        Spacer(Modifier.width(7.dp))
        Text(label, style = PackagesTheme.type.label, color = colors.textMuted, modifier = Modifier.weight(1f))
        Text(count.toString(), style = PackagesTheme.type.label, color = colors.textMuted)
    }
}
