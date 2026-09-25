package com.meticulouscreations.homesafe.uitest

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Text
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.meticulouscreations.homesafe.domain.model.ActiveConnection
import com.meticulouscreations.homesafe.domain.model.ConnectionRoute
import com.meticulouscreations.homesafe.domain.model.DetectorInfo
import com.meticulouscreations.homesafe.domain.model.GpuLoad
import com.meticulouscreations.homesafe.domain.model.RetentionPolicy
import com.meticulouscreations.homesafe.domain.model.ServerOverview
import com.meticulouscreations.homesafe.domain.model.StorageUsage
import com.meticulouscreations.homesafe.ui.preview.FrigatePreview
import com.meticulouscreations.homesafe.ui.screens.AiFeaturesSection
import com.meticulouscreations.homesafe.ui.screens.ServerSection
import com.meticulouscreations.homesafe.ui.screens.SettingsCaption
import com.meticulouscreations.homesafe.ui.screens.SettingsSection
import com.meticulouscreations.homesafe.ui.screens.SettingsToggleRow
import com.meticulouscreations.homesafe.ui.screens.StorageSection
import com.meticulouscreations.homesafe.viewmodel.SettingsUiState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Settings tab's building blocks — the section card, its captions and the switch row — and
 * the three read-outs the Server page is made of: the server itself, its disk and retention, and
 * its AI features. All of them are stateless, so each is driven straight from a [SettingsUiState]
 * or a [ServerOverview] with no view model behind it, through loading, loaded and failed.
 *
 * `mainClock.autoAdvance = false` throughout, as in [HomeFeedUiTest]: a section still waiting
 * for the server shows an indeterminate spinner, which animates forever, so the composition is
 * never idle. Time is advanced by hand where a change has to be drawn.
 */
@OptIn(ExperimentalTestApi::class)
class SettingsSectionsUiTest {

    private fun overview(
        version: String = "0.16.0",
        latestVersion: String? = "0.16.1",
        recordingsStorage: StorageUsage? = StorageUsage("/media/frigate/recordings", usedMb = 412_650.0, totalMb = 1_000_000.0),
        detector: DetectorInfo? = null,
        gpus: List<GpuLoad> = emptyList(),
    ) = ServerOverview(
        version = version,
        latestVersion = latestVersion,
        uptimeSeconds = 90_000,
        cpuPercent = 12.4,
        memoryPercent = 48.6,
        recordingsStorage = recordingsStorage,
        detector = detector,
        gpus = gpus,
        retention = RetentionPolicy(continuousDays = 1.0, motionDays = 7.0, alertDays = 30.0, detectionDays = null),
        faceRecognitionEnabled = true,
        licensePlateRecognitionEnabled = false,
        semanticSearchEnabled = true,
        cameras = emptyList(),
        canEditConfig = true,
    )

