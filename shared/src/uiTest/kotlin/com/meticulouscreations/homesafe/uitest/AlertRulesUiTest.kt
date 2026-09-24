package com.meticulouscreations.homesafe.uitest

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import com.meticulouscreations.homesafe.domain.model.AlertPreset
import com.meticulouscreations.homesafe.domain.model.AlertSettings
import com.meticulouscreations.homesafe.domain.model.AlertVolume
import com.meticulouscreations.homesafe.domain.model.AlertZone
import com.meticulouscreations.homesafe.domain.model.CameraPipeline
import com.meticulouscreations.homesafe.domain.model.CameraZone
import com.meticulouscreations.homesafe.domain.model.MomentCategory
import com.meticulouscreations.homesafe.domain.model.QuietHours
import com.meticulouscreations.homesafe.domain.model.RetentionPolicy
import com.meticulouscreations.homesafe.domain.model.ServerOverview
import com.meticulouscreations.homesafe.domain.platform.NotificationPermission
import com.meticulouscreations.homesafe.ui.preview.FrigatePreview
import com.meticulouscreations.homesafe.ui.screens.AlertsSection
import com.meticulouscreations.homesafe.viewmodel.SettingsUiState
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The Settings tab's "Alerts" section, rendered for real: the notifications switch in each of its
 * states, and once it's on, the presets, the per-zone grid behind them, quiet hours with its
 * clock, the stranger rule and the test button. [AlertsSection] is stateless, so every state is
 * a [SettingsUiState] fixture and every control is checked by the callback it fires. Which preset
 * the rules amount to is pinned by AlertPresetsTest; this checks the screen says so, and hides or
 * shows the grid to match.
 *
 * The section is laid in a scrolling column, because with every rule showing it is taller than a
 * phone: rows near the bottom are scrolled to before they are touched. `mainClock.autoAdvance =
 * false` throughout, as in [HomeFeedUiTest], and time is advanced by hand past each change.
 */
@OptIn(ExperimentalTestApi::class)
class AlertRulesUiTest {

    private val street = AlertZone("front_door", "street")

    private val anywhereElse = AlertZone("front_door", null)

    /** A camera with one drawn zone, so its places are that zone and "Anywhere else". */
    private fun camera(zone: String, enabled: Boolean = true) = CameraPipeline(
        name = "front_door",
        enabled = enabled,
        detectionEnabled = true,
        motionEnabled = true,
        cameraFps = 5.0,
        detectionFps = 1.0,
        skippedFps = 0.0,
        zones = listOf(CameraZone(zone)),
    )

    private fun server(cameras: List<CameraPipeline> = listOf(camera("street")), faceRecognition: Boolean = true) = ServerOverview(
        version = "0.16.1",
        latestVersion = "0.16.1",
        uptimeSeconds = 86_400,
        cpuPercent = 9.0,
        memoryPercent = 40.0,
        recordingsStorage = null,
        detector = null,
        gpus = emptyList(),
        retention = RetentionPolicy(7.0, 7.0, null, null),
        faceRecognitionEnabled = faceRecognition,
        licensePlateRecognitionEnabled = false,
        semanticSearchEnabled = false,
        cameras = cameras,
        canEditConfig = true,
    )

    /** Notifications allowed by the OS and switched on in the app: the whole section is showing. */
    private fun alertsOn(
        alerts: AlertSettings = AlertSettings(pushNotificationsEnabled = true),
        overview: ServerOverview? = server(),
        alertVolume: AlertVolume? = null,
        testNotificationSent: Boolean = false,
    ) = SettingsUiState(
        overview = overview,
        alerts = alerts,
        notificationsSupported = true,
        notificationPermission = NotificationPermission.GRANTED,
        testNotificationSent = testNotificationSent,
        alertVolume = alertVolume,
    )

