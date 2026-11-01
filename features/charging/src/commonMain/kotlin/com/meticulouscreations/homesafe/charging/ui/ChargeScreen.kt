package com.meticulouscreations.homesafe.charging.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.charging.ChargingUiState
import com.meticulouscreations.homesafe.charging.domain.ChargeCostSource
import com.meticulouscreations.homesafe.charging.domain.ChargeMeasure
import com.meticulouscreations.homesafe.charging.domain.ChargePlace
import com.meticulouscreations.homesafe.charging.domain.ChargeSession
import com.meticulouscreations.homesafe.charging.domain.ChargeSessionDetail
import com.meticulouscreations.homesafe.charging.domain.ChargingSummary
import com.meticulouscreations.homesafe.charging.domain.RatePeriod
import com.meticulouscreations.homesafe.text.resolve
import com.meticulouscreations.homesafe.ui.handCursor
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.charging_battery_range
import homesafe.shared.generated.resources.charging_charge_avg_kw
import homesafe.shared.generated.resources.charging_charge_battery
import homesafe.shared.generated.resources.charging_charge_chart
import homesafe.shared.generated.resources.charging_charge_cost_plan
import homesafe.shared.generated.resources.charging_charge_cost_receipt
import homesafe.shared.generated.resources.charging_charge_cost_typed
import homesafe.shared.generated.resources.charging_charge_duration
import homesafe.shared.generated.resources.charging_charge_energy
import homesafe.shared.generated.resources.charging_charge_hours_title
import homesafe.shared.generated.resources.charging_charge_loading
import homesafe.shared.generated.resources.charging_charge_measure_battery
import homesafe.shared.generated.resources.charging_charge_measure_missed
import homesafe.shared.generated.resources.charging_charge_measure_power
import homesafe.shared.generated.resources.charging_charge_measure_receipt
import homesafe.shared.generated.resources.charging_charge_no_price
import homesafe.shared.generated.resources.charging_charge_note
import homesafe.shared.generated.resources.charging_charge_paid
import homesafe.shared.generated.resources.charging_charge_paid_note
import homesafe.shared.generated.resources.charging_charge_peak_kw
import homesafe.shared.generated.resources.charging_charge_plans_title
import homesafe.shared.generated.resources.charging_charge_price
import homesafe.shared.generated.resources.charging_charge_save
import homesafe.shared.generated.resources.charging_charge_saved_title
import homesafe.shared.generated.resources.charging_charge_when
import homesafe.shared.generated.resources.charging_money
import org.jetbrains.compose.resources.stringResource

/** One charge: what it cost and how that was worked out, hour by hour, and a place to say what was paid. */
@Composable
internal fun ChargeScreen(state: ChargingUiState, id: Long, padding: PaddingValues, onSave: (Long, Double?, String?) -> Unit) {
    val colors = ChargingTheme.colors
    val detail = state.detail?.takeIf { it.session.id == id }
    // The list's copy stands in for a moment while the hours are fetched.
    val session = detail?.session ?: state.sessions.firstOrNull { it.id == id }
    if (session == null) {
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            Text(state.error?.resolve() ?: stringResource(Res.string.charging_charge_loading), style = ChargingTheme.type.body, color = colors.textMuted, textAlign = TextAlign.Center)
        }
        return
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).testTag("charging_charge_page"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SummaryCard(session, state.nowEpochSeconds)
        if (detail != null && detail.hours.isNotEmpty()) HoursCard(detail)
        if (detail != null && detail.plans.isNotEmpty()) PlansCard(detail, state.summary)
        // Tesla's receipt says what a Supercharger cost; everywhere else what was paid can be typed in.
        if (session.costSource != ChargeCostSource.RECEIPT) {
            // Keyed so a saved charge's answer, or the next charge's page, starts the fields again.
            key(session.id, session.cost, session.note) {
                EditCard(session, state.saving, onSave)
            }
        } else if (session.note != null) {
            ChargingCard(title = stringResource(Res.string.charging_charge_note)) { Text(session.note, style = ChargingTheme.type.body, color = colors.text) }
        }
    }
}

