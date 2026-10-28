package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.domain.model.ParkingAlert
import com.meticulouscreations.homesafe.ui.theme.FrigateTheme
import com.meticulouscreations.homesafe.weather.ui.NoTiles

/*
 * The map a parking notification opens, for a sweeping the next morning and for a time limit that
 * has run out. The street and the spot are made up: the real ones would give the house away.
 * No tiles come in a preview, so the map is its open-land colour with the car's mark on it.
 */

private fun parked(event: ParkingAlert.Event = ParkingAlert.Event.DUE, note: String? = "Street sweeping", limitMinutes: Int? = null) = ParkingAlert(
    id = "parking-andrews_tesla",
    carName = "Andrew's Tesla",
    event = event,
    street = "Elm St",
    side = ParkingAlert.Side.NORTH,
    byEpochSeconds = 4_102_502_400L,
    note = note,
    limitMinutes = limitMinutes,
    fromEpochSeconds = if (limitMinutes == null) 4_102_502_400L else null,
    untilEpochSeconds = if (limitMinutes == null) 4_102_513_200L else null,
    silent = false,
    latitude = 40.0,
    longitude = -89.0,
    headingDegrees = 270f,
)

@Composable
private fun ParkingPreviewFrame(alert: ParkingAlert) {
    FrigateTheme {
        Box(Modifier.background(Color(0xFF101010)).width(412.dp).padding(16.dp)) {
            ParkingMapCard(alert, NoTiles, onClose = {}, animated = false)
        }
    }
}

@Preview(name = "Parking map, sweeping tomorrow")
@Composable
private fun ParkingMapDuePreview() = ParkingPreviewFrame(parked())

@Preview(name = "Parking map, time limit up", locale = "es")
@Composable
private fun ParkingMapNowPreview() = ParkingPreviewFrame(parked(ParkingAlert.Event.NOW, note = null, limitMinutes = 120))
