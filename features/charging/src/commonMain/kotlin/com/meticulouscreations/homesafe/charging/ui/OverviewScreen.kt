package com.meticulouscreations.homesafe.charging.ui

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.charging.ChargingUiState
import com.meticulouscreations.homesafe.charging.domain.CarMonth
import com.meticulouscreations.homesafe.charging.domain.CarNow
import com.meticulouscreations.homesafe.charging.domain.ChargingMonth
import com.meticulouscreations.homesafe.charging.domain.ChargingSetup
import com.meticulouscreations.homesafe.charging.domain.ChargingSummary
import com.meticulouscreations.homesafe.charging.domain.RatePlan
import com.meticulouscreations.homesafe.ui.handCursor
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.charging_car_efficiency
import homesafe.shared.generated.resources.charging_car_fast
import homesafe.shared.generated.resources.charging_car_home
import homesafe.shared.generated.resources.charging_car_other
import homesafe.shared.generated.resources.charging_car_per_mile
import homesafe.shared.generated.resources.charging_cars_title
import homesafe.shared.generated.resources.charging_empty_body
import homesafe.shared.generated.resources.charging_empty_title
import homesafe.shared.generated.resources.charging_gas_cheaper
import homesafe.shared.generated.resources.charging_gas_detail
import homesafe.shared.generated.resources.charging_gas_title
import homesafe.shared.generated.resources.charging_history_title
import homesafe.shared.generated.resources.charging_home_per_kwh
import homesafe.shared.generated.resources.charging_home_title
import homesafe.shared.generated.resources.charging_live_battery
import homesafe.shared.generated.resources.charging_live_kw
import homesafe.shared.generated.resources.charging_live_left
import homesafe.shared.generated.resources.charging_live_so_far
import homesafe.shared.generated.resources.charging_live_title
import homesafe.shared.generated.resources.charging_month_charges
import homesafe.shared.generated.resources.charging_month_down
import homesafe.shared.generated.resources.charging_month_newer
import homesafe.shared.generated.resources.charging_month_older
import homesafe.shared.generated.resources.charging_month_up
import homesafe.shared.generated.resources.charging_months_title
import homesafe.shared.generated.resources.charging_paid_provider
import homesafe.shared.generated.resources.charging_paid_provider_detail
import homesafe.shared.generated.resources.charging_paid_title
import homesafe.shared.generated.resources.charging_peak_share
import homesafe.shared.generated.resources.charging_place_fast_short
import homesafe.shared.generated.resources.charging_place_home
import homesafe.shared.generated.resources.charging_place_other
import homesafe.shared.generated.resources.charging_plan_compare_less
import homesafe.shared.generated.resources.charging_plan_compare_more
import homesafe.shared.generated.resources.charging_plan_compare_same
import homesafe.shared.generated.resources.charging_setup_body
import homesafe.shared.generated.resources.charging_setup_power
import homesafe.shared.generated.resources.charging_setup_rates
import homesafe.shared.generated.resources.charging_setup_receipts
import homesafe.shared.generated.resources.charging_setup_title
import homesafe.shared.generated.resources.charging_unmatched_note
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** One month at a time: what it came to, where, for each car, and how that compares. */
@Composable
internal fun OverviewScreen(state: ChargingUiState, padding: PaddingValues, onOpenCharge: (Long) -> Unit, onSeeRates: () -> Unit) {
    val summary = state.summary
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    val month = summary.months.firstOrNull { it.key == picked } ?: summary.months.firstOrNull()
    val index = summary.months.indexOf(month)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).testTag("charging_overview"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        summary.charging.forEach { LiveCard(it, state.nowEpochSeconds, onOpenCharge) }
        if (month == null) {
            EmptyCard()
        } else {
            HeroCard(
                month = month,
                previous = summary.months.getOrNull(index + 1),
                onOlder = summary.months.getOrNull(index + 1)?.let { older -> { picked = older.key } },
                onNewer = summary.months.getOrNull(index - 1)?.let { newer -> { picked = newer.key } },
            )
            if (month.cars.isNotEmpty()) {
                ChargingCard(title = stringResource(Res.string.charging_cars_title)) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { month.cars.forEach { CarRow(it, month) } }
                }
            }
            if (summary.months.size > 1) {
                ChargingCard(title = stringResource(Res.string.charging_months_title)) {
                    MonthBars(summary.months, month.key, onSelect = { picked = it })
                }
            }
            if (month.homeKwh > 0) HomeCard(month, summary, onSeeRates)
            GasCard(month, summary)
            PaidCard(month, summary)
            if (summary.months.size > 1) HistoryCard(summary.months, month.key, onSelect = { picked = it })
        }
        if (!summary.setup.complete) SetupCard(summary.setup)
    }
}

