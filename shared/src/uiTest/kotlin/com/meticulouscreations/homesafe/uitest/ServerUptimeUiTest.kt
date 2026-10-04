package com.meticulouscreations.homesafe.uitest

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import com.meticulouscreations.homesafe.domain.model.ConnectionRoute
import com.meticulouscreations.homesafe.domain.model.ServerUptime
import com.meticulouscreations.homesafe.domain.model.UptimeCheck
import com.meticulouscreations.homesafe.domain.model.UptimeDevice
import com.meticulouscreations.homesafe.domain.model.UptimeOutage
import com.meticulouscreations.homesafe.domain.model.UptimeRange
import com.meticulouscreations.homesafe.domain.model.UptimeState
import com.meticulouscreations.homesafe.text.asUiText
import com.meticulouscreations.homesafe.ui.preview.FrigatePreview
import com.meticulouscreations.homesafe.ui.screens.ServerUptimeContent
import com.meticulouscreations.homesafe.viewmodel.ServerUptimeUiState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The uptime screen from fixtures: what it says is down, each check's figures, what a tapped
 * span says, and its range and retry controls. Times of day depend on the machine's zone, so the
 * tests match around them rather than on them.
 */
@OptIn(ExperimentalTestApi::class)
class ServerUptimeUiTest {

    private fun states(pattern: String) = pattern.map(UptimeState::fromLetter)

    private val until = 1_791_147_600L

    private val uptime = ServerUptime(
        sinceEpochSeconds = until - 86_400,
        untilEpochSeconds = until,
        bucketSeconds = 21_600.0,
        recordingSinceEpochSeconds = until - 9 * 86_400,
        checks = listOf(
            UptimeCheck("server", "Server running", states("uuuu"), 1.0, 0, true),
            UptimeCheck("internet", "Internet", states("uxuu"), 0.9722, 2_400, true),
            UptimeCheck("live", "Live video (go2rtc)", states("uuud"), 0.9972, 240, false),
            UptimeCheck("zigbee", "Zigbee hub", states("nnnn"), null, 0, null),
        ),
        devices = listOf(
            UptimeDevice("iphone-15-pro", "iOS", true, until - 60, states("uuuu")),
            UptimeDevice("pixel-10-pro-xl", "android", false, until - 13 * 3_600, states("uuxx")),
        ),
        outages = listOf(UptimeOutage("live", until - 240, null, 240), UptimeOutage("internet", until - 50_400, until - 48_000, 2_400)),
    )

    private fun loaded(route: ConnectionRoute? = ConnectionRoute.LOCAL_NETWORK) = ServerUptimeUiState(isLoading = false, uptime = uptime, route = route)

    @Test
    fun itLeadsWithWhatIsDownNowAndHowItWasRead() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent { FrigatePreview { ServerUptimeContent(state = loaded(), onBack = {}, onRefresh = {}, onSelectRange = {}) } }