@Composable
private fun SummaryCard(session: ChargeSession, nowEpochSeconds: Long) {
    val colors = ChargingTheme.colors
    val placeColor = colors.place(session.place)
    ChargingCard {
        Text(session.site ?: stringResource(session.place.label), style = ChargingTheme.type.headline, color = placeColor)
        Text(stringResource(Res.string.charging_charge_when, session.carName, dayText(session.startedEpochSeconds), timeText(session.startedEpochSeconds)), style = ChargingTheme.type.label, color = colors.textMuted)
        Spacer(Modifier.height(10.dp))
        val cost = session.cost
        Text(if (cost != null) moneyText(cost) else stringResource(Res.string.charging_charge_no_price), style = ChargingTheme.type.hero, color = colors.text, modifier = Modifier.testTag("charging_charge_cost"))
        when (session.costSource) {
            ChargeCostSource.PLAN -> stringResource(Res.string.charging_charge_cost_plan)
            ChargeCostSource.RECEIPT -> stringResource(Res.string.charging_charge_cost_receipt)
            ChargeCostSource.TYPED -> stringResource(Res.string.charging_charge_cost_typed)
            null -> null
        }?.let { Text(it, style = ChargingTheme.type.label, color = colors.textFaint) }
        val from = session.socStart
        val to = session.socEnd
        if (from != null && to != null && to >= from) {
            Spacer(Modifier.height(14.dp))
            BatteryBar(from, to, placeColor)
        }
        Spacer(Modifier.height(14.dp))
        StatRow(stringResource(Res.string.charging_charge_energy), kwhText(session.kwh))
        val seconds = session.durationSeconds(nowEpochSeconds)
        StatRow(stringResource(Res.string.charging_charge_duration), durationText(seconds))
        if (from != null && to != null && to >= from) StatRow(stringResource(Res.string.charging_charge_battery), stringResource(Res.string.charging_battery_range, from.toString(), to.toString()))
        session.pricePerKwh?.let { StatRow(stringResource(Res.string.charging_charge_price), rateText(it)) }
        if (seconds > 0 && session.kwh > 0) StatRow(stringResource(Res.string.charging_charge_avg_kw), ChargingFormat.fixed(session.kwh / (seconds / 3600.0), 1))
        session.peakKw?.let { StatRow(stringResource(Res.string.charging_charge_peak_kw), ChargingFormat.fixed(it, 1)) }
        Spacer(Modifier.height(10.dp))
        val measure = when {
            session.missed -> Res.string.charging_charge_measure_missed
            session.measure == ChargeMeasure.POWER -> Res.string.charging_charge_measure_power
            session.measure == ChargeMeasure.RECEIPT -> Res.string.charging_charge_measure_receipt
            else -> Res.string.charging_charge_measure_battery
        }
        Text(stringResource(measure), style = ChargingTheme.type.label, color = colors.textFaint)
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    val colors = ChargingTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = ChargingTheme.type.body, color = colors.textMuted, modifier = Modifier.weight(1f))
        Text(value, style = ChargingTheme.type.bodyStrong, color = colors.text)
    }
}

