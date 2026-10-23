package com.meticulouscreations.homesafe.atlas.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.meticulouscreations.homesafe.atlas.AtlasUiState
import com.meticulouscreations.homesafe.atlas.domain.AtlasProblem
import com.meticulouscreations.homesafe.atlas.domain.AtlasServerStatus
import com.meticulouscreations.homesafe.atlas.domain.AtlasSetupProblem
import com.meticulouscreations.homesafe.atlas.domain.AtlasSyncState
import com.meticulouscreations.homesafe.atlas.domain.CivilDate
import com.meticulouscreations.homesafe.finance.FinanceLockUiState
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.ui.theme.FrigateTheme
import com.meticulouscreations.homesafe.weather.ui.NoTiles
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.atlas_error_unreachable
import homesafe.shared.generated.resources.biometric_name_fingerprint

// Whole screens from the made-up life in AtlasFixtures. The map's tiles are left out (NoTiles):
// a preview has no network, and the night and the glow are what there is to look at.
@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun AtlasMapPreview() {
    FrigateTheme { AtlasAppContent(AtlasFixtures.state, NoTiles, AtlasActions()) }
}

@Preview(widthDp = 412, heightDp = 1500)
@Composable
private fun AtlasDaysPreview() {
    FrigateTheme { AtlasAppContent(AtlasFixtures.state, NoTiles, AtlasActions(), initialTab = AtlasTab.DAYS) }
}

// A workday, and the first day of the trip to the desert: a drive, a flight, a drive.
@Preview(widthDp = 412, heightDp = 1100)
@Composable
private fun AtlasDayPreview() {
    FrigateTheme { AtlasAppContent(AtlasFixtures.state, NoTiles, AtlasActions(), initialPage = AtlasPage.Day(CivilDate.dayOf(2025, 5, 13))) }
}

@Preview(widthDp = 412, heightDp = 1100)
@Composable
private fun AtlasDayFlightPreview() {
    FrigateTheme { AtlasAppContent(AtlasFixtures.state, NoTiles, AtlasActions(), initialPage = AtlasPage.Day(AtlasFixtures.desertTripDay)) }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun AtlasDayEmptyPreview() {
    FrigateTheme { AtlasAppContent(AtlasFixtures.state, NoTiles, AtlasActions(), initialPage = AtlasPage.Day(AtlasFixtures.firstDay - 40)) }
}

@Preview(widthDp = 412, heightDp = 1200)
@Composable
private fun AtlasPlacesPreview() {
    FrigateTheme { AtlasAppContent(AtlasFixtures.state, NoTiles, AtlasActions(), initialTab = AtlasTab.PLACES) }
}

@Preview(widthDp = 412, heightDp = 1700)
@Composable
private fun AtlasPlacePreview() {
    FrigateTheme { AtlasAppContent(AtlasFixtures.state, NoTiles, AtlasActions(), initialPage = AtlasPage.PlaceDetail(AtlasFixtures.gym.key)) }
}

@Preview(widthDp = 412, heightDp = 1200)
@Composable
private fun AtlasTripsPreview() {
    FrigateTheme { AtlasAppContent(AtlasFixtures.state, NoTiles, AtlasActions(), initialTab = AtlasTab.TRIPS) }
}

@Preview(widthDp = 412, heightDp = 1500)
@Composable
private fun AtlasTripPreview() {
    FrigateTheme { AtlasAppContent(AtlasFixtures.state, NoTiles, AtlasActions(), initialPage = AtlasPage.TripDetail(AtlasFixtures.state.analysis.trips[1].start)) }
}

@Preview(widthDp = 412, heightDp = 3200)
@Composable
private fun AtlasPatternsPreview() {
    FrigateTheme { AtlasAppContent(AtlasFixtures.state, NoTiles, AtlasActions(), initialTab = AtlasTab.PATTERNS) }
}

@Preview(widthDp = 412, heightDp = 1300)
@Composable
private fun AtlasSyncPreview() {
    FrigateTheme { AtlasAppContent(AtlasFixtures.state, NoTiles, AtlasActions(), initialPage = AtlasPage.Sync) }
}

// The box not set up yet: the folder not shared, no key for the names, and the server just now out of reach.
@Preview(widthDp = 412, heightDp = 1300)
@Composable
private fun AtlasSyncSetupPreview() {
    val state = AtlasFixtures.state.copy(
        sync = AtlasSyncState(
            problem = AtlasProblem.UNREACHABLE,
            message = UiText.of(Res.string.atlas_error_unreachable),
            server = AtlasServerStatus(driveConfigured = true, driveProblem = AtlasSetupProblem.NOT_SHARED, serviceAccount = "relay@example.iam.gserviceaccount.com", namingConfigured = false),
            checkedAtEpochSeconds = AtlasFixtures.state.nowEpochSeconds - 3 * 86_400,
        ),
    )
    FrigateTheme { AtlasAppContent(state, NoTiles, AtlasActions(), initialPage = AtlasPage.Sync) }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun AtlasEmptyPreview() {
    FrigateTheme { AtlasAppContent(AtlasUiState(loaded = true), NoTiles, AtlasActions()) }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun AtlasLockPreview() {
    FrigateTheme { AtlasLockScreen(FinanceLockUiState(locked = true, offline = true, canUnlock = true, method = Res.string.biometric_name_fingerprint), onUnlock = {}) }
}

// On its side, as a phone turned over or a short desktop window has it.
@Preview(widthDp = 915, heightDp = 412)
@Composable
private fun AtlasMapLandscapePreview() {
    FrigateTheme { AtlasAppContent(AtlasFixtures.state, NoTiles, AtlasActions()) }
}