@Composable
private fun LiveCard(car: CarNow, nowEpochSeconds: Long, onOpenCharge: (Long) -> Unit) {
    val colors = ChargingTheme.colors
    val session = car.session ?: return
    Column(
        Modifier
            .fillMaxWidth()
            .clip(ChargingCardShape)
            .background(colors.home.copy(alpha = 0.14f))
            .clickable(role = Role.Button) { onOpenCharge(session.id) }
            .handCursor()
            .padding(18.dp)
            .testTag("charging_live_${car.car}"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Bolt, contentDescription = null, tint = colors.home, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(Res.string.charging_live_title, car.name, stringResource(session.place.label)), style = ChargingTheme.type.bodyStrong, color = colors.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            car.kw?.takeIf { it > 0 }?.let { Text(stringResource(Res.string.charging_live_kw, ChargingFormat.fixed(it, 1)), style = ChargingTheme.type.bodyStrong, color = colors.home) }
        }
        Spacer(Modifier.height(10.dp))
        val battery = car.battery
        if (battery != null) {
            BatteryBar(session.socStart ?: battery, battery, colors.home)
            Spacer(Modifier.height(8.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (battery != null) Text(stringResource(Res.string.charging_live_battery, battery.toString(), (car.limit ?: 100).toString()), style = ChargingTheme.type.label, color = colors.textMuted)
            val soFar = session.cost
            Text(
                if (soFar != null) stringResource(Res.string.charging_live_so_far, moneyText(soFar), kwhText(session.kwh)) else kwhText(session.kwh),
                style = ChargingTheme.type.label,
                color = colors.textMuted,
            )
            car.minutesToFull?.takeIf { it > 0 }?.let { Text(stringResource(Res.string.charging_live_left, durationText(it * 60L)), style = ChargingTheme.type.label, color = colors.textMuted) }
        }
    }
}

@Composable
private fun EmptyCard() {
    val colors = ChargingTheme.colors
    ChargingCard {
        Text(stringResource(Res.string.charging_empty_title), style = ChargingTheme.type.headline, color = colors.text)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(Res.string.charging_empty_body), style = ChargingTheme.type.body, color = colors.textMuted)
    }
}

@Composable
private fun HeroCard(month: ChargingMonth, previous: ChargingMonth?, onOlder: (() -> Unit)?, onNewer: (() -> Unit)?) {
    val colors = ChargingTheme.colors
    ChargingCard(Modifier.testTag("charging_hero")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(monthYearText(month), style = ChargingTheme.type.headline, color = colors.text, modifier = Modifier.weight(1f))
            if (onOlder != null) ChargingRoundButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(Res.string.charging_month_older), onOlder, Modifier.testTag("charging_month_older"), size = 40.dp)
            // Held open on the newest month, so the arrow beside it stays where the thumb left it.
            if (onNewer != null) ChargingRoundButton(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(Res.string.charging_month_newer), onNewer, Modifier.testTag("charging_month_newer"), size = 40.dp) else Spacer(Modifier.size(40.dp))
        }
        Text(moneyText(month.cost), style = ChargingTheme.type.hero, color = colors.text, modifier = Modifier.testTag("charging_month_total"))
        Text(
            pluralStringResource(Res.plurals.charging_month_charges, month.sessions, kwhText(month.kwh), month.sessions),
            style = ChargingTheme.type.label,
            color = colors.textMuted,
        )
        if (previous != null && previous.cost > 0) {
            val change = month.cost - previous.cost
            Text(
                stringResource(if (change >= 0) Res.string.charging_month_up else Res.string.charging_month_down, moneyText(if (change >= 0) change else -change), monthYearText(previous)),
                style = ChargingTheme.type.label,
                color = colors.textFaint,
            )
        }
        Spacer(Modifier.height(14.dp))
        StackedBar(listOf(BarPart(month.homeCost, colors.home), BarPart(month.fastCost, colors.fast), BarPart(month.otherCost, colors.other)))
        Spacer(Modifier.height(10.dp))
        LegendRow(colors.home, stringResource(Res.string.charging_place_home), moneyText(month.homeCost), detail = kwhText(month.homeKwh))
        LegendRow(colors.fast, stringResource(Res.string.charging_place_fast_short), moneyText(month.fastCost), detail = kwhText(month.fastKwh))
        if (month.otherKwh > 0 || month.otherCost > 0) {
            LegendRow(colors.other, stringResource(Res.string.charging_place_other), moneyText(month.otherCost), detail = kwhText(month.otherKwh))
        }
        if (month.unmatched > 0) {
            Spacer(Modifier.height(4.dp))
            Text(stringResource(Res.string.charging_unmatched_note, moneyText(month.unmatched)), style = ChargingTheme.type.label, color = colors.textFaint)
        }
    }
}