    private fun runServer(state: SettingsUiState, onRetry: () -> Unit = {}, block: ComposeUiTest.() -> Unit) = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            FrigatePreview {
                ServerSection(state = state, onRetry = onRetry)
            }
        }
        block()
    }

    private fun runToggle(checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit, block: ComposeUiTest.() -> Unit) = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            FrigatePreview {
                SettingsToggleRow(
                    title = "Quiet hours",
                    description = "Silence ordinary alerts overnight.",
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                    enabled = enabled,
                )
            }
        }
        block()
    }

    // ---- Building blocks -----------------------------------------------------------------------

    @Test
    fun aSectionShowsItsTitleAboveItsContent() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            FrigatePreview {
                SettingsSection(title = "Storage & Retention", icon = Icons.Filled.Storage) {
                    Text("Recordings")
                    SettingsCaption("Frigate deletes old recordings on its own.")
                    SettingsCaption("Last refresh failed: timeout", error = true)
                }
            }
        }

        onNodeWithText("Storage & Retention").assertIsDisplayed()
        onNodeWithText("Recordings").assertIsDisplayed()
        onNodeWithText("Frigate deletes old recordings on its own.").assertIsDisplayed()
        // An error caption is the same text in another colour, not a different kind of node.
        onNodeWithText("Last refresh failed: timeout").assertIsDisplayed()
    }

    @Test
    fun aToggleRowShowsItsTitleDescriptionAndState() = runToggle(checked = true, enabled = true, onCheckedChange = {}) {
        onNodeWithText("Quiet hours").assertIsDisplayed()
        onNodeWithText("Silence ordinary alerts overnight.").assertIsDisplayed()
        onNode(isToggleable()).assertIsOn().assertIsEnabled()
    }

    @Test
    fun flippingAnOffSwitchAsksToTurnItOn() {
        var asked: Boolean? = null
        runToggle(checked = false, enabled = true, onCheckedChange = { asked = it }) {
            onNode(isToggleable()).assertIsOff().performClick()
            assertEquals(true, asked)
            // The row is stateless: a tap is a request, and the switch stays put until it's granted.
            mainClock.advanceTimeBy(SETTLE_MS)
            onNode(isToggleable()).assertIsOff()
        }
    }

    @Test
    fun flippingAnOnSwitchAsksToTurnItOff() {
        var asked: Boolean? = null
        runToggle(checked = true, enabled = true, onCheckedChange = { asked = it }) {
            onNode(isToggleable()).performClick()
            assertEquals(false, asked)
        }
    }

    @Test
    fun aLockedSwitchStillShowsItsStateButIgnoresTaps() {
        var asked: Boolean? = null
        runToggle(checked = true, enabled = false, onCheckedChange = { asked = it }) {
            // Locked "on" must still read as on: the row's own colours exist for exactly this.
            onNode(isToggleable()).assertIsOn().assertIsNotEnabled().performClick()
            assertEquals(null, asked, "a locked switch should not report a flip")
        }
    }

    // ---- Server --------------------------------------------------------------------------------

    @Test
    fun theServerSaysItIsReadingBeforeTheFirstAnswer() = runServer(SettingsUiState()) {
        onNodeWithText("Reading server stats…").assertIsDisplayed()
        onAllNodesWithText("Retry").assertCountEquals(0)
        onAllNodesWithText("Connected to", substring = true).assertCountEquals(0)
    }

    @Test
    fun aFailedFirstReadSaysWhyAndOffersARetry() {
        var retries = 0
        runServer(SettingsUiState(overviewError = "timeout"), onRetry = { retries++ }) {
            onNodeWithText("Couldn't reach the server: timeout").assertIsDisplayed()
            onAllNodesWithText("Reading server stats…").assertCountEquals(0)
            onNodeWithText("Retry").performClick()
            assertEquals(1, retries)
        }
    }

    @Test
    fun theLocalRouteSaysItSkipsTheVpn() = runServer(
        SettingsUiState(
            connection = ActiveConnection(
                serverUrl = "http://frigate.tailnet.ts.net:5000",
                localUrl = "http://192.168.1.20:5000",
                route = ConnectionRoute.LOCAL_NETWORK,
            ),
        ),
    ) {
        onNodeWithText("Connected to http://192.168.1.20:5000").assertIsDisplayed()
        onNodeWithText("Local network — direct over Wi-Fi, no VPN hop").assertIsDisplayed()
    }

    @Test
    fun aTailscaleRouteSaysWhenTheLocalAddressIsOutOfReach() = runServer(
        SettingsUiState(
            connection = ActiveConnection(
                serverUrl = "http://frigate.tailnet.ts.net:5000",
                localUrl = "http://192.168.1.20:5000",
                route = ConnectionRoute.TAILSCALE,
            ),
        ),
    ) {
        onNodeWithText("Connected to http://frigate.tailnet.ts.net:5000").assertIsDisplayed()
        onNodeWithText("Tailscale — the local address isn't reachable from here").assertIsDisplayed()
    }

    @Test
    fun aLoadedServerShowsItsVersionUptimeAndLoad() = runServer(SettingsUiState(overview = overview())) {
        onNodeWithText("VERSION").assertIsDisplayed()
        onNodeWithText("0.16.0").assertIsDisplayed()
        onNodeWithText("Update available: 0.16.1").assertIsDisplayed()
        onNodeWithText("1 day, 1 hour").assertIsDisplayed()
        onNodeWithText("12%").assertIsDisplayed()
        onNodeWithText("49%").assertIsDisplayed()
        onAllNodesWithText("Reading server stats…").assertCountEquals(0)
    }

    @Test
    fun aServerOnTheLatestReleaseOffersNoUpdate() = runServer(
        // A build suffix on the running version is still the latest release.
        SettingsUiState(overview = overview(version = "0.16.1-3d4dd3a", latestVersion = "0.16.1")),
    ) {
        onNodeWithText("0.16.1-3d4dd3a").assertIsDisplayed()
        onAllNodesWithText("Update available", substring = true).assertCountEquals(0)
    }

    @Test
    fun theDetectorAndEachGpuAreDescribed() = runServer(
        SettingsUiState(
            overview = overview(
                detector = DetectorInfo(
                    name = "onnx",
                    type = "onnx",
                    modelType = "yolonas",
                    modelFileName = null,
                    inputWidth = 320,
                    inputHeight = 320,
                    inferenceMs = 6.84,
                ),
                gpus = listOf(GpuLoad("Intel QSV", gpuPercent = 23.0, memoryPercent = null, decoderPercent = 12.0)),
            ),
        ),
    ) {
        onNodeWithText("DETECTOR").assertIsDisplayed()
        onNodeWithText("onnx · yolonas 320×320").assertIsDisplayed()
        onNodeWithText("Inference 6.8 ms per frame").assertIsDisplayed()
        onNodeWithText("GPU").assertIsDisplayed()
        onNodeWithText("Intel QSV").assertIsDisplayed()
        // What the GPU didn't report is left out rather than shown as a dash.
        onNodeWithText("23% busy · 12% decoder").assertIsDisplayed()
    }

    @Test
    fun aDetectorThatHasNotRunYetSaysSo() = runServer(
        SettingsUiState(
            overview = overview(
                detector = DetectorInfo("cpu", "cpu", modelType = null, modelFileName = null, inputWidth = null, inputHeight = null, inferenceMs = null),
            ),
        ),
    ) {
        onNodeWithText("cpu").assertIsDisplayed()
        onNodeWithText("No inference yet").assertIsDisplayed()
    }

    @Test
    fun aFailedRefreshKeepsTheLastReadingAndSaysSo() = runServer(
        SettingsUiState(overview = overview(), overviewError = "HTTP 502"),
    ) {
        onNodeWithText("0.16.0").assertIsDisplayed()
        onNodeWithText("Last refresh failed: HTTP 502").assertIsDisplayed()
        // The numbers are still good, so this is a note, not the full-stop "couldn't reach" row.
        onAllNodesWithText("Retry").assertCountEquals(0)
    }

    // ---- Storage & retention -------------------------------------------------------------------

    @Test
    fun storageSaysItIsReadingBeforeTheOverviewArrives() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            FrigatePreview {
                StorageSection(overview = null)
            }
        }

        onNodeWithText("Storage & Retention").assertIsDisplayed()
        onNodeWithText("Reading disk usage…").assertIsDisplayed()
        onAllNodesWithText("CONTINUOUS").assertCountEquals(0)
    }

    @Test
    fun storageShowsTheRecordingsDiskAndHowLongEachKindIsKept() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            FrigatePreview {
                StorageSection(overview = overview())
            }
        }

        onNodeWithText("Recordings (/media/frigate/recordings)").assertIsDisplayed()
        onNodeWithText("41% used").assertIsDisplayed()
        onNodeWithText("412.7 GB used").assertIsDisplayed()
        onNodeWithText("1 TB total").assertIsDisplayed()
        onNodeWithText("CONTINUOUS").assertIsDisplayed()
        onNodeWithText("1 day").assertIsDisplayed()
        onNodeWithText("7 days").assertIsDisplayed()
        onNodeWithText("30 days").assertIsDisplayed()
        // No event retention of its own means Frigate's default applies, and it says so.
        onNodeWithText("Default").assertIsDisplayed()
        onNodeWithText("Frigate deletes recordings older than these on its own. Retention is set in its config.yml.").assertIsDisplayed()
    }

    @Test
    fun aServerWithNoRecordingsDiskSaysSoAndStillShowsRetention() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            FrigatePreview {
                StorageSection(overview = overview(recordingsStorage = null))
            }
        }

        onNodeWithText("The server didn't report its recordings disk.").assertIsDisplayed()
        onAllNodesWithText("used", substring = true).assertCountEquals(0)
        onNodeWithText("7 days").assertIsDisplayed()
    }

    // ---- AI features ---------------------------------------------------------------------------

    @Test
    fun aiFeaturesWaitForTheConfig() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            FrigatePreview {
                AiFeaturesSection(overview = null)
            }
        }

        onNodeWithText("AI Features").assertIsDisplayed()
        onNodeWithText("Reading the server's config…").assertIsDisplayed()
        onAllNodesWithText("FACE RECOGNITION").assertCountEquals(0)
    }

    @Test
    fun aiFeaturesReadOnOrOffOneByOne() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            FrigatePreview {
                AiFeaturesSection(overview = overview())
            }
        }

        onNodeWithText("FACE RECOGNITION").assertIsDisplayed()
        onNodeWithText("LICENSE PLATE RECOGNITION").assertIsDisplayed()
        onNodeWithText("SEMANTIC SEARCH").assertIsDisplayed()
        // Faces and semantic search on, plates off.
        onAllNodesWithText("On").assertCountEquals(2)
        onAllNodesWithText("Off").assertCountEquals(1)
        onNodeWithText("These AI features are set in Frigate's config.yml and need a Frigate restart to change.").assertIsDisplayed()
    }

    private companion object {
        /** Longer than a switch's thumb animation and the frames either side of it. */
        const val SETTLE_MS = 1_000L
    }
}
