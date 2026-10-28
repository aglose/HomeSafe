package com.meticulouscreations.homesafe.uitest

import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.v2.runComposeUiTest
import com.meticulouscreations.homesafe.domain.model.Camera
import com.meticulouscreations.homesafe.domain.model.CarFix
import com.meticulouscreations.homesafe.domain.model.ConnectionProblem
import com.meticulouscreations.homesafe.domain.model.Door
import com.meticulouscreations.homesafe.domain.model.HouseholdCar
import com.meticulouscreations.homesafe.ui.preview.FrigatePreview
import com.meticulouscreations.homesafe.ui.screens.HomeFeed
import com.meticulouscreations.homesafe.viewmodel.CameraTile
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The home grid, rendered for real on every target this source set reaches — the JVM and the iOS
 * simulator. [HomeFeed] is the stateless half of the home screen, so it can be driven straight
 * from fixtures with no graph behind it.
 *
 * Every test here sets `mainClock.autoAdvance = false`. The feed runs animations that never end —
 * `rememberLoadingPhase()` is a `while (true)` frame loop while the cameras are still loading, and
 * `PulsingDot` uses `rememberInfiniteTransition` — so the composition is never idle and anything
 * that waits for idle would hang rather than fail.
 */
@OptIn(ExperimentalTestApi::class)
class HomeFeedUiTest {

    private fun tile(name: String) = CameraTile(
        camera = Camera(name = name, enabled = true),
        streamUrl = "http://frigate.test/live/$name",
        posterUrl = "http://frigate.test/snapshot/$name",
    )