@Composable
private fun CarRow(car: CarMonth, month: ChargingMonth) {
    val colors = ChargingTheme.colors
    Column(Modifier.testTag("charging_car_${car.car}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(car.name, style = ChargingTheme.type.headline, color = colors.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(moneyText(car.cost), style = ChargingTheme.type.headline, color = colors.text)
        }
        Spacer(Modifier.height(6.dp))
        StackedBar(listOf(BarPart(car.homeCost, colors.home), BarPart(car.fastCost, colors.fast), BarPart(car.otherCost, colors.other)), height = 6.dp)
        Spacer(Modifier.height(6.dp))
        Text(
            buildList {
                if (car.homeKwh > 0) add(stringResource(Res.string.charging_car_home, kwhText(car.homeKwh), moneyText(car.homeCost)))
                if (car.fastKwh > 0) add(stringResource(Res.string.charging_car_fast, kwhText(car.fastKwh), moneyText(car.fastCost)))
                if (car.otherKwh > 0) add(stringResource(Res.string.charging_car_other, kwhText(car.otherKwh)))
            }.joinToString(" · "),
            style = ChargingTheme.type.label,
            color = colors.textMuted,
        )
        val driving = buildList {
            car.miles?.let { add(milesText(it)) }
            car.costPerMile?.let { add(stringResource(Res.string.charging_car_per_mile, ChargingFormat.fixed(it * 100, 1))) }
            car.milesPerKwh?.let { add(stringResource(Res.string.charging_car_efficiency, ChargingFormat.fixed(it, 2))) }
        }
        if (driving.isNotEmpty()) Text(driving.joinToString(" · "), style = ChargingTheme.type.label, color = colors.textFaint)
    }
}

/** What a kWh came to at home, how much of it fell in peak hours, and what another plan would have made of it. */
@Composable
private fun HomeCard(month: ChargingMonth, summary: ChargingSummary, onSeeRates: () -> Unit) {
    val colors = ChargingTheme.colors
    ChargingCard(Modifier.clickable(role = Role.Button, onClick = onSeeRates).handCursor().testTag("charging_home_card"), title = stringResource(Res.string.charging_home_title)) {
        month.homePerKwh?.let { Text(stringResource(Res.string.charging_home_per_kwh, rateText(it)), style = ChargingTheme.type.bodyStrong, color = colors.text) }
        month.peakShare?.takeIf { it > 0 }?.let {
            Text(stringResource(Res.string.charging_peak_share, ChargingFormat.fixed(it * 100, 0)), style = ChargingTheme.type.label, color = colors.peak)
        }
        val current = summary.plan
        val currentCost = current?.let { month.planCosts[it.id] }
        summary.plans.filter { it.id != current?.id }.forEach { other ->
            val otherCost = month.planCosts[other.id]
            if (currentCost != null && otherCost != null) {
                Spacer(Modifier.height(6.dp))
                PlanLine(other, otherCost, currentCost)
            }
        }
    }
}

