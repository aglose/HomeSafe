package com.meticulouscreations.homesafe.uitest

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.meticulouscreations.homesafe.domain.model.ActiveConnection
import com.meticulouscreations.homesafe.domain.model.AlertSettings
import com.meticulouscreations.homesafe.domain.model.CameraPipeline
import com.meticulouscreations.homesafe.domain.model.ClassifierModel
import com.meticulouscreations.homesafe.domain.model.ConnectionRoute
import com.meticulouscreations.homesafe.domain.model.HouseholdPresence
import com.meticulouscreations.homesafe.domain.model.PresenceDevice
import com.meticulouscreations.homesafe.domain.model.RetentionPolicy
import com.meticulouscreations.homesafe.domain.model.ServerOverview
import com.meticulouscreations.homesafe.domain.model.StorageUsage
import com.meticulouscreations.homesafe.domain.platform.NotificationPermission
import com.meticulouscreations.homesafe.finance.domain.EconomyTone
import com.meticulouscreations.homesafe.ui.preview.FrigatePreview
import com.meticulouscreations.homesafe.ui.screens.SettingsFold
import com.meticulouscreations.homesafe.ui.screens.SettingsHome
import com.meticulouscreations.homesafe.viewmodel.SettingsUiState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Settings tab's main page, rendered for real: a short list of rows, each with a line saying
 * how its section stands, that either ask for a page of their own (Alerts, Away mode,
 * Recognition's rows, Server) or open out in place (Cameras, Economy commentary). [SettingsHome]
 * is stateless but for which fold is open, so every state is a [SettingsUiState] fixture and
 * every row is checked by the callback it fires or by what it shows. The wording of the lines is
 * pinned by SettingsSummariesTest and ServerSummaryTest; this checks they are on the rows.
 *
 * `mainClock.autoAdvance = false` as in the other Settings suites; a fold's opening is an
 * animation, so time is advanced by hand past each tap on one.
 */
@OptIn(ExperimentalTestApi::class)
class SettingsHomeUiTest {

    private fun camera(name: String, detecting: Boolean = true) = CameraPipeline(
        name = name,
        enabled = true,
        detectionEnabled = detecting,
        motionEnabled = true,
        cameraFps = 5.0,
        detectionFps = 1.0,
        skippedFps = 0.0,
    )

    private val overview = ServerOverview(
        version = "0.17.2",
        latestVersion = "0.17.2",
        uptimeSeconds = 86_400,
        cpuPercent = 9.0,
        memoryPercent = 40.0,
        recordingsStorage = StorageUsage("/media/frigate/recordings", usedMb = 550_000.0, totalMb = 1_000_000.0),
        detector = null,
        gpus = emptyList(),
        retention = RetentionPolicy(7.0, 7.0, null, null),
        faceRecognitionEnabled = true,
        licensePlateRecognitionEnabled = false,
        semanticSearchEnabled = false,
        cameras = listOf(camera("front_door"), camera("driveway"), camera("back_yard", detecting = false)),
        canEditConfig = true,
    )

    /** A signed-in household with everything read: notifications on, this phone home, three cameras. */
    private val loaded = SettingsUiState(
        connection = ActiveConnection(serverUrl = "https://frigate.example.ts.net", localUrl = null, route = ConnectionRoute.TAILSCALE),
        overview = overview,
        alerts = AlertSettings(pushNotificationsEnabled = true),
        notificationsSupported = true,
        notificationPermission = NotificationPermission.GRANTED,
        classifiers = listOf(ClassifierModel(name = "known_cars", objects = listOf("car"))),
        presence = HouseholdPresence(
            devices = listOf(PresenceDevice(name = "Google Pixel 10 Pro XL", platform = "android", away = false, isThisDevice = true, id = "pixel", build = "release")),
            everyoneAway = false,
        ),
    )

    private fun runHome(
        state: SettingsUiState = loaded,
        initialFold: SettingsFold? = null,
        onOpen: (String) -> Unit = {},
        onDetection: (String, Boolean) -> Unit = { _, _ -> },
        onTone: (EconomyTone) -> Unit = {},
        block: ComposeUiTest.() -> Unit,
    ) = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            FrigatePreview {
                SettingsHome(
                    state = state,
                    onOpenAlerts = { onOpen("alerts") },
                    onOpenAway = { onOpen("away") },
                    onOpenClassifier = { onOpen("classifier:$it") },
                    onOpenFaces = { onOpen("faces") },
                    onOpenServer = { onOpen("server") },
                    onDetection = onDetection,
                    onMotion = { _, _ -> },
                    onDismissCameraError = {},
                    onTone = onTone,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    initialFold = initialFold,
                )
            }
        }
        block()
    }

    private fun ComposeUiTest.tapRow(title: String) {
        scrollIntoView(onNode(hasText(title) and hasClickAction())).performClick()
        mainClock.advanceTimeBy(SETTLE_MS)
    }

    private val folded = SemanticsMatcher.keyIsDefined(SemanticsActions.Expand)

    private val openedOut = SemanticsMatcher.keyIsDefined(SemanticsActions.Collapse)

    @Test
    fun everyRowSaysHowItsSectionStands() = runHome {
        onNode(hasText("Alerts") and hasText("On · People + vehicles")).assertIsDisplayed()
        onNode(hasText("Away mode") and hasText("This phone is home")).assertIsDisplayed()
        onNodeWithText("Recognition").assertIsDisplayed()
        onNode(hasText("Faces") and hasClickAction()).assertIsDisplayed()
        onNode(hasText("Known Cars") and hasClickAction()).assertIsDisplayed()
        onNode(hasText("Cameras") and hasText("Detection on for 2 of 3 cameras")).assertIsDisplayed()
        onNode(hasText("Economy commentary") and hasText("Straight talk")).assertIsDisplayed()
        onNode(hasText("Server") and hasText("Tailscale · 55% storage used · healthy")).assertIsDisplayed()
    }

    @Test
    fun beforeAnythingHasAnsweredEveryRowStillHasALine() = runHome(SettingsUiState(notificationsSupported = true)) {
        onNode(hasText("Alerts") and hasText("Off")).assertIsDisplayed()
        onNode(hasText("Away mode") and hasText("Checking who's home…")).assertIsDisplayed()
        onNode(hasText("Cameras") and hasText("Reading camera pipelines…")).assertIsDisplayed()
        onNode(hasText("Server") and hasText("checking…")).assertIsDisplayed()
        // Nothing to teach until the server says it recognises faces or has a classifier.
        onAllNodesWithText("Recognition").assertCountEquals(0)
    }

    @Test
    fun theRowsWithAChevronAskForTheirPages() {
        val opened = mutableListOf<String>()
        runHome(onOpen = { opened += it }) {
            listOf("Alerts", "Away mode", "Faces", "Known Cars", "Server").forEach { tapRow(it) }

            assertEquals(listOf("alerts", "away", "faces", "classifier:known_cars", "server"), opened)
            // None of them opened anything out on this page.
            onAllNodes(isToggleable()).assertCountEquals(0)
        }
    }

    @Test
    fun theCameraSwitchesStayFoldedAwayUntilTheCamerasRowIsTapped() {
        val asked = mutableListOf<Pair<String, Boolean>>()
        runHome(onDetection = { camera, enabled -> asked += camera to enabled }) {
            onNode(hasText("Cameras") and hasClickAction()).assert(folded)
            onAllNodes(isToggleable()).assertCountEquals(0)
            onAllNodesWithText("Back Yard").assertCountEquals(0)

            tapRow("Cameras")

            onNode(hasText("Cameras") and hasClickAction()).assert(openedOut)
            // Object and motion detection for each of the three cameras.
            onAllNodes(isToggleable()).assertCountEquals(6)
            listOf("Front Door", "Driveway", "Back Yard").forEach { onNodeWithText(it).assertExists() }
            scrollIntoView(onAllNodes(isToggleable())[0]).assertIsOn().performClick()
            mainClock.advanceTimeBy(SETTLE_MS)
            assertEquals(listOf("front_door" to false), asked)

            tapRow("Cameras")
            onAllNodes(isToggleable()).assertCountEquals(0)
        }
    }

    @Test
    fun openingOneFoldClosesTheOther() = runHome(initialFold = SettingsFold.CAMERAS) {
        onAllNodes(isToggleable()).assertCountEquals(6)

        tapRow("Economy commentary")

        onNode(hasText("Economy commentary") and hasClickAction()).assert(openedOut)
        onNodeWithText("How Finance describes the economy. The readings, charts and warning lines are the same in both.").assertExists()
        onNode(hasText("Cameras") and hasClickAction()).assert(folded)
        onAllNodes(isToggleable()).assertCountEquals(0)
    }

    @Test
    fun theEconomyRowNamesTheToneInUseAndItsFoldPicksAnother() {
        val asked = mutableListOf<EconomyTone>()
        runHome(state = loaded.copy(economyTone = EconomyTone.BRIGHT_SIDE), initialFold = SettingsFold.ECONOMY, onTone = { asked += it }) {
            onNode(hasText("Economy commentary") and hasText("Bright side")).assertExists()

            // "Straight talk" is only on its own choice while the other tone is the one in use.
            scrollIntoView(onNodeWithText("Straight talk")).performClick()
            mainClock.advanceTimeBy(SETTLE_MS)

            assertEquals(listOf(EconomyTone.STRAIGHT), asked)
        }
    }

    private companion object {
        const val SETTLE_MS = 1_000L
    }
}
