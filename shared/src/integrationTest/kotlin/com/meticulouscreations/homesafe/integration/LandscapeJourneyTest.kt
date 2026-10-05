package com.meticulouscreations.homesafe.integration

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import com.meticulouscreations.homesafe.navigation.TopLevelRoute
import com.meticulouscreations.homesafe.ui.screens.DETECTION_ZONES_CANVAS_TEST_TAG
import com.meticulouscreations.homesafe.ui.screens.FULL_SCREEN_CHROME_TEST_TAG
import com.meticulouscreations.homesafe.ui.screens.HOME_FEED_TEST_TAG
import com.meticulouscreations.homesafe.ui.screens.NAV_RAIL_TEST_TAG
import com.meticulouscreations.homesafe.ui.screens.bottomNavTestTag
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.seconds

/**
 * The app with the phone turned on its side and back, mid-session: nothing is lost in the turn —
 * the sign-in, the tab, the camera that was open — and each screen takes the shape the window
 * calls for. The shell's nav stands down the side; a camera gives the whole window to its video,
 * with the page's controls on the picture instead of under it.
 *
 * The turn is the journey's own (see [AppJourney.turnPhone]); a real device rotating under the
 * real Activity is `AppE2eTest`'s to show.
 */
@OptIn(ExperimentalTestApi::class)
class LandscapeJourneyTest {

    private val chrome = hasTestTag(FULL_SCREEN_CHROME_TEST_TAG)

    @Test
    fun turningThePhoneMovesTheNavToTheSideAndKeepsTheTabThatWasUp() = runAppJourney {
        signIn.signInAs()
        shell.openTab(TopLevelRoute.Moments)
        assertFalse(exists(hasTestTag(NAV_RAIL_TEST_TAG)), "upright, the nav is the bar along the bottom")

        turnPhone(onItsSide = true)

        awaitTag(NAV_RAIL_TEST_TAG)
        shell.awaitSelected(TopLevelRoute.Moments)
        // The rail is the same nav: its tabs switch tabs.
        shell.openTab(TopLevelRoute.Settings)

        turnPhone(onItsSide = false)

        awaitGone(hasTestTag(NAV_RAIL_TEST_TAG), "the side nav")
        shell.awaitSelected(TopLevelRoute.Settings)
    }

    @Test
    fun aCameraTakesTheWholeWindowOnItsSideAndGivesThePageBackUpright() = runAppJourney {
        val home = HomeRobot(this)
        val camera = CameraRobot(this)
        signIn.signInAs()
        home.openCamera("driveway", "Driveway")

        turnPhone(onItsSide = true)

        // The page is gone and so is the shell's nav; the video's own chrome names the camera
        // and carries the way back.
        awaitNode(chrome, "the full-screen player's controls")
        awaitGone(hasText(CameraRobot.RECENT_ACTIVITY), "the page under the player")
        awaitGone(hasTestTag(bottomNavTestTag(TopLevelRoute.Home)), "the shell's nav")
        awaitText("Driveway")
        awaitNode(hasContentDescription("Back"), "the chrome's Back button")

        turnPhone(onItsSide = false)

        // The same camera, as a page again, under the nav.
        camera.awaitOpen("Driveway")
        awaitGone(chrome, "the full-screen player's controls")
        shell.awaitSelected(TopLevelRoute.Home)
    }

    @Test
    fun theFullScreenPlayersControlsFadeWhenLeftAloneAndATapBringsThemBack() = runAppJourney {
        val home = HomeRobot(this)
        signIn.signInAs()
        home.openCamera("driveway", "Driveway")
        turnPhone(onItsSide = true)
        awaitNode(chrome, "the full-screen player's controls")

        // Left alone, they get out of the picture's way.
        settle(6.seconds)
        awaitGone(chrome, "the full-screen player's controls")

        ui.onRoot().performTouchInput { click(center) }
        awaitNode(chrome, "the full-screen player's controls, back on a tap")

        // Paused, they stay: the picture isn't going anywhere.
        tap(hasContentDescription("Pause"), "the pause button")
        settle(6.seconds)
        awaitNode(hasContentDescription("Play"), "the play button, still up")
    }

    @Test
    fun backFromAFullScreenCameraReturnsToTheCameraListWithItsNavAtTheSide() = runAppJourney {
        val home = HomeRobot(this)
        signIn.signInAs()
        home.openCamera("back_yard", "Back Yard")
        turnPhone(onItsSide = true)
        awaitNode(chrome, "the full-screen player's controls")

        tap(hasContentDescription("Back"), "the chrome's Back button")

        awaitTag(HOME_FEED_TEST_TAG)
        awaitTag(NAV_RAIL_TEST_TAG)
        shell.awaitSelected(TopLevelRoute.Home)
    }

    @Test
    fun theFullScreenPlayersMenuStillReachesTheZoneEditor() = runAppJourney {
        val home = HomeRobot(this)
        signIn.signInAs()
        home.openCamera("driveway", "Driveway")
        turnPhone(onItsSide = true)
        awaitNode(chrome, "the full-screen player's controls")

        CameraRobot(this).openDetectionZones()

        // A page again, so the nav is back (at the side), with the frame and its tools side by side.
        awaitTag(DETECTION_ZONES_CANVAS_TEST_TAG)
        awaitTag(NAV_RAIL_TEST_TAG)
        awaitText("Parking")
    }
}
