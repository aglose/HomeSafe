package com.meticulouscreations.homesafe.packages.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.packages.PackagesUiState
import com.meticulouscreations.homesafe.packages.domain.PackagesProblem
import com.meticulouscreations.homesafe.packages.domain.PackagesSnapshot
import com.meticulouscreations.homesafe.packages.domain.PackagesSync
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.ui.theme.FrigateTheme
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.packages_error_unreachable

// Whole screens from the made-up household in PackagesFixtures. A preview has no network, so the
// camera's pictures are the wipe's frame with nothing in it; the shaders are what there is to see.
@Preview(widthDp = 412, heightDp = 1500)
@Composable
private fun PackagesTodayPreview() {
    FrigateTheme { PackagesAppContent(PackagesFixtures.state(), PackagesActions()) }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun PackagesTroublePreview() {
    FrigateTheme { PackagesAppContent(PackagesFixtures.trouble, PackagesActions()) }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun PackagesQuietPreview() {
    FrigateTheme { PackagesAppContent(PackagesFixtures.quiet, PackagesActions()) }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun PackagesWhosePhonePreview() {
    FrigateTheme { PackagesAppContent(PackagesFixtures.state(PackagesFixtures.snapshot.copy(viewer = null)), PackagesActions()) }
}

@Preview(widthDp = 412, heightDp = 1500)
@Composable
private fun PackagesAllPreview() {
    FrigateTheme { PackagesAppContent(PackagesFixtures.state(), PackagesActions(), initialTab = PackagesTab.ALL) }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun PackagesReturnsPreview() {
    FrigateTheme { PackagesAppContent(PackagesFixtures.state(), PackagesActions(), initialTab = PackagesTab.RETURNS) }
}

@Preview(widthDp = 412, heightDp = 1900)
@Composable
private fun PackagesInsightsPreview() {
    FrigateTheme { PackagesAppContent(PackagesFixtures.state(), PackagesActions(), initialTab = PackagesTab.INSIGHTS) }
}

@Preview(widthDp = 412, heightDp = 2300)
@Composable
private fun PackagesParcelDeliveredPreview() {
    FrigateTheme { PackagesAppContent(PackagesFixtures.state(), PackagesActions(), initialPage = PackagesPage.Detail(PackagesFixtures.atDoor.id)) }
}

@Preview(widthDp = 412, heightDp = 1900)
@Composable
private fun PackagesParcelOnTheWayPreview() {
    FrigateTheme { PackagesAppContent(PackagesFixtures.state(), PackagesActions(), initialPage = PackagesPage.Detail(PackagesFixtures.outToday.id)) }
}

@Preview(widthDp = 412, heightDp = 2100)
@Composable
private fun PackagesParcelMayBeGonePreview() {
    FrigateTheme { PackagesAppContent(PackagesFixtures.state(), PackagesActions(), initialPage = PackagesPage.Detail(PackagesFixtures.mayBeGone.id)) }
}

@Preview(widthDp = 412, heightDp = 1500)
@Composable
private fun PackagesParcelNotSeenPreview() {
    FrigateTheme { PackagesAppContent(PackagesFixtures.state(), PackagesActions(), initialPage = PackagesPage.Detail(PackagesFixtures.notSeen.id)) }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun PackagesSurpriseKeptPreview() {
    FrigateTheme { PackagesAppContent(PackagesFixtures.state(), PackagesActions(), initialPage = PackagesPage.Detail(PackagesFixtures.surpriseKept.id)) }
}

@Preview(widthDp = 412, heightDp = 1100)
@Composable
private fun PackagesClaimPreview() {
    FrigateTheme { PackagesAppContent(PackagesFixtures.state(), PackagesActions(), initialPage = PackagesPage.Claim(PackagesFixtures.notSeen.id)) }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun PackagesAddPreview() {
    FrigateTheme { PackagesAppContent(PackagesFixtures.state(), PackagesActions(), initialPage = PackagesPage.Add) }
}

@Preview(widthDp = 412, heightDp = 1500)
@Composable
private fun PackagesSetupPreview() {
    FrigateTheme { PackagesAppContent(PackagesFixtures.state(), PackagesActions(), initialPage = PackagesPage.Setup) }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun PackagesLoadingPreview() {
    FrigateTheme { PackagesAppContent(PackagesUiState(), PackagesActions()) }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun PackagesNoServerPreview() {
    val state = PackagesUiState(snapshot = PackagesSnapshot(), sync = PackagesSync(problem = PackagesProblem.UNREACHABLE, message = UiText.of(Res.string.packages_error_unreachable)))
    FrigateTheme { PackagesAppContent(state, PackagesActions()) }
}

@Preview(widthDp = 380, heightDp = 170)
@Composable
private fun PackagesDrawerCardPreview() {
    FrigateTheme { Box(Modifier.fillMaxSize().padding(8.dp)) { PackagesDrawerCard(PackagesFixtures.state(), animated = false, onOpen = {}) } }
}

@Preview(widthDp = 1200, heightDp = 800)
@Composable
private fun PackagesWidePreview() {
    FrigateTheme { PackagesAppContent(PackagesFixtures.state(), PackagesActions()) }
}