/** The charge's hours on a day's 24: when it went in, in the colour of the rate that hour. */
@Composable
private fun HoursCard(detail: ChargeSessionDetail) {
    val colors = ChargingTheme.colors
    val hours = detail.hours
    ChargingCard(title = stringResource(Res.string.charging_charge_hours_title)) {
        hours.forEach { hour ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(timeText(hour.epochSeconds), style = ChargingTheme.type.label, color = colors.textMuted, modifier = Modifier.weight(0.9f))
                val highest = hours.maxOf { it.kwh }.takeIf { it > 0 } ?: 1.0
                Box(Modifier.weight(1.2f)) {
                    StackedBar(listOf(BarPart(hour.kwh, colors.period(hour.period)), BarPart(highest - hour.kwh, colors.surfaceRaised)), height = 8.dp)
                }
                Spacer(Modifier.padding(start = 10.dp))
                Text(kwhText(hour.kwh), style = ChargingTheme.type.label, color = colors.textMuted, textAlign = TextAlign.End, modifier = Modifier.weight(0.8f))
                val price = hour.price
                Text(
                    if (price != null) stringResource(Res.string.charging_money, ChargingFormat.fixed(price * hour.kwh)) else "",
                    style = ChargingTheme.type.bodyStrong,
                    color = if (hour.period == RatePeriod.PEAK) colors.peak else colors.text,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(0.7f),
                )
            }
        }
        if (hours.any { it.period == RatePeriod.PEAK }) {
            Spacer(Modifier.height(6.dp))
            Text(stringResource(Res.string.charging_charge_chart), style = ChargingTheme.type.label, color = colors.textFaint)
        }
    }
}

@Composable
private fun PlansCard(detail: ChargeSessionDetail, summary: ChargingSummary) {
    if (detail.session.place != ChargePlace.HOME) return
    val colors = ChargingTheme.colors
    ChargingCard(title = stringResource(Res.string.charging_charge_plans_title)) {
        summary.plans.forEach { plan ->
            detail.plans[plan.id]?.let { cost ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(plan.name, style = if (plan.current) ChargingTheme.type.bodyStrong else ChargingTheme.type.body, color = colors.text, modifier = Modifier.weight(1f))
                    Text(moneyText(cost), style = ChargingTheme.type.bodyStrong, color = colors.text)
                }
            }
        }
    }
}

/** What was actually paid, and a note. An empty price takes a typed one away. */
@Composable
private fun EditCard(session: ChargeSession, saving: Boolean, onSave: (Long, Double?, String?) -> Unit) {
    val colors = ChargingTheme.colors
    val typed = session.cost?.takeIf { session.costSource == ChargeCostSource.TYPED }
    var costText by rememberSaveable { mutableStateOf(typed?.let { ChargingFormat.fixed(it).replace(",", "") }.orEmpty()) }
    var note by rememberSaveable { mutableStateOf(session.note.orEmpty()) }
    val cost = costText.trim().removePrefix("$").toDoubleOrNull()
    val valid = costText.isBlank() || (cost != null && cost >= 0)
    val changed = (if (costText.isBlank()) null else cost) != typed || note.trim() != session.note.orEmpty()
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = colors.text,
        unfocusedTextColor = colors.text,
        focusedBorderColor = colors.home,
        unfocusedBorderColor = colors.hairline,
        focusedLabelColor = colors.home,
        unfocusedLabelColor = colors.textFaint,
        cursorColor = colors.home,
    )
    ChargingCard(title = stringResource(if (session.place == ChargePlace.HOME && typed == null) Res.string.charging_charge_note else Res.string.charging_charge_saved_title)) {
        // A charge at home is on the electric bill, at the plan's price: there is nothing else it was paid.
        if (session.place != ChargePlace.HOME || typed != null) {
            OutlinedTextField(
                value = costText,
                onValueChange = { costText = it },
                label = { Text(stringResource(Res.string.charging_charge_paid)) },
                supportingText = { Text(stringResource(Res.string.charging_charge_paid_note)) },
                isError = !valid,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth().testTag("charging_charge_cost_field"),
            )
            Spacer(Modifier.height(8.dp))
        }
        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            label = { Text(stringResource(Res.string.charging_charge_note)) },
            colors = fieldColors,
            modifier = Modifier.fillMaxWidth().testTag("charging_charge_note_field"),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(
                onClick = { onSave(session.id, if (costText.isBlank()) null else cost, note.trim().ifEmpty { null }) },
                enabled = valid && changed && !saving,
                modifier = Modifier.handCursor().testTag("charging_charge_save"),
            ) {
                Text(stringResource(Res.string.charging_charge_save), color = if (valid && changed && !saving) colors.home else colors.textFaint)
            }
        }
    }
}
