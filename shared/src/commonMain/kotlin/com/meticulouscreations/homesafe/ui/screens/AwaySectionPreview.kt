package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.domain.model.HouseholdPresence
import com.meticulouscreations.homesafe.domain.model.NotTrackedReason
import com.meticulouscreations.homesafe.domain.model.PresenceDevice
import com.meticulouscreations.homesafe.domain.platform.LocationAccess
import com.meticulouscreations.homesafe.ui.theme.FrigateTheme
import com.meticulouscreations.homesafe.viewmodel.SettingsUiState

private val previewPixel = PresenceDevice(
    name = "Google Pixel 10 Pro XL",
    platform = "android",
    away = true,
    updatedEpochSeconds = 1_732_650_000.0,
    isThisDevice = true,
    id = "pixel",
)

private val previewIphone = PresenceDevice(name = "Apple iPhone", platform = "ios", away = false, pendingAway = true, id = "iphone")

/**
 * Away mode from the Pixel while the iPhone is on its way out, with location still "while in use";
 * then from a debug build, which the relay doesn't track, before any iPhone has joined.
 */
@Preview(name = "Away mode · tracked and not", widthDp = 412)
@Composable
private fun AwaySectionTrackedPreview() {
    val states = listOf(
        SettingsUiState(
            presence = HouseholdPresence(listOf(previewPixel, previewIphone), everyoneAway = false),
            geofenceSupported = true,
            locationAccess = LocationAccess.WHILE_IN_USE,
        ),
        SettingsUiState(
            presence = HouseholdPresence(
                listOf(previewPixel.copy(isThisDevice = false, away = false)),
                everyoneAway = false,
                thisDeviceNotTracked = NotTrackedReason.DEBUG_BUILD,
            ),
            geofenceSupported = true,
            locationAccess = LocationAccess.ALWAYS,
        ),
    )
    FrigateTheme {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            states.forEach { state ->
                AwaySection(
                    state = state,
                    onAway = {},
                    onRequestLocation = {},
                    onSetHomeHere = {},
                    onClearHome = {},
                    onRemoveDevice = {},
                )
            }
        }
    }
}
