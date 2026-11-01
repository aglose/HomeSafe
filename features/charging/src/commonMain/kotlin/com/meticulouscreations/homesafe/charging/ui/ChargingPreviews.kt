package com.meticulouscreations.homesafe.charging.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.charging.ChargingUiState
import com.meticulouscreations.homesafe.charging.domain.ChargingSetup
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.ui.theme.FrigateTheme
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.charging_error_unreachable

// Whole screens from the made-up household in ChargingFixtures.
@Preview(widthDp = 412, heightDp = 1950)
@Composable
private fun ChargingOverviewPreview() {
    FrigateTheme { ChargingAppContent(ChargingFixtures.state(), ChargingActions()) }
}

// With no one charging, and with the receipts and the power readings still owed.
@Preview(widthDp = 412, heightDp = 1700)
@Composable
private fun ChargingOverviewSettingUpPreview() {
    val summary = ChargingFixtures.summary.copy(now = emptyList(), setup = ChargingSetup(powerStream = false, receipts = false))
    FrigateTheme { ChargingAppContent(ChargingFixtures.state(summary), ChargingActions()) }
}

// The months from before the relay was listening: Superchargers are all they can say.
@Preview(widthDp = 412, heightDp = 1100)
@Composable
private fun ChargingOverviewSuperchargersOnlyPreview() {
    val summary = ChargingFixtures.summary.run { copy(now = emptyList(), months = months.filter { !it.homeRecorded }) }
    FrigateTheme { ChargingAppContent(ChargingFixtures.state(summary), ChargingActions()) }
}

@Preview(widthDp = 412, heightDp = 1700)
@Composable
private fun ChargingCarPreview() {
    FrigateTheme { ChargingAppContent(ChargingFixtures.state(), ChargingActions(), initialPage = ChargingPage.Car("car_a")) }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun ChargingFirstRunPreview() {
    FrigateTheme { ChargingAppContent(ChargingFixtures.emptyState, ChargingActions()) }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun ChargingChargesPreview() {
    FrigateTheme { ChargingAppContent(ChargingFixtures.state(), ChargingActions(), initialTab = ChargingTab.CHARGES) }
}

@Preview(widthDp = 412, heightDp = 1500)
@Composable
private fun ChargingRatesPreview() {
    FrigateTheme { ChargingAppContent(ChargingFixtures.state(), ChargingActions(), initialTab = ChargingTab.RATES) }
}

@Preview(widthDp = 412, heightDp = 1250)
@Composable
private fun ChargingChargePreview() {
    FrigateTheme { ChargingAppContent(ChargingFixtures.state(detail = ChargingFixtures.detail), ChargingActions(), initialPage = ChargingPage.Charge(ChargingFixtures.detail.session.id)) }
}

// A Supercharger's receipt, with an idle fee the price per kWh leaves out.
@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun ChargingSuperchargerChargePreview() {
    val detail = ChargingFixtures.detail.copy(session = ChargingFixtures.sessions.first { it.idleFee > 0 }, hours = emptyList(), plans = emptyMap())
    FrigateTheme { ChargingAppContent(ChargingFixtures.state(detail = detail), ChargingActions(), initialPage = ChargingPage.Charge(detail.session.id)) }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun ChargingUnreachablePreview() {
    FrigateTheme { ChargingAppContent(ChargingUiState(error = UiText.of(Res.string.charging_error_unreachable)), ChargingActions()) }
}

@Preview(name = "Drawer, Charging", widthDp = 320)
@Composable
private fun ChargingDrawerCardPreview() {
    FrigateTheme {
        Column(Modifier.width(320.dp).padding(12.dp)) {
            ChargingDrawerCard(ChargingFixtures.state(), animated = false, onOpen = {})
        }
    }
}

@Preview(name = "Charging, an error over what's loaded", widthDp = 390, heightDp = 844)
@Composable
private fun ChargingLoadedErrorPreview() {
    FrigateTheme { ChargingAppContent(ChargingFixtures.state().copy(error = UiText.of(Res.string.charging_error_unreachable)), ChargingActions(), initialTab = ChargingTab.RATES) }
}
