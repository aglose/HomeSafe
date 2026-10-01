package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.domain.model.HouseholdPresence
import com.meticulouscreations.homesafe.domain.model.PresenceDevice
import com.meticulouscreations.homesafe.ui.theme.FrigateTheme
import com.meticulouscreations.homesafe.viewmodel.SettingsUiState

private val previewPixel = PresenceDevice(
    name = "Google Pixel 10 Pro XL",
    platform = "android",
    away = false,
    isThisDevice = true,
    id = "pixel",
    build = "release",
)

private val previewIphone = PresenceDevice(name = "Apple iPhone", platform = "ios", away = false, countsForAway = false, id = "iphone", build = "debug")

/** The household as the relay tells it once one phone decides: [decider] alone counts. */
private fun previewPresence(decider: String) = HouseholdPresence(
    devices = listOf(previewPixel, previewIphone).map { it.copy(decides = it.id == decider, countsForAway = it.id == decider) },
    everyoneAway = false,
    authorityDeviceId = decider,
)

/** Away mode with this phone deciding home/away, and with the other phone deciding instead. */
@Preview(name = "Away mode · who decides", widthDp = 412)
@Composable
private fun AwaySectionDecidesPreview() {
    FrigateTheme {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            listOf("pixel", "iphone").forEach { decider ->
                AwaySection(
                    state = SettingsUiState(presence = previewPresence(decider)),
                    onAway = {},
                    onDecides = {},
                    onAutomatic = {},
                    onRequestLocation = {},
                    onSetHomeHere = {},
                    onClearHome = {},
                    onRemoveDevice = {},
                )
            }
        }
    }
}