        onNodeWithText("Down now: Live video").assertIsDisplayed()
        onNodeWithText("Read over your home network.").assertIsDisplayed()
        onNodeWithText("100% up").assertExists()
        onNodeWithText("97% up · 40 minutes down").assertExists()
        onNodeWithText("99.7% up · 4 minutes down").assertExists()
    }

    @Test
    fun aCheckThatWasDownSaysWhatItChecksAndOneThatWasNotDoesNot() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent { FrigatePreview { ServerUptimeContent(state = loaded(), onBack = {}, onRefresh = {}, onSelectRange = {}) } }

        onNodeWithText("Checks that the house is online. Down means nothing reaches the box from outside.").assertExists()
        onAllNodesWithText("Checks that the box is on and keeping this record. A gap is a restart or a power cut.").assertCountEquals(0)
    }

    @Test
    fun aCheckThisBuildDoesNotKnowIsShownByTheRelaysNameAsNotMeasured() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent { FrigatePreview { ServerUptimeContent(state = loaded(), onBack = {}, onRefresh = {}, onSelectRange = {}) } }

        onNodeWithText("Zigbee hub").assertExists()
        // Once in the legend, once as this check's figures.
        onAllNodesWithText("Not measured").assertCountEquals(2)
    }

    @Test
    fun tappingASpanSaysWhichCheckWhenAndItsState() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent { FrigatePreview { ServerUptimeContent(state = loaded(), onBack = {}, onRefresh = {}, onSelectRange = {}) } }

        onNodeWithText("Tap a bar to see its time").assertExists()
        // The second of four spans: the one the internet was down for.
        onNodeWithContentDescription("Timeline for Internet").performTouchInput { click(Offset(width * 0.375f, height / 2f)) }
        mainClock.advanceTimeByFrame()

        onNodeWithText("Internet · ", substring = true).assertExists()
        onNodeWithText(" · Down", substring = true).assertExists()
        onAllNodesWithText("Tap a bar to see its time").assertCountEquals(0)
    }

    @Test
    fun aDeviceOffTheTailnetSaysWhenItWasLastSeen() = runComposeUiTest {
        // The clock runs here, unlike in the rest: the device rows are below the fold, and scrolling
        // to one with the clock frozen never settles (it ran the test JVM out of memory). Nothing
        // on the loaded screen animates without end, so there is nothing for a running clock to wait on.
        setContent { FrigatePreview { ServerUptimeContent(state = loaded(), onBack = {}, onRefresh = {}, onSelectRange = {}) } }

        onNodeWithText("pixel-10-pro-xl").assertExists()
        onNodeWithText("Last seen ", substring = true).assertExists()
        onNodeWithText("On the tailnet").assertExists()
        onNodeWithContentDescription("Timeline for pixel-10-pro-xl").performScrollTo().performTouchInput { click(Offset(width * 0.9f, height / 2f)) }
        onNodeWithText(" · Not on the tailnet", substring = true).assertExists()
    }

    @Test
    fun outagesAreListedNewestFirstWithTheOneStillGoingSayingSo() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent { FrigatePreview { ServerUptimeContent(state = loaded(), onBack = {}, onRefresh = {}, onSelectRange = {}) } }

        onNodeWithText("still down after 4 minutes", substring = true).assertExists()
        // The internet's row says "97% up · 40 minutes down"; its outage ends on the length alone.
        onAllNodesWithText(" · 40 minutes", substring = true).assertCountEquals(2)
    }

    @Test
    fun choosingARangeAsksForIt() {
        val chosen = mutableListOf<UptimeRange>()
        runComposeUiTest {
            mainClock.autoAdvance = false
            setContent { FrigatePreview { ServerUptimeContent(state = loaded(), onBack = {}, onRefresh = {}, onSelectRange = { chosen += it }) } }

            onNodeWithText("7 days").performClick()
            onNodeWithText("30 days").performClick()
        }
        assertEquals(listOf(UptimeRange.Week, UptimeRange.Month), chosen)
    }

    @Test
    fun aFailureWithNothingToShowOffersARetry() {
        var retries = 0
        runComposeUiTest {
            mainClock.autoAdvance = false
            setContent {
                FrigatePreview {
                    ServerUptimeContent(
                        state = ServerUptimeUiState(isLoading = false, error = "Relay answered 502 Bad Gateway".asUiText()),
                        onBack = {},
                        onRefresh = { retries++ },
                        onSelectRange = {},
                    )
                }
            }

            onNodeWithText("Relay answered 502 Bad Gateway").assertIsDisplayed()
            onNodeWithText("Retry").performClick()
        }
        assertEquals(1, retries)
    }

    @Test
    fun aFailedRefreshKeepsTheRecordOnScreen() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            FrigatePreview {
                ServerUptimeContent(state = loaded().copy(error = "Connection refused".asUiText()), onBack = {}, onRefresh = {}, onSelectRange = {})
            }
        }

        onNodeWithText("Connection refused").assertIsDisplayed()
        onNodeWithText("Down now: Live video").assertExists()
    }

    @Test
    fun theFirstLoadSaysItIsReading() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent { FrigatePreview { ServerUptimeContent(state = ServerUptimeUiState(), onBack = {}, onRefresh = {}, onSelectRange = {}) } }

        onNodeWithText("Reading the uptime record…").assertIsDisplayed()
        onAllNodesWithText("Retry").assertCountEquals(0)
    }
}
