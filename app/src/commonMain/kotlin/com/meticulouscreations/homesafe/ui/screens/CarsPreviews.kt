package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.meticulouscreations.homesafe.domain.model.CarFix
import com.meticulouscreations.homesafe.domain.model.CurbSpot
import com.meticulouscreations.homesafe.domain.model.HouseholdCar
import com.meticulouscreations.homesafe.domain.model.ParkingAlert
import com.meticulouscreations.homesafe.domain.model.ParkingCurb
import com.meticulouscreations.homesafe.domain.model.ParkingRule
import com.meticulouscreations.homesafe.domain.model.ParkingStreet
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.ui.theme.FrigateTheme
import com.meticulouscreations.homesafe.viewmodel.CarsUiState
import com.meticulouscreations.homesafe.weather.ui.NoTiles
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.household_cars_unreachable_kept

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

private fun sweeping(days: List<Int>, weeks: List<Int>, start: Int, end: Int, next: Long?) = ParkingRule(
    limitMinutes = null, days = days, weeks = weeks, months = emptyList(), startMinutes = start, endMinutes = end, note = "Street sweeping",
    permit = null, lifted = false, nextFromEpochSeconds = next, nextUntilEpochSeconds = next?.plus((end - start) * 60L),
)

// Elm St runs east past the curb Tesla, Oak St north from it. NOW is the first Friday of 2100, at
// midnight: sweeping on Elm's north side this afternoon and its south side's in two weeks; on Oak,
// sweeping all day on the east side today, and on the west a two-hour limit the household's permit lifts.
private val streets = listOf(
    ParkingStreet(
        street = "Elm St",
        line = listOf(ParkingStreet.Point(40.00000, -89.00250), ParkingStreet.Point(40.00000, -88.99850)),
        widthMetres = 16.0,
        curbs = listOf(
            ParkingCurb(ParkingAlert.Side.NORTH, left = true, rules = listOf(sweeping(listOf(4), listOf(1, 3), 780, 960, NOW + 13 * 3600))),
            ParkingCurb(ParkingAlert.Side.SOUTH, left = false, rules = listOf(sweeping(listOf(3), listOf(2, 4), 600, 780, NOW + (13 * 24 + 10) * 3600))),
        ),
    ),
    ParkingStreet(
        street = "Oak St",
        line = listOf(ParkingStreet.Point(40.00000, -88.99930), ParkingStreet.Point(40.00250, -88.99930)),
        widthMetres = 12.0,
        curbs = listOf(
            ParkingCurb(
                ParkingAlert.Side.WEST,
                left = true,
                rules = listOf(
                    ParkingRule(
                        limitMinutes = 120, days = listOf(0, 1, 2, 3, 4), weeks = emptyList(), months = listOf(4, 5, 6, 7, 8, 9, 10), startMinutes = 480, endMinutes = 1080,
                        note = null, permit = "B", lifted = true, nextFromEpochSeconds = NOW + 3 * 86_400 + 8 * 3600, nextUntilEpochSeconds = NOW + 3 * 86_400 + 18 * 3600,
                    ),
                ),
            ),
            ParkingCurb(ParkingAlert.Side.EAST, left = false, rules = listOf(sweeping(listOf(4), emptyList(), 0, 1440, NOW))),
        ),
    ),
)

@Composable
private fun CarsPreviewFrame(state: CarsUiState, rulesShown: Boolean = false) {
    FrigateTheme { CarsContent(state = state, tiles = NoTiles, onBack = {}, onRefresh = {}, animated = false, rulesShown = rulesShown) }
}

@Preview(name = "Cars map", widthDp = 412, heightDp = 892)
@Composable
private fun CarsMapPreview() = CarsPreviewFrame(CarsUiState(cars = listOf(curb, home, driving), streets = streets, loaded = true, nowEpochSeconds = NOW))

@Preview(name = "Cars map, parking rules", widthDp = 412, heightDp = 892)
@Composable
private fun CarsMapRulesPreview() = CarsPreviewFrame(CarsUiState(cars = listOf(curb, home), streets = streets, loaded = true, nowEpochSeconds = NOW), rulesShown = true)

@Preview(name = "Cars map, parking rules, Spanish", widthDp = 412, heightDp = 892, locale = "es")
@Composable
private fun CarsMapRulesSpanishPreview() = CarsPreviewFrame(CarsUiState(cars = listOf(curb, home), streets = streets, loaded = true, nowEpochSeconds = NOW), rulesShown = true)

@Preview(name = "Cars map, Spanish", widthDp = 412, heightDp = 892, locale = "es")
@Composable
private fun CarsMapSpanishPreview() = CarsPreviewFrame(CarsUiState(cars = listOf(curb, home), loaded = true, nowEpochSeconds = NOW))

@Preview(name = "Cars map, no cars", widthDp = 412, heightDp = 892)
@Composable
private fun CarsMapEmptyPreview() = CarsPreviewFrame(CarsUiState(loaded = true, nowEpochSeconds = NOW))

@Preview(name = "Cars map, nowhere yet", widthDp = 412, heightDp = 892)
@Composable
private fun CarsMapNowherePreview() = CarsPreviewFrame(
    CarsUiState(
        cars = listOf(curb.copy(lastSeen = null, parked = null), home.copy(lastSeen = null, asleep = false)),
        loaded = true,
        nowEpochSeconds = NOW,
    ),
)

@Preview(name = "Cars map, server unreachable", widthDp = 412, heightDp = 892)
@Composable
private fun CarsMapUnreachablePreview() = CarsPreviewFrame(
    CarsUiState(
        cars = listOf(curb, home),
        loaded = true,
        nowEpochSeconds = NOW,
        error = UiText.of(Res.string.household_cars_unreachable_kept),
    ),
)

@Preview(name = "Cars map, parking rules, no car placed", widthDp = 412, heightDp = 892)
@Composable
private fun CarsMapRulesOnlyPreview() = CarsPreviewFrame(
    CarsUiState(cars = listOf(home.copy(lastSeen = null, asleep = false)), streets = streets, loaded = true, nowEpochSeconds = NOW),
)
