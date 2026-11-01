package com.meticulouscreations.homesafe.uitest

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.meticulouscreations.homesafe.packages.PackagesUiState
import com.meticulouscreations.homesafe.packages.domain.ParcelChange
import com.meticulouscreations.homesafe.packages.domain.PorchState
import com.meticulouscreations.homesafe.packages.ui.LocalPackagesShaders
import com.meticulouscreations.homesafe.packages.ui.PackagesActions
import com.meticulouscreations.homesafe.packages.ui.PackagesAppContent
import com.meticulouscreations.homesafe.packages.ui.PackagesPage
import com.meticulouscreations.homesafe.packages.ui.PackagesPreviewData
import com.meticulouscreations.homesafe.packages.ui.PackagesTab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

/** The packages app's own screens, from made-up parcels, with the shaders off: off a GPU they run on the CPU at seconds a frame. */
@OptIn(ExperimentalTestApi::class)
class PackagesAppUiTest {

    private class Calls {
        val changes = ArrayList<Pair<String, ParcelChange>>()
        val adds = ArrayList<String>()
        val forgotten = ArrayList<String>()
        var closed = 0

        fun actions() = PackagesActions(
            onClose = { closed++ },
            onChange = { id, change -> changes += id to change },
            onAdd = { tracking, _, _, _ -> adds += tracking },
            onForget = { forgotten += it },
        )
    }

    private fun show(test: androidx.compose.ui.test.ComposeUiTest, state: PackagesUiState, calls: Calls, tab: PackagesTab = PackagesTab.TODAY, page: PackagesPage? = null) {
        test.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalPackagesShaders provides false) {
                    PackagesAppContent(state, calls.actions(), initialTab = tab, initialPage = page)
                }
            }
        }
    }

    @Test
    fun theTodayPageSaysWhatsAtTheDoorAndOpensAParcel() = runComposeUiTest(testTimeout = 5.minutes) {
        show(this, PackagesPreviewData.state(), Calls())
        onNodeWithTag("packages_headline").assertIsDisplayed()
        onNodeWithTag("parcel_attempt", useUnmergedTree = true).performClick()
        waitForIdle()
        onNodeWithTag("packages_parcel").assertIsDisplayed()
        onNodeWithTag("parcel_name").assertIsDisplayed()
    }

    @Test
    fun backOffTheTopPageLeavesTheApp() = runComposeUiTest(testTimeout = 5.minutes) {
        val calls = Calls()
        show(this, PackagesPreviewData.state(), calls)
        onNodeWithTag("packages_back").performClick()
        assertEquals(1, calls.closed)
    }

    @Test
    fun takingAParcelInIsSaid() = runComposeUiTest(testTimeout = 5.minutes) {
        val calls = Calls()
        show(this, PackagesPreviewData.state(), calls, page = PackagesPage.Detail(PackagesPreviewData.atDoorId))
        onNodeWithTag("parcel_got_it").performScrollTo().performClick()
        assertEquals(listOf(PackagesPreviewData.atDoorId to ParcelChange(porch = PorchState.INSIDE)), calls.changes)
    }

    @Test
    fun theCamerasWordIsSaidWithTheParcel() = runComposeUiTest(testTimeout = 5.minutes) {
        show(this, PackagesPreviewData.state(), Calls(), page = PackagesPage.Detail(PackagesPreviewData.atDoorId))
        onNodeWithText("The camera saw a parcel land").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun nobodySeenIsSaidPlainly() = runComposeUiTest(testTimeout = 5.minutes) {
        show(this, PackagesPreviewData.state(), Calls(), page = PackagesPage.Detail(PackagesPreviewData.notSeenId))
        onNodeWithText("The camera saw nobody at the door").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aSurpriseKeptFromThisPhoneShowsNothingOfItself() = runComposeUiTest(testTimeout = 5.minutes) {
        show(this, PackagesPreviewData.state(), Calls(), page = PackagesPage.Detail(PackagesPreviewData.keptId))
        onNodeWithText("A surprise").assertIsDisplayed()
        onNodeWithText("Someone's keeping a secret").assertIsDisplayed()
        assertEquals(0, onAllNodesWithTagCount("parcel_surprise"))
    }

    private fun androidx.compose.ui.test.ComposeUiTest.onAllNodesWithTagCount(tag: String): Int = onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size

    @Test
    fun aParcelCanBeMadeASurprise() = runComposeUiTest(testTimeout = 5.minutes) {
        val calls = Calls()
        show(this, PackagesPreviewData.state(), calls, page = PackagesPage.Detail(PackagesPreviewData.atDoorId))
        onNodeWithTag("parcel_surprise").performScrollTo().performClick()
        assertEquals(listOf(PackagesPreviewData.atDoorId to ParcelChange(surprise = true)), calls.changes)
    }

    @Test
    fun aTrackingNumberIsAdded() = runComposeUiTest(testTimeout = 5.minutes) {
        val calls = Calls()
        show(this, PackagesPreviewData.state(), calls, page = PackagesPage.Add)
        onNodeWithTag("add_tracking").performTextInput("1Z999AA10123456784")
        onNodeWithTag("add_save").performScrollTo().performClick()
        assertEquals(listOf("1Z999AA10123456784"), calls.adds)
    }

    @Test
    fun searchNarrowsTheList() = runComposeUiTest(testTimeout = 5.minutes) {
        show(this, PackagesPreviewData.state(), Calls(), tab = PackagesTab.ALL)
        onNodeWithTag("packages_search").performTextInput("no such thing at all")
        waitForIdle()
        onNodeWithText("Nothing matches that.").assertIsDisplayed()
    }

    @Test
    fun theClaimHasTheNumbersOnIt() = runComposeUiTest(testTimeout = 5.minutes) {
        show(this, PackagesPreviewData.state(), Calls(), page = PackagesPage.Claim(PackagesPreviewData.notSeenId))
        listOf("Bath towels", "1Z999AA10123456784", "Target", "recorded nobody at the door").forEach { onNodeWithText(it, substring = true).assertExists() }
    }
}