    @Test
    fun showsACardForEachCamera() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            FrigatePreview {
                HomeFeed(
                    everyoneAway = false,
                    cameras = listOf(tile("front_door"), tile("back_yard")),
                    onAwayBack = {},
                ) { tile -> Text(tile.camera.displayName) }
            }
        }

        onNodeWithText("Front Door").assertIsDisplayed()
        onNodeWithText("Back Yard").assertIsDisplayed()
    }

    @Test
    fun saysSoWhenTheServerHasNoCameras() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            FrigatePreview {
                HomeFeed(everyoneAway = false, cameras = emptyList(), onAwayBack = {}) { }
            }
        }

        onNodeWithText("No cameras found on this server.").assertIsDisplayed()
    }

    @Test
    fun showsNoEmptyMessageWhileCamerasAreStillLoading() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            FrigatePreview {
                // null, not emptyList: the grid must not flash "no cameras" on the way in.
                HomeFeed(everyoneAway = false, cameras = null, onAwayBack = {}) { }
            }
        }

        onAllNodesWithText("No cameras found on this server.").assertCountEquals(0)
    }

    @Test
    fun tailscaleBeingOffOnThisDeviceIsSaidAtTheTopOfThePage() {
        var retries = 0
        runComposeUiTest {
            mainClock.autoAdvance = false
            setContent {
                FrigatePreview {
                    HomeFeed(
                        everyoneAway = false,
                        cameras = listOf(tile("front_door")),
                        onAwayBack = {},
                        connectionProblem = ConnectionProblem.TailscaleOff,
                        onConnectionRetry = { retries++ },
                    ) { tile -> Text(tile.camera.displayName) }
                }
            }

            onNodeWithText("Tailscale isn't connected on this device").assertIsDisplayed()
            onNodeWithText("Open the Tailscale app and connect.", substring = true).assertIsDisplayed()
            // What the device kept is still there beneath it.
            onNodeWithText("Front Door").assertIsDisplayed()
            onNodeWithText("Try again").performClick()
        }
        assertEquals(1, retries)
    }

    @Test
    fun whereTheAppCanOpenTailscaleTheNoticeOffersTo() {
        var opened = 0
        runComposeUiTest {
            mainClock.autoAdvance = false
            setContent {
                FrigatePreview {
                    HomeFeed(
                        everyoneAway = false,
                        cameras = emptyList(),
                        onAwayBack = {},
                        connectionProblem = ConnectionProblem.TailscaleOff,
                        onOpenTailscale = { opened++ },
                    ) { }
                }
            }

            onNodeWithText("Open Tailscale").performClick()
            onNodeWithText("Try again").assertIsDisplayed()
        }
        assertEquals(1, opened)
    }

    @Test
    fun whereItCannotThereIsOnlyTryAgain() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            FrigatePreview {
                HomeFeed(everyoneAway = false, cameras = emptyList(), onAwayBack = {}, connectionProblem = ConnectionProblem.TailscaleOff) { }
            }
        }

        onAllNodesWithText("Open Tailscale").assertCountEquals(0)
        onNodeWithText("Try again").assertIsDisplayed()
    }

    @Test
    fun openingTailscaleIsNotOfferedForAServerThatIsSimplyNotAnswering() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            FrigatePreview {
                HomeFeed(
                    everyoneAway = false,
                    cameras = emptyList(),
                    onAwayBack = {},
                    connectionProblem = ConnectionProblem.ServerUnreachable,
                    onOpenTailscale = {},
                ) { }
            }
        }

        onAllNodesWithText("Open Tailscale").assertCountEquals(0)
    }

    @Test
    fun aServerThatIsNotAnsweringIsNotBlamedOnTailscale() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            FrigatePreview {
                HomeFeed(
                    everyoneAway = false,
                    cameras = emptyList(),
                    onAwayBack = {},
                    connectionProblem = ConnectionProblem.ServerUnreachable,
                    connectionRetrying = true,
                ) { }
            }
        }

        onNodeWithText("Can't reach the server").assertIsDisplayed()
        onAllNodesWithText("Tailscale isn't connected on this device").assertCountEquals(0)
        // Mid-retry the button says so and can't be pressed again.
        onNodeWithText("Trying…").assertIsNotEnabled()
    }

    @Test
    fun thereIsNoNoticeWhileTheServerAnswers() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            FrigatePreview {
                HomeFeed(everyoneAway = false, cameras = emptyList(), onAwayBack = {}) { }
            }
        }

        onAllNodesWithText("Try again").assertCountEquals(0)
        onAllNodesWithText("Can't reach the server").assertCountEquals(0)
    }

    @Test
    fun theAwayBannerAppearsOnlyWhenEveryoneIsAway() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            FrigatePreview {
                HomeFeed(everyoneAway = false, cameras = emptyList(), onAwayBack = {}) { }
            }
        }

        onAllNodesWithText("I'm back").assertCountEquals(0)
    }

    @Test
    fun thereIsNoGlanceRowWithNoCarsAndNoDoors() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            FrigatePreview {
                HomeFeed(everyoneAway = false, cameras = listOf(tile("front_door")), onAwayBack = {}) { }
            }
        }

        onAllNodesWithText("Sarah's Tesla").assertCountEquals(0)
        onAllNodesWithText("All doors closed").assertCountEquals(0)
    }

    @Test
    fun tappingATeslaOpensTheCarsMap() = runComposeUiTest {
        var opened = 0
        mainClock.autoAdvance = false
        setContent {
            FrigatePreview {
                HomeFeed(
                    everyoneAway = false,
                    cameras = listOf(tile("front_door")),
                    onAwayBack = {},
                    cars = listOf(
                        HouseholdCar(
                            id = "sarahs_car",
                            name = "Sarah's Tesla",
                            lastSeen = CarFix(37.77020, -122.25800, 90f, 1_789_408_000, moving = false, home = true),
                            parked = null,
                            asleep = false,
                        ),
                    ),
                    onOpenCars = { opened++ },
                ) { }
            }
        }

        onNodeWithText("Sarah's Tesla").assertIsDisplayed().performClick()

        assertEquals(1, opened)
    }

    @Test
    fun closedDoorsAreOnePillAndAnOpenOneHasItsOwn() = runComposeUiTest {
        var doorTaps = 0
        mainClock.autoAdvance = false
        setContent {
            FrigatePreview {
                HomeFeed(
                    everyoneAway = false,
                    cameras = listOf(tile("front_door")),
                    onAwayBack = {},
                    doors = listOf(Door("0x01", "Back door", open = true), Door("0x02", "Front door", open = false), Door("0x03", "Garage door", open = false)),
                    onOpenDoors = { doorTaps++ },
                ) { }
            }
        }

        onNodeWithText("Back door").assertIsDisplayed()
        onAllNodesWithText("Front door").assertCountEquals(0)
        onNodeWithText("2 doors closed").assertIsDisplayed().performClick()
        onNodeWithText("Back door").performClick()

        assertEquals(2, doorTaps)
    }

    @Test
    fun imBackReportsTheTapOnce() = runComposeUiTest {
        var backTaps = 0
        mainClock.autoAdvance = false
        setContent {
            FrigatePreview {
                HomeFeed(
                    everyoneAway = true,
                    cameras = emptyList(),
                    onAwayBack = { backTaps++ },
                ) { }
            }
        }

        onNodeWithText("I'm back").performClick()

        assertEquals(1, backTaps, "one tap on 'I'm back' should mark this device home exactly once")
    }

    @Test
    fun pullingTheListDownFromTheTopAsksForARefresh() = runComposeUiTest {
        mainClock.autoAdvance = false
        var refreshes = 0
        setContent {
            FrigatePreview {
                HomeFeed(
                    everyoneAway = false,
                    cameras = listOf(tile("front_door")),
                    onAwayBack = {},
                    modifier = Modifier.testTag("feed"),
                    onRefresh = { refreshes++ },
                ) { tile -> Text(tile.camera.displayName) }
            }
        }

        onNodeWithTag("feed").performTouchInput { swipeDown(startY = top + 10f, endY = bottom, durationMillis = 400) }
        mainClock.advanceTimeBy(1_000)

        assertEquals(1, refreshes)
    }
}
