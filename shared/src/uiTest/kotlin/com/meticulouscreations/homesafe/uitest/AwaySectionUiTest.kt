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
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.meticulouscreations.homesafe.domain.model.HouseholdPresence
import com.meticulouscreations.homesafe.domain.model.PresenceDevice
import com.meticulouscreations.homesafe.text.asUiText
import com.meticulouscreations.homesafe.ui.preview.FrigatePreview
import com.meticulouscreations.homesafe.ui.screens.AwaySection
import com.meticulouscreations.homesafe.viewmodel.SettingsUiState
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Settings tab's "Away mode" section and its "This phone decides home/away" switch, rendered
 * for real: which phone decides, as the relay's presence snapshot says, and what the switch and
 * the device list make of it. [AwaySection] is stateless, so every state is a [SettingsUiState]
 * fixture and the switch is checked by the callback it fires.
 */
@OptIn(ExperimentalTestApi::class)
class AwaySectionUiTest {

    private val pixel = PresenceDevice(name = "Google Pixel 10 Pro XL", platform = "android", away = false, isThisDevice = true, id = "pixel", build = "release")
    private val iphone = PresenceDevice(name = "Apple iPhone", platform = "ios", away = false, id = "iphone", build = "debug")

    /** The household as a relay with [decider] as its presence authority tells it: that phone alone counts. */
    private fun presence(decider: String?, devices: List<PresenceDevice> = listOf(pixel, iphone)) = HouseholdPresence(
        devices = devices.map { if (decider == null) it else it.copy(decides = it.id == decider, countsForAway = it.id == decider) },
        everyoneAway = false,
        authorityDeviceId = decider,
    )

    private fun runAway(state: SettingsUiState, onDecides: (Boolean) -> Unit = {}, block: ComposeUiTest.() -> Unit) = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            FrigatePreview {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    AwaySection(
                        state = state,
                        onAway = {},
                        onDecides = onDecides,
                        onAutomatic = {},
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
    fun withNobodyDecidingTheSwitchIsOffAndTurningItOnAsksToDecide() {
        val asked = mutableListOf<Boolean>()
        runAway(SettingsUiState(presence = presence(decider = null)), onDecides = { asked += it }) {
            switchFor(DECIDES).assertIsOff().assertIsEnabled().performClick()
            mainClock.advanceTimeBy(SETTLE_MS)
            assertEquals(listOf(true), asked)
        }
    }

    @Test
    fun whenThisPhoneDecidesTheSwitchIsOnAndTheListSaysSo() {
        val asked = mutableListOf<Boolean>()
        runAway(SettingsUiState(presence = presence(decider = "pixel")), onDecides = { asked += it }) {
            switchFor(DECIDES).assertIsOn()
            onNodeWithText("On — only this phone's location says whether the house is empty. Every phone still gets notifications.").assertIsDisplayed()
            onNodeWithText("· decides home/away", substring = true).assertIsDisplayed()
            switchFor(DECIDES).performClick()
            mainClock.advanceTimeBy(SETTLE_MS)
            assertEquals(listOf(false), asked, "turning it off hands the decision back")
        }
    }

    @Test
    fun whenAnotherPhoneDecidesBothSwitchesNameIt() = runAway(SettingsUiState(presence = presence(decider = "iphone"))) {
        switchFor(DECIDES).assertIsOff().assertIsEnabled()
        onNodeWithText("Apple iPhone decides now. Turn this on to make it this phone instead. Every phone still gets notifications.").assertIsDisplayed()
        onNodeWithText("Apple iPhone decides whether the house is empty, so this switch only speaks for this phone.").assertIsDisplayed()
        onNodeWithText("debug, not counted", substring = true).assertDoesNotExist()
    }

    @Test
    fun theSwitchWaitsWhileAChangeIsOnItsWay() = runAway(SettingsUiState(presence = presence(decider = null), decidesBusy = true)) {
        switchFor(DECIDES).assertIsNotEnabled()
    }

    @Test
    fun anOlderRelayThatListsNoIdsCantBeAskedToDecide() =
        runAway(SettingsUiState(presence = presence(decider = null, devices = listOf(pixel.copy(id = null))))) {
            switchFor(DECIDES).assertIsNotEnabled()
        }

    @Test
    fun anUnreachableRelayLocksTheSwitch() = runAway(SettingsUiState(presence = HouseholdPresence.EMPTY, awayError = "Relay unreachable".asUiText())) {
        switchFor(DECIDES).assertIsNotEnabled()
    }

    private companion object {
        const val DECIDES = "This phone decides home/away"
        const val SETTLE_MS = 1_000L
    }
}
