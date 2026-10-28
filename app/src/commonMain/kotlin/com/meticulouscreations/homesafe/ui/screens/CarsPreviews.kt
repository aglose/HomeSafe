package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.meticulouscreations.homesafe.domain.model.CarFix
import com.meticulouscreations.homesafe.domain.model.CurbSpot
import com.meticulouscreations.homesafe.domain.model.HouseholdCar
import com.meticulouscreations.homesafe.domain.model.ParkingAlert
import com.meticulouscreations.homesafe.ui.theme.FrigateTheme
import com.meticulouscreations.homesafe.viewmodel.CarsUiState
import com.meticulouscreations.homesafe.weather.ui.NoTiles

/*
 * The cars' map: one Tesla at the curb with street sweeping in the morning, one at home and asleep,
 * and one driving. The places are made up (a field in Illinois): the real ones would give the house
 * away. No tiles come in a preview, so the map is its open-land colour with the cars' pins on it.
 */

private const val NOW = 4_102_444_800L

private val curb = HouseholdCar(
    id = "andrews_tesla",
    name = "Andrew's Tesla",
    lastSeen = CarFix(40.00010, -89.00060, 270f, NOW - 25 * 60, moving = false, home = true),
    parked = CurbSpot(
        latitude = 40.00010,
        longitude = -89.00060,
        street = "Elm St",
        side = ParkingAlert.Side.NORTH,
        sinceEpochSeconds = NOW - 5 * 3600,
        moveByEpochSeconds = NOW + 13 * 3600,
        note = "Street sweeping",
        limitMinutes = null,
        fromEpochSeconds = NOW + 13 * 3600,
        untilEpochSeconds = NOW + 16 * 3600,
    ),
    asleep = false,
)

private val home = HouseholdCar(
    id = "sarahs_car",
    name = "Sarah's Tesla",
    lastSeen = CarFix(40.00045, -89.00010, 90f, NOW - 3 * 3600, moving = false, home = true),
    parked = null,
    asleep = true,
)

private val driving = HouseholdCar(
    id = "guest_tesla",
    name = "Guest Tesla",
    lastSeen = CarFix(40.00300, -88.99700, 10f, NOW - 60, moving = true, home = false),
    parked = null,
    asleep = false,
)

@Composable
private fun CarsPreviewFrame(state: CarsUiState) {
    FrigateTheme { CarsContent(state = state, tiles = NoTiles, onBack = {}, onRefresh = {}, animated = false) }
}

@Preview(name = "Cars map", widthDp = 412, heightDp = 892)
@Composable
private fun CarsMapPreview() = CarsPreviewFrame(CarsUiState(cars = listOf(curb, home, driving), loaded = true, nowEpochSeconds = NOW))

@Preview(name = "Cars map, Spanish", widthDp = 412, heightDp = 892, locale = "es")
@Composable
private fun CarsMapSpanishPreview() = CarsPreviewFrame(CarsUiState(cars = listOf(curb, home), loaded = true, nowEpochSeconds = NOW))

@Preview(name = "Cars map, no cars", widthDp = 412, heightDp = 892)
@Composable
private fun CarsMapEmptyPreview() = CarsPreviewFrame(CarsUiState(loaded = true, nowEpochSeconds = NOW))
