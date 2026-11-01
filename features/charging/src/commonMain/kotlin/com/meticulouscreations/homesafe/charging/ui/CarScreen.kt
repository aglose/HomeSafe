package com.meticulouscreations.homesafe.charging.ui

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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.charging.ChargingUiState
import com.meticulouscreations.homesafe.charging.domain.CarMonth
import com.meticulouscreations.homesafe.charging.domain.ChargingMonth
import com.meticulouscreations.homesafe.ui.handCursor
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.charging_car_efficiency
import homesafe.shared.generated.resources.charging_car_home_from
import homesafe.shared.generated.resources.charging_car_idle_note
import homesafe.shared.generated.resources.charging_car_overall
import homesafe.shared.generated.resources.charging_car_per_mile
import homesafe.shared.generated.resources.charging_car_see_charges
import homesafe.shared.generated.resources.charging_car_since
import homesafe.shared.generated.resources.charging_car_totals_title
import homesafe.shared.generated.resources.charging_charges_empty
import homesafe.shared.generated.resources.charging_history_title
import homesafe.shared.generated.resources.charging_month_charges
import homesafe.shared.generated.resources.charging_month_fast_only
import homesafe.shared.generated.resources.charging_month_fast_only_note
import homesafe.shared.generated.resources.charging_months_title
import homesafe.shared.generated.resources.charging_per_mile_note
import homesafe.shared.generated.resources.charging_per_mile_title
import homesafe.shared.generated.resources.charging_place_fast_short
import homesafe.shared.generated.resources.charging_place_fast_tiny
import homesafe.shared.generated.resources.charging_place_home
import homesafe.shared.generated.resources.charging_place_other
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** One car, month by month: what it has come to so far, what a kWh and a mile cost it, and the way to its charges. */
@Composable
internal fun CarScreen(state: ChargingUiState, car: String, padding: PaddingValues, onSeeCharges: () -> Unit) {
    val colors = ChargingTheme.colors
    val months = state.summary.monthsOf(car)
    val all = months.flatMap { it.cars }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).testTag("charging_car_page"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val total = all.reduceOrNull(CarMonth::plus)
        if (total == null) {
            Text(stringResource(Res.string.charging_charges_empty), style = ChargingTheme.type.body, color = colors.textMuted, modifier = Modifier.padding(vertical = 24.dp))
        } else {
            // Miles are only known from when the relay began listening, so what they are set against is those months' alone.
            // Where the car has also said what it used, only those months: a mile with no energy would make it look thrifty.
            val driven = all.filter { it.miles != null && (it.usedKwh ?: 0.0) > 0 }.reduceOrNull(CarMonth::plus)
                ?: all.filter { it.miles != null }.reduceOrNull(CarMonth::plus)
            TotalsCard(total, driven, months)
            if (months.size > 1) {
                ChargingCard(title = stringResource(Res.string.charging_months_title)) {
                    MonthBars(months, selected = null)
                    FadedMonthsNote(months)
                }
            }
            driven?.let { PerMileCard(it.homePerMile, it.fastPerMile, it.otherPerMile) }
            ChargingCard(title = stringResource(Res.string.charging_history_title)) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { months.forEach { CarMonthRow(it) } }
            }
        }
        TextButton(onClick = onSeeCharges, modifier = Modifier.align(Alignment.CenterHorizontally).handCursor().testTag("charging_car_see_charges")) {
            Text(stringResource(Res.string.charging_car_see_charges), color = colors.home)
        }
    }
}

@Composable
private fun TotalsCard(total: CarMonth, driven: CarMonth?, months: List<ChargingMonth>) {
    val colors = ChargingTheme.colors
    ChargingCard(title = stringResource(Res.string.charging_car_totals_title)) {
        Text(moneyText(total.cost), style = ChargingTheme.type.hero, color = colors.text, modifier = Modifier.testTag("charging_car_total"))
        if (total.sessions > 0) {
            Text(pluralStringResource(Res.plurals.charging_month_charges, total.sessions, kwhText(total.kwh), total.sessions), style = ChargingTheme.type.label, color = colors.textMuted)
        }
        months.lastOrNull()?.let { Text(stringResource(Res.string.charging_car_since, monthYearText(it)), style = ChargingTheme.type.label, color = colors.textFaint) }
        if (months.any { !it.homeRecorded }) {
            val firstRecorded = months.lastOrNull { it.homeRecorded }
            Text(
                if (firstRecorded != null) stringResource(Res.string.charging_car_home_from, monthYearText(firstRecorded)) else stringResource(Res.string.charging_month_fast_only_note),
                style = ChargingTheme.type.label,
                color = colors.peak,
            )
        }
        Spacer(Modifier.height(12.dp))
        CarPlaces(total)
        if (driven != null) CarDriving(driven)
    }
}

