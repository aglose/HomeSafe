package com.meticulouscreations.homesafe.charging.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.charging.ChargingUiState
import com.meticulouscreations.homesafe.charging.domain.ChargingSummary
import com.meticulouscreations.homesafe.charging.domain.RatePlan
import com.meticulouscreations.homesafe.charging.domain.RatePrice
import com.meticulouscreations.homesafe.ui.handCursor
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.charging_baseline
import homesafe.shared.generated.resources.charging_rates_billed_note
import homesafe.shared.generated.resources.charging_rates_customer_charge
import homesafe.shared.generated.resources.charging_rates_day_chart
import homesafe.shared.generated.resources.charging_rates_effective
import homesafe.shared.generated.resources.charging_rates_marginal
import homesafe.shared.generated.resources.charging_rates_none
import homesafe.shared.generated.resources.charging_rates_open_source
import homesafe.shared.generated.resources.charging_rates_peak_hours
import homesafe.shared.generated.resources.charging_rates_plan_note
import homesafe.shared.generated.resources.charging_rates_plan_title
import homesafe.shared.generated.resources.charging_rates_stated
import homesafe.shared.generated.resources.charging_rates_tou_only_note
import homesafe.shared.generated.resources.charging_rates_when_chart
import homesafe.shared.generated.resources.charging_rates_when_peak
import homesafe.shared.generated.resources.charging_rates_when_title
import org.jetbrains.compose.resources.stringResource

/** Who supplies the electricity, each plan's rate at each hour, and when the charging at home actually happens. */
@Composable
internal fun RatesScreen(state: ChargingUiState, padding: PaddingValues, onChoosePlan: (String) -> Unit) {
    val colors = ChargingTheme.colors
    val summary = state.summary
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).testTag("charging_rates"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (summary.plans.isEmpty()) {
            ChargingCard { Text(stringResource(Res.string.charging_rates_none), style = ChargingTheme.type.body, color = colors.textMuted) }
        } else {
            PlanChooser(summary, state.saving, onChoosePlan)
            summary.plans.forEach { PricesCard(it) }
        }
        if (summary.byHour.any { it > 0 }) WhenCard(summary)
        if (!summary.setup.complete) SetupCard(summary.setup)
    }
}