@Composable
private fun PlanLine(other: RatePlan, otherCost: Double, currentCost: Double) {
    val colors = ChargingTheme.colors
    val difference = otherCost - currentCost
    val text = when {
        difference < -0.005 -> stringResource(Res.string.charging_plan_compare_less, other.name, moneyText(otherCost), moneyText(-difference))
        difference > 0.005 -> stringResource(Res.string.charging_plan_compare_more, other.name, moneyText(otherCost), moneyText(difference))
        else -> stringResource(Res.string.charging_plan_compare_same, other.name)
    }
    Text(text, style = ChargingTheme.type.body, color = colors.textMuted)
}

@Composable
private fun GasCard(month: ChargingMonth, summary: ChargingSummary) {
    val gas = summary.gas ?: return
    // A charge with no price would count as money saved.
    if (month.unpriced > 0) return
    val miles = month.milesOfEveryCar?.takeIf { it > 0 } ?: return
    val colors = ChargingTheme.colors
    val gasCost = gas.costOf(miles)
    ChargingCard(title = stringResource(Res.string.charging_gas_title)) {
        Text(stringResource(Res.string.charging_gas_cheaper, moneyText(gasCost - month.cost), moneyText(gasCost)), style = ChargingTheme.type.bodyStrong, color = colors.text)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(Res.string.charging_gas_detail, milesText(miles), ChargingFormat.fixed(gas.milesPerGallon, 0), moneyText(gas.pricePerGallon)), style = ChargingTheme.type.label, color = colors.textFaint)
    }
}

@Composable
private fun PaidCard(month: ChargingMonth, summary: ChargingSummary) {
    val paid = month.electricPaid?.takeIf { it > 0 } ?: return
    val colors = ChargingTheme.colors
    ChargingCard(title = stringResource(Res.string.charging_paid_title)) {
        Text(stringResource(Res.string.charging_paid_provider, summary.providerName.orEmpty(), moneyText(paid)), style = ChargingTheme.type.bodyStrong, color = colors.text)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(Res.string.charging_paid_provider_detail), style = ChargingTheme.type.label, color = colors.textFaint)
    }
}

@Composable
private fun HistoryCard(months: List<ChargingMonth>, selected: String, onSelect: (String) -> Unit) {
    val colors = ChargingTheme.colors
    ChargingCard(title = stringResource(Res.string.charging_history_title)) {
        months.forEach { month ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(CircleShape)
                    .clickable(role = Role.Button) { onSelect(month.key) }
                    .handCursor()
                    .padding(vertical = 8.dp)
                    .testTag("charging_history_${month.key}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(monthYearText(month), style = if (month.key == selected) ChargingTheme.type.bodyStrong else ChargingTheme.type.body, color = colors.text, modifier = Modifier.weight(1f))
                // A month known only from the cards has a price and no energy.
                if (month.kwh > 0) Text(kwhText(month.kwh), style = ChargingTheme.type.label, color = colors.textFaint, modifier = Modifier.padding(end = 14.dp))
                Text(moneyText(month.cost), style = ChargingTheme.type.bodyStrong, color = colors.text)
            }
        }
    }
}

/** What the box still owes: only the parts not done. */
@Composable
internal fun SetupCard(setup: ChargingSetup) {
    val colors = ChargingTheme.colors
    ChargingCard(Modifier.testTag("charging_setup"), title = stringResource(Res.string.charging_setup_title)) {
        Text(stringResource(Res.string.charging_setup_body), style = ChargingTheme.type.body, color = colors.textMuted)
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!setup.rates) SetupLine(stringResource(Res.string.charging_setup_rates))
            if (!setup.powerStream) SetupLine(stringResource(Res.string.charging_setup_power))
            if (!setup.receipts) SetupLine(stringResource(Res.string.charging_setup_receipts))
        }
    }
}

@Composable
private fun SetupLine(text: String) {
    val colors = ChargingTheme.colors
    Row {
        Box(Modifier.padding(top = 7.dp).size(6.dp).clip(CircleShape).background(colors.peak))
        Spacer(Modifier.width(10.dp))
        Text(text, style = ChargingTheme.type.body, color = colors.text)
    }
}