@Composable
private fun CarMonthRow(month: ChargingMonth) {
    val colors = ChargingTheme.colors
    val car = month.cars.firstOrNull() ?: return
    Column(Modifier.testTag("charging_car_month_${month.key}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(monthYearText(month), style = ChargingTheme.type.bodyStrong, color = colors.text, modifier = Modifier.weight(1f))
            Text(moneyText(car.cost), style = ChargingTheme.type.bodyStrong, color = colors.text)
        }
        val detail = buildList {
            if (car.kwh > 0) add(kwhText(car.kwh))
            car.perKwh?.let { add(rateText(it)) }
            if (!month.homeRecorded) add(stringResource(Res.string.charging_month_fast_only))
        }
        if (detail.isNotEmpty()) Text(detail.joinToString(" · "), style = ChargingTheme.type.label, color = colors.textMuted)
        CarDriving(car, withOverall = false)
    }
}

/** A car's charging at each place: how much, what a kWh of it came to, and what that was in all. */
@Composable
internal fun CarPlaces(car: CarMonth) {
    val colors = ChargingTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (car.homeKwh > 0) PlaceLine(colors.home, stringResource(Res.string.charging_place_home), car.homeKwh, car.homePerKwh, car.homeCost)
        if (car.fastKwh > 0) PlaceLine(colors.fast, stringResource(Res.string.charging_place_fast_tiny), car.fastKwh, car.fastPerKwh, car.fastCost)
        if (car.otherKwh > 0) PlaceLine(colors.other, stringResource(Res.string.charging_place_other), car.otherKwh, car.otherPerKwh, car.otherCost)
        if (car.fastIdle > 0) {
            Text(stringResource(Res.string.charging_car_idle_note, moneyText(car.fastIdle)), style = ChargingTheme.type.label, color = colors.textFaint, modifier = Modifier.padding(top = 1.dp))
        }
    }
}

@Composable
private fun PlaceLine(color: Color, label: String, kwh: Double, rate: Double?, cost: Double) {
    val colors = ChargingTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(label, style = ChargingTheme.type.label, color = colors.textMuted, modifier = Modifier.weight(0.9f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(kwhText(kwh), style = ChargingTheme.type.label, color = colors.textMuted, textAlign = TextAlign.End, modifier = Modifier.weight(0.8f), maxLines = 1)
        // Energy with no price yet isn't free: no rate, rather than one that says so.
        Text(rate?.let { rateText(it) }.orEmpty(), style = ChargingTheme.type.label, color = colors.text, textAlign = TextAlign.End, modifier = Modifier.weight(1.15f), maxLines = 1)
        Text(moneyText(cost), style = ChargingTheme.type.label, color = colors.textMuted, textAlign = TextAlign.End, modifier = Modifier.weight(0.8f), maxLines = 1)
    }
}

/** How far a car went on it, and what a kWh and a mile came to overall. Nothing until there's something to say. */
@Composable
internal fun CarDriving(car: CarMonth, withOverall: Boolean = true) {
    val colors = ChargingTheme.colors
    val places = listOf(car.homePricedKwh, car.fastPricedKwh, car.otherPricedKwh).count { it > 0 }
    val driving = buildList {
        car.miles?.let { add(milesText(it)) }
        car.costPerMile?.let { add(perMileText(it)) }
        car.milesPerKwh?.let { add(stringResource(Res.string.charging_car_efficiency, ChargingFormat.fixed(it, 2))) }
        // With one place, its own line already says it.
        car.perKwh?.takeIf { withOverall && places > 1 }?.let { add(stringResource(Res.string.charging_car_overall, rateText(it))) }
    }
    if (driving.isNotEmpty()) Text(driving.joinToString(" · "), style = ChargingTheme.type.label, color = colors.textFaint, modifier = Modifier.padding(top = 4.dp))
}

/**
 * What a mile costs by where its energy came from. A kWh at home is counted at the wall and a fast
 * charger's nearer the battery, so their prices per kWh alone flatter home; a mile is the same mile
 * either way. Only said with two places to set side by side.
 */
@Composable
internal fun PerMileCard(home: Double?, fast: Double?, other: Double?) {
    if (listOfNotNull(home, fast, other).size < 2) return
    val colors = ChargingTheme.colors
    ChargingCard(Modifier.testTag("charging_per_mile"), title = stringResource(Res.string.charging_per_mile_title)) {
        home?.let { LegendRow(colors.home, stringResource(Res.string.charging_place_home), perMileText(it)) }
        fast?.let { LegendRow(colors.fast, stringResource(Res.string.charging_place_fast_short), perMileText(it)) }
        other?.let { LegendRow(colors.other, stringResource(Res.string.charging_place_other), perMileText(it)) }
        Spacer(Modifier.height(4.dp))
        Text(stringResource(Res.string.charging_per_mile_note), style = ChargingTheme.type.label, color = colors.textFaint)
    }
}

/** "7.2¢ a mile". */
@Composable
private fun perMileText(dollars: Double): String = stringResource(Res.string.charging_car_per_mile, ChargingFormat.fixed(dollars * 100, 1))

/** "196 kWh · $0.280/kWh", or as much of it as is known; null with no energy. */
@Composable
internal fun placeDetail(kwh: Double, rate: Double?): String? = kwh.takeIf { it > 0 }?.let { energy ->
    listOfNotNull(kwhText(energy), rate?.let { rateText(it) }).joinToString(" · ")
}