@Composable
private fun PlanChooser(summary: ChargingSummary, saving: Boolean, onChoosePlan: (String) -> Unit) {
    val colors = ChargingTheme.colors
    val uriHandler = LocalUriHandler.current
    ChargingCard(title = stringResource(Res.string.charging_rates_plan_title)) {
        summary.providerName?.let { Text(it, style = ChargingTheme.type.headline, color = colors.text) }
        Spacer(Modifier.height(4.dp))
        Text(stringResource(Res.string.charging_rates_plan_note), style = ChargingTheme.type.label, color = colors.textFaint)
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            summary.plans.forEach { plan ->
                val shape = RoundedCornerShape(14.dp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(if (plan.current) colors.home.copy(alpha = 0.14f) else colors.surfaceRaised)
                        .border(1.dp, if (plan.current) colors.home.copy(alpha = 0.6f) else colors.hairline, shape)
                        .clickable(enabled = !saving && !plan.current, role = Role.RadioButton) { onChoosePlan(plan.id) }
                        .handCursor(!plan.current)
                        .semantics { selected = plan.current }
                        .padding(12.dp)
                        .testTag("charging_plan_${plan.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (plan.current) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, contentDescription = null, tint = if (plan.current) colors.home else colors.textFaint, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.size(10.dp))
                    Text(plan.name, style = ChargingTheme.type.bodyStrong, color = colors.text)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(billedNote(summary), style = ChargingTheme.type.label, color = colors.textFaint)
        summary.customerCharge?.let {
            Spacer(Modifier.height(4.dp))
            Text(stringResource(Res.string.charging_rates_customer_charge, moneyText(it)), style = ChargingTheme.type.label, color = colors.textFaint)
        }
        val url = summary.providerUrl
        if (url != null) {
            TextButton(onClick = { runCatching { uriHandler.openUri(url) } }, modifier = Modifier.handCursor()) {
                Text(stringResource(Res.string.charging_rates_open_source), color = colors.home)
            }
        }
    }
}

/** A plan's rates and, where they change through the day, what a kWh costs at each hour of a weekday. */
@Composable
private fun PricesCard(plan: RatePlan) {
    val colors = ChargingTheme.colors
    val uriHandler = LocalUriHandler.current
    ChargingCard(Modifier.testTag("charging_prices_${plan.id}"), title = plan.name) {
        plan.prices.forEach { PriceRow(it) }
        val start = plan.peakStart
        val end = plan.peakEnd
        if (plan.isTimeOfUse && start != null && end != null) {
            Spacer(Modifier.height(12.dp))
            val top = plan.weekday.maxOrNull() ?: 0.0
            val low = plan.weekday.minOrNull() ?: 0.0
            HourBars(
                plan.weekday,
                stringResource(Res.string.charging_rates_day_chart, rateText(low), rateText(top)),
                valueText = { rateText(it) },
                height = 72.dp,
                color = { hour -> if (hour in start until end) colors.peak else colors.home },
            )
            Spacer(Modifier.height(10.dp))
            Text(stringResource(Res.string.charging_rates_peak_hours, hourText(start), hourText(end)), style = ChargingTheme.type.label, color = colors.peak)
        }
        if (!plan.isTimeOfUse) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(Res.string.charging_rates_marginal), style = ChargingTheme.type.label, color = colors.textFaint)
            val summer = plan.baselineSummer
            val winter = plan.baselineWinter
            if (summer != null && winter != null) {
                Spacer(Modifier.height(4.dp))
                Text(stringResource(Res.string.charging_baseline, summer.toString(), winter.toString()), style = ChargingTheme.type.label, color = colors.textFaint)
            }
        } else {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(Res.string.charging_rates_tou_only_note), style = ChargingTheme.type.label, color = colors.textFaint)
        }
        plan.effective?.let {
            Spacer(Modifier.height(4.dp))
            Text(stringResource(Res.string.charging_rates_effective, it), style = ChargingTheme.type.label, color = colors.textFaint)
        }
        plan.url?.let { url ->
            TextButton(onClick = { runCatching { uriHandler.openUri(url) } }, modifier = Modifier.handCursor()) {
                Text(stringResource(Res.string.charging_rates_open_source), color = colors.home)
            }
        }
    }
}

@Composable
private fun billedNote(summary: ChargingSummary): String =
    stringResource(Res.string.charging_rates_billed_note, ChargingFormat.fixed(summary.taxPercent ?: 0.0, 1), ChargingFormat.fixed((summary.surchargePerKwh ?: 0.0) * 100, 2))

@Composable
private fun PriceRow(price: RatePrice) {
    val colors = ChargingTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(colors.period(price.period)))
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(price.period.label), style = ChargingTheme.type.body, color = colors.textMuted)
            Text(stringResource(Res.string.charging_rates_stated, ChargingFormat.fixed(price.rate, 5)), style = ChargingTheme.type.label, color = colors.textFaint)
        }
        Text(rateText(price.billed), style = ChargingTheme.type.headline, color = colors.text)
    }
}

/** When the charging at home goes in, with the peak's hours in the peak's colour. */
@Composable
private fun WhenCard(summary: ChargingSummary) {
    val colors = ChargingTheme.colors
    val tou = summary.plans.firstOrNull { it.isTimeOfUse }
    val start = tou?.peakStart
    val end = tou?.peakEnd
    val share = summary.recentPeakShare
    ChargingCard(title = stringResource(Res.string.charging_rates_when_title)) {
        HourBars(
            summary.byHour,
            stringResource(Res.string.charging_rates_when_chart),
            valueText = { kwhText(it) },
            color = { hour -> if (start != null && end != null && hour in start until end) colors.peak else colors.home },
        )
        if (share != null) {
            Spacer(Modifier.height(10.dp))
            Text(stringResource(Res.string.charging_rates_when_peak, ChargingFormat.fixed(share * 100, 0)), style = ChargingTheme.type.label, color = if (share > 0) colors.peak else colors.textMuted)
        }
    }
}