    private fun runAlerts(
        state: SettingsUiState,
        onPushNotifications: (Boolean) -> Unit = {},
        onZoneCategory: (AlertZone, MomentCategory, Boolean) -> Unit = { _, _, _ -> },
        onPreset: (AlertPreset) -> Unit = {},
        onQuietHours: (QuietHours) -> Unit = {},
        onOnlyWhenAway: (Boolean) -> Unit = {},
        onQuietFamiliar: (Boolean) -> Unit = {},
        onLoadVolume: () -> Unit = {},
        onOpenSettings: () -> Unit = {},
        onSendTest: () -> Unit = {},
        block: ComposeUiTest.() -> Unit,
    ) = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            FrigatePreview {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    AlertsSection(
                        state = state,
                        onPushNotifications = onPushNotifications,
                        onZoneCategory = onZoneCategory,
                        onPreset = onPreset,
                        onQuietHours = onQuietHours,
                        onOnlyWhenAway = onOnlyWhenAway,
                        onQuietFamiliar = onQuietFamiliar,
                        onLoadVolume = onLoadVolume,
                        onOpenSettings = onOpenSettings,
                        onSendTest = onSendTest,
                    )
                }
            }
        }
        block()
    }

    /**
     * The switch on the row titled [title]. The row has no semantics of its own, so the switch
     * isn't found under its title but beside it: the one whose centre is nearest the title's.
     */
    private fun ComposeUiTest.switchFor(title: String): SemanticsNodeInteraction {
        val titleY = onNodeWithText(title).fetchSemanticsNode().boundsInRoot.center.y
        val switches = onAllNodes(isToggleable())
        val nearest = switches.fetchSemanticsNodes()
            .withIndex()
            .minBy { (_, node) -> abs(node.boundsInRoot.center.y - titleY) }
            .index
        return switches[nearest]
    }

    /** Scrolls [node] into view and lets the scroll finish, since the clock only moves by hand here. */
    private fun ComposeUiTest.reveal(node: SemanticsNodeInteraction): SemanticsNodeInteraction {
        node.performScrollTo()
        mainClock.advanceTimeBy(SETTLE_MS)
        return node
    }

    // ---- The notifications switch --------------------------------------------------------------

    @Test
    fun withoutNotificationsOnThisPlatformTheSwitchIsLockedOff() = runAlerts(SettingsUiState(notificationsSupported = false)) {
        onNodeWithText("Not available on this platform. Use the Android or iOS app for alerts.").assertIsDisplayed()
        switchFor("Notifications").assertIsOff().assertIsNotEnabled()
        onAllNodesWithText("Send test notification").assertCountEquals(0)
    }

    @Test
    fun blockedNotificationsLockTheSwitchAndPointToSystemSettings() {
        var opened = 0
        runAlerts(
            // Switched on in the app, but the OS says no: the OS wins.
            alertsOn().copy(notificationPermission = NotificationPermission.DENIED),
            onOpenSettings = { opened++ },
        ) {
            onNodeWithText("Blocked in system settings. Allow notifications for HomeSafe to turn this on.").assertIsDisplayed()
            switchFor("Notifications").assertIsOff().assertIsNotEnabled()
            onAllNodesWithText("What to hear about").assertCountEquals(0)
            onNodeWithText("Open notification settings").performClick()
            assertEquals(1, opened)
        }
    }

    @Test
    fun notificationsOffOfferTheSwitchAndNothingElse() {
        var asked: Boolean? = null
        runAlerts(alertsOn(alerts = AlertSettings.DEFAULT), onPushNotifications = { asked = it }) {
            onNodeWithText("Get a notification when a camera sees something.").assertIsDisplayed()
            onAllNodesWithText("What to hear about").assertCountEquals(0)
            onAllNodesWithText("Send test notification").assertCountEquals(0)
            switchFor("Notifications").assertIsOff().assertIsEnabled().performClick()
            assertEquals(true, asked)
        }
    }

    @Test
    fun notificationsOnLayOutTheRulesAndTheTestButton() {
        var asked: Boolean? = null
        runAlerts(alertsOn(), onPushNotifications = { asked = it }) {
            onNodeWithText("On — a notification for each new detection while HomeSafe is running.").assertIsDisplayed()
            onNodeWithText("What to hear about").assertExists()
            onNodeWithText("Quiet hours").assertExists()
            onNodeWithText("Only strangers").assertExists()
            onNodeWithText("Send test notification").assertExists()
            switchFor("Notifications").assertIsOn().performClick()
            assertEquals(false, asked)
        }
    }

    @Test
    fun onlyWhenAwayHidesTheRulesItOverrides() {
        var asked: Boolean? = null
        runAlerts(
            alertsOn(alerts = AlertSettings(pushNotificationsEnabled = true, onlyWhenAway = true)),
            onOnlyWhenAway = { asked = it },
        ) {
            onNodeWithText(
                "On — quiet while anyone's home. Once every phone is away, each person seen notifies on the Away alerts channel.",
            ).assertIsDisplayed()
            // Away alerts ignore zones, quiet hours and the stranger rule, so none of them is offered.
            onAllNodesWithText("What to hear about").assertCountEquals(0)
            onAllNodesWithText("Quiet hours").assertCountEquals(0)
            onAllNodesWithText("Only strangers").assertCountEquals(0)
            onNodeWithText("Send test notification").assertExists()
            switchFor("Only when everyone's away").assertIsOn().performClick()
            assertEquals(false, asked)
        }
    }

    @Test
    fun onlyWhenAwayCanBeSwitchedOn() {
        var asked: Boolean? = null
        runAlerts(alertsOn(), onOnlyWhenAway = { asked = it }) {
            onNodeWithText("Stay quiet while anyone's home, and hear only the Away alerts once the house is empty.").assertIsDisplayed()
            switchFor("Only when everyone's away").assertIsOff().performClick()
            assertEquals(true, asked)
        }
    }

    // ---- What to hear about --------------------------------------------------------------------

    @Test
    fun theRulesWaitForTheCameras() {
        var volumeAsked = 0
        runAlerts(alertsOn(overview = null), onLoadVolume = { volumeAsked++ }) {
            onNodeWithText("Loading cameras…").assertIsDisplayed()
            onAllNodesWithText("People only").assertCountEquals(0)
            // Without the server's config it isn't known whether faces are recognised at all.
            onNodeWithText("Needs face recognition on the server.").assertExists()
            reveal(switchFor("Only strangers")).assertIsOff().assertIsNotEnabled()
            mainClock.advanceTimeBy(SETTLE_MS)
            assertEquals(0, volumeAsked, "there are no rules to estimate yet")
        }
    }

    @Test
    fun aServerWithNoEnabledCamerasHasNoRulesToSet() {
        var volumeAsked = 0
        runAlerts(alertsOn(overview = server(cameras = listOf(camera("street", enabled = false)))), onLoadVolume = { volumeAsked++ }) {
            onNodeWithText("No cameras on this server.").assertIsDisplayed()
            onAllNodesWithText("People only").assertCountEquals(0)
            mainClock.advanceTimeBy(SETTLE_MS)
            assertEquals(0, volumeAsked)
        }
    }

    @Test
    fun theNoiseEstimateIsAskedForOnceTheCamerasAreKnown() {
        var volumeAsked = 0
        runAlerts(alertsOn(), onLoadVolume = { volumeAsked++ }) {
            mainClock.advanceTimeBy(SETTLE_MS)
            assertEquals(1, volumeAsked)
        }
    }

    @Test
    fun theDefaultRulesReadAsPeopleAndVehiclesWithTheGridFolded() = runAlerts(alertsOn()) {
        onNodeWithText("People + vehicles").assertIsSelected()
        onNodeWithText("People only").assertIsNotSelected()
        onNodeWithText("Everything").assertIsNotSelected()
        onNodeWithText("People and vehicles anywhere, street traffic included. The out-of-the-box rules.").assertIsDisplayed()
        // A street is not a driveway, so the driveway preset would only repeat "People only".
        onAllNodesWithText("People + driveway cars").assertCountEquals(0)
        onAllNodesWithText("Custom").assertCountEquals(0)
        onNodeWithText("Fine-tune by zone").assertExists()
        onAllNodesWithText("Zones come from each camera's detection zones.").assertCountEquals(0)
    }

    @Test
    fun aDrivewayZoneAddsTheDrivewayPreset() = runAlerts(alertsOn(overview = server(cameras = listOf(camera("driveway"))))) {
        onNodeWithText("People + driveway cars").assertIsDisplayed().assertIsNotSelected()
        onNodeWithText("People + vehicles").assertIsSelected()
    }

    @Test
    fun tappingAPresetAppliesIt() {
        var picked: AlertPreset? = null
        runAlerts(alertsOn(), onPreset = { picked = it }) {
            onNodeWithText("Everything").performClick()
            assertEquals(AlertPreset.EVERYTHING, picked)
        }
    }

    @Test
    fun fineTuneOpensThePerZoneGridAndHidesItAgain() = runAlerts(alertsOn()) {
        reveal(onNodeWithText("Fine-tune by zone")).performClick()
        mainClock.advanceTimeBy(SETTLE_MS)

        onNodeWithText("Zones come from each camera's detection zones.").assertExists()
        onNodeWithText("Front Door").assertExists()
        onNodeWithText("Street").assertExists()
        onNodeWithText("Anywhere else").assertExists()
        // A place nobody has touched shows the defaults rather than nothing.
        onAllNodesWithText("People").assertCountEquals(2)
        reveal(onAllNodesWithText("Vehicles")[0]).assertIsSelected()
        reveal(onAllNodesWithText("Animals")[0]).assertIsNotSelected()

        reveal(onNodeWithText("Hide zones")).performClick()
        mainClock.advanceTimeBy(SETTLE_MS)

        onAllNodesWithText("Zones come from each camera's detection zones.").assertCountEquals(0)
        onNodeWithText("Fine-tune by zone").assertExists()
    }

    @Test
    fun rulesNoPresetDescribesShowCustomAndTheWholeGrid() = runAlerts(
        alertsOn(alerts = AlertSettings(pushNotificationsEnabled = true, zoneRules = mapOf(street to setOf(MomentCategory.PEOPLE)))),
    ) {
        // "Custom" is a state, not a choice: shown selected, but not something to tap.
        onNodeWithText("Custom").assertIsSelected().assertIsNotEnabled()
        onNodeWithText("People + vehicles").assertIsNotSelected()
        onNodeWithText("Custom — tuned place by place below.").assertIsDisplayed()
        // Custom rules are only readable as the grid, so it's open and can't be folded away.
        onNodeWithText("Zones come from each camera's detection zones.").assertExists()
        onAllNodesWithText("Fine-tune by zone").assertCountEquals(0)
        onAllNodesWithText("Hide zones").assertCountEquals(0)
    }

    @Test
    fun aGridChipFlipsThatCategoryInThatPlaceOnly() {
        var flipped: Triple<AlertZone, MomentCategory, Boolean>? = null
        runAlerts(
            alertsOn(alerts = AlertSettings(pushNotificationsEnabled = true, zoneRules = mapOf(street to setOf(MomentCategory.PEOPLE)))),
            onZoneCategory = { place, category, on -> flipped = Triple(place, category, on) },
        ) {
            // The street's chips come first, then "Anywhere else"'s, which still has the defaults.
            val streetVehicles = reveal(onAllNodesWithText("Vehicles")[0])
            streetVehicles.assertIsNotSelected().performClick()
            assertEquals(Triple(street, MomentCategory.VEHICLES, true), flipped)

            val elsewhereVehicles = reveal(onAllNodesWithText("Vehicles")[1])
            elsewhereVehicles.assertIsSelected().performClick()
            assertEquals(Triple(anywhereElse, MomentCategory.VEHICLES, false), flipped)
        }
    }

    @Test
    fun theLoudestRuleThatIsOnIsNamedWithHowOftenItWouldHaveFired() = runAlerts(
        alertsOn(alertVolume = AlertVolume(perDay = mapOf((street to MomentCategory.VEHICLES) to 40.0), days = 7.0)),
    ) {
        onNodeWithText("Front Door · Street · Vehicles would have alerted about 280 times last week.").assertIsDisplayed()

        reveal(onNodeWithText("Fine-tune by zone")).performClick()
        mainClock.advanceTimeBy(SETTLE_MS)

        reveal(onNodeWithText("Vehicles · ~40/day")).assertIsSelected()
    }

    @Test
    fun aShortSampleQuotesTheNoisePerDay() = runAlerts(
        alertsOn(alertVolume = AlertVolume(perDay = mapOf((street to MomentCategory.VEHICLES) to 40.0), days = 2.0)),
    ) {
        onNodeWithText("Front Door · Street · Vehicles would have alerted about 40 times a day lately.").assertIsDisplayed()
    }

    @Test
    fun aNoisyRuleThatIsOffIsFlaggedOnItsChipButNotWarnedAbout() = runAlerts(
        alertsOn(
            alerts = AlertSettings(pushNotificationsEnabled = true, zoneRules = mapOf(street to setOf(MomentCategory.PEOPLE))),
            alertVolume = AlertVolume(perDay = mapOf((street to MomentCategory.VEHICLES) to 40.0), days = 7.0),
        ),
    ) {
        onAllNodesWithText("would have alerted", substring = true).assertCountEquals(0)
        // Spotting a noisy rule before switching it on is the point of labelling the chip either way.
        reveal(onNodeWithText("Vehicles · ~40/day")).assertIsNotSelected()
    }

    // ---- Quiet hours ---------------------------------------------------------------------------

    @Test
    fun quietHoursOffOfferOnlyTheSwitchAndKeepTheWindow() {
        var changed: QuietHours? = null
        runAlerts(alertsOn(), onQuietHours = { changed = it }) {
            onNodeWithText("Silence ordinary alerts overnight. Away alerts still come through.").assertExists()
            onAllNodesWithText("From 10:00 PM").assertCountEquals(0)
            reveal(switchFor("Quiet hours")).assertIsOff().performClick()
            // Switching on brings back the window last chosen, here the default 10 PM to 7 AM.
            assertEquals(QuietHours(enabled = true, startMinute = 22 * 60, endMinute = 7 * 60), changed)
        }
    }

    @Test
    fun quietHoursOnShowTheWindowAndItsEnds() {
        var changed: QuietHours? = null
        runAlerts(alertsOn(alerts = AlertSettings(pushNotificationsEnabled = true, quietHours = QuietHours(enabled = true))), onQuietHours = { changed = it }) {
            onNodeWithText("On — nothing from 10:00 PM to 7:00 AM. Away alerts still come through.").assertExists()
            reveal(onNodeWithText("From 10:00 PM")).assertIsDisplayed()
            reveal(onNodeWithText("To 7:00 AM")).assertIsDisplayed()
            onAllNodesWithText("The window starts where it ends, so it's empty. Pick a different end time.").assertCountEquals(0)
            reveal(switchFor("Quiet hours")).assertIsOn().performClick()
            assertEquals(QuietHours(enabled = false), changed)
        }
    }

    @Test
    fun anEmptyQuietWindowIsFlagged() = runAlerts(
        alertsOn(alerts = AlertSettings(pushNotificationsEnabled = true, quietHours = QuietHours(enabled = true, startMinute = 6 * 60, endMinute = 6 * 60))),
    ) {
        onNodeWithText("From 6:00 AM").assertExists()
        onNodeWithText("To 6:00 AM").assertExists()
        reveal(onNodeWithText("The window starts where it ends, so it's empty. Pick a different end time.")).assertIsDisplayed()
    }

    @Test
    fun theFromButtonOpensAClockForTheStartOfTheWindow() {
        var changed: QuietHours? = null
        runAlerts(alertsOn(alerts = AlertSettings(pushNotificationsEnabled = true, quietHours = QuietHours(enabled = true))), onQuietHours = { changed = it }) {
            reveal(onNodeWithText("From 10:00 PM")).performClick()
            mainClock.advanceTimeBy(SETTLE_MS)
            onNodeWithText("Quiet from").assertIsDisplayed()

            // The clock opens on the current start, so setting it untouched keeps the window as it was.
            onNodeWithText("Set").performClick()
            mainClock.advanceTimeBy(SETTLE_MS)

            assertEquals(QuietHours(enabled = true, startMinute = 22 * 60, endMinute = 7 * 60), changed)
            onAllNodesWithText("Quiet from").assertCountEquals(0)
        }
    }

    @Test
    fun cancellingTheClockChangesNothing() {
        var changed: QuietHours? = null
        runAlerts(alertsOn(alerts = AlertSettings(pushNotificationsEnabled = true, quietHours = QuietHours(enabled = true))), onQuietHours = { changed = it }) {
            reveal(onNodeWithText("To 7:00 AM")).performClick()
            mainClock.advanceTimeBy(SETTLE_MS)
            onNodeWithText("Quiet until").assertIsDisplayed()

            onNodeWithText("Cancel").performClick()
            mainClock.advanceTimeBy(SETTLE_MS)

            assertNull(changed)
            onAllNodesWithText("Quiet until").assertCountEquals(0)
        }
    }

    // ---- Strangers and the test button ---------------------------------------------------------

    @Test
    fun onlyStrangersIsLockedOffWhileFaceRecognitionIsOff() {
        var asked: Boolean? = null
        runAlerts(
            // The wish is kept, but without faces it can't do anything, so the switch says off.
            alertsOn(
                alerts = AlertSettings(pushNotificationsEnabled = true, quietFamiliarPeople = true),
                overview = server(faceRecognition = false),
            ),
            onQuietFamiliar = { asked = it },
        ) {
            reveal(onNodeWithText("Needs face recognition, which is off in Frigate's config.")).assertIsDisplayed()
            reveal(switchFor("Only strangers")).assertIsOff().assertIsNotEnabled().performClick()
            assertNull(asked)
        }
    }

    @Test
    fun onlyStrangersCanBeSwitchedOnOnceFacesAreRecognised() {
        var asked: Boolean? = null
        runAlerts(alertsOn(), onQuietFamiliar = { asked = it }) {
            reveal(onNodeWithText("Skip the notification when Frigate recognises the person. Name faces under Recognition below.")).assertIsDisplayed()
            reveal(switchFor("Only strangers")).assertIsOff().assertIsEnabled().performClick()
            assertEquals(true, asked)
        }
    }

    @Test
    fun theTestButtonSendsOneAndSaysWhenItHasGone() {
        var sent = 0
        runAlerts(alertsOn(testNotificationSent = true), onSendTest = { sent++ }) {
            reveal(onNodeWithText("Send test notification")).performClick()
            assertEquals(1, sent)
            onNodeWithText("Sent").assertExists()
        }
    }

    private companion object {
        /** Longer than a scroll, a dialog's entrance or exit, and the frames either side of them. */
        const val SETTLE_MS = 1_000L
    }
}
