package com.meticulouscreations.homesafe.uitest

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.meticulouscreations.homesafe.domain.model.HouseholdPresence
import com.meticulouscreations.homesafe.domain.model.NotTrackedReason
import com.meticulouscreations.homesafe.domain.model.PresenceDevice
import com.meticulouscreations.homesafe.text.asUiText
import com.meticulouscreations.homesafe.ui.preview.FrigatePreview
import com.meticulouscreations.homesafe.ui.screens.AwaySection
import com.meticulouscreations.homesafe.viewmodel.SettingsUiState
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Away mode page's card, rendered for real: the two tracked phones as the relay's presence
 * snapshot lists them, the "I'm away" switch, and what an install the relay doesn't track is told.
 * [AwaySection] is stateless, so every state is a [SettingsUiState] fixture and the switch is
 * checked by the callback it fires.
 */
@OptIn(ExperimentalTestApi::class)
class AwaySectionUiTest {

    private val pixel = PresenceDevice(name = "Google Pixel 10 Pro XL", platform = "android", away = false, isThisDevice = true, id = "pixel")
    private val iphone = PresenceDevice(name = "Apple iPhone", platform = "ios", away = false, id = "iphone")

    private fun presence(devices: List<PresenceDevice> = listOf(pixel, iphone), notTracked: NotTrackedReason? = null) =
        HouseholdPresence(devices = devices, everyoneAway = false, thisDeviceNotTracked = notTracked)

    private fun runAway(state: SettingsUiState, onAway: (Boolean) -> Unit = {}, block: ComposeUiTest.() -> Unit) = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            FrigatePreview {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    AwaySection(
                        state = state,
                        onAway = onAway,
                        onRequestLocation = {},
                        onSetHomeHere = {},
                        onClearHome = {},
                        onRemoveDevice = {},
                    )
                }
            }
        }
        mainClock.advanceTimeBy(SETTLE_MS)
        block()
    }

    /** The switch beside the row titled [title]: the toggleable whose centre is nearest the title's (see AlertRulesUiTest). */
    private fun ComposeUiTest.switchFor(title: String): SemanticsNodeInteraction {
        val titleY = onNodeWithText(title).fetchSemanticsNode().boundsInRoot.center.y
        val switches = onAllNodes(isToggleable())
        val nearest = switches.fetchSemanticsNodes().withIndex().minBy { (_, node) -> abs(node.boundsInRoot.center.y - titleY) }.index
        return switches[nearest]
    }

    @Test
    fun aTrackedPhoneSaysWhereItCountsAndItsSwitchSaysAway() {
        val asked = mutableListOf<Boolean>()
        runAway(SettingsUiState(presence = presence()), onAway = { asked += it }) {
            onNodeWithText("This phone counts as home.").assertIsDisplayed()
            switchFor(IM_AWAY).assertIsOff().assertIsEnabled().performClick()
            mainClock.advanceTimeBy(SETTLE_MS)
            assertEquals(listOf(true), asked)
        }
    }

    @Test
    fun theOnlySwitchIsTheAwayOneAutomaticIsAlwaysOn() = runAway(SettingsUiState(presence = presence())) {
        onNodeWithText("Automatic").assertIsDisplayed()
        assertEquals(1, onAllNodes(isToggleable()).fetchSemanticsNodes().size)
    }

    @Test
    fun bothTrackedPhonesAreListedAndAMissingOneIsNamed() {
        runAway(SettingsUiState(presence = presence())) {
            onNodeWithText("Google Pixel 10 Pro XL").assertIsDisplayed()
            onNodeWithText("Apple iPhone").assertIsDisplayed()
            onNodeWithText("No iPhone is tracked yet", substring = true).assertDoesNotExist()
        }
        runAway(SettingsUiState(presence = presence(devices = listOf(pixel)))) {
            onNodeWithText("No iPhone is tracked yet. One joins when a Release build opens on it.").assertIsDisplayed()
        }
    }

    @Test
    fun aDebugBuildIsToldItIsNotTrackedAndItsSwitchIsLocked() =
        runAway(SettingsUiState(presence = presence(devices = listOf(pixel.copy(isThisDevice = false), iphone), notTracked = NotTrackedReason.DEBUG_BUILD))) {
            onNodeWithText("This is a debug build", substring = true).assertIsDisplayed()
            onNodeWithText("This phone counts as home.").assertDoesNotExist()
            switchFor(IM_AWAY).assertIsNotEnabled()
        }

    @Test
    fun aSecondPhoneOfTheSameKindIsToldTheOtherIsTracked() =
        runAway(SettingsUiState(presence = presence(devices = listOf(pixel.copy(isThisDevice = false)), notTracked = NotTrackedReason.ANOTHER_PHONE))) {
            onNodeWithText("Another phone of this kind is the tracked one. Remove it below if this phone replaces it.").assertIsDisplayed()
        }

    @Test
    fun theSwitchWaitsWhileAChangeIsOnItsWay() = runAway(SettingsUiState(presence = presence(), awayBusy = true)) {
        switchFor(IM_AWAY).assertIsNotEnabled()
    }

    @Test
    fun untilTheRelayAnswersTheSwitchSaysItIsChecking() = runAway(SettingsUiState()) {
        onNodeWithText("Checking with the relay…").assertIsDisplayed()
        onNodeWithText("This phone counts as home.").assertDoesNotExist()
    }

    @Test
    fun anUnreachableRelayLocksTheSwitch() = runAway(SettingsUiState(presence = HouseholdPresence.EMPTY, awayError = "Relay unreachable".asUiText())) {
        switchFor(IM_AWAY).assertIsNotEnabled()
    }

    private companion object {
        const val IM_AWAY = "I'm away"
        const val SETTLE_MS = 1_000L
    }
}
