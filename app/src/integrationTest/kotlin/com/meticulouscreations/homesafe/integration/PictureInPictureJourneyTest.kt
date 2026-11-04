package com.meticulouscreations.homesafe.integration

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import com.meticulouscreations.homesafe.navigation.TopLevelRoute
import com.meticulouscreations.homesafe.ui.screens.FULL_SCREEN_CHROME_TEST_TAG
import com.meticulouscreations.homesafe.ui.screens.bottomNavTestTag
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * A camera in Android's floating mini player, picture-in-picture: the window is the video and
 * nothing else — no page, no nav, none of the full-screen player's controls, which the system's own
 * window controls stand in for — and growing back gives the camera's page back as it was.
 *
 * The mini player is the journey's own (see [AppJourney.floatInMiniPlayer]): the system shrinking
 * the real Activity is a device's to show.
 */
class PictureInPictureJourneyTest {

    @Test
    fun theMiniPlayerIsTheCamerasVideoAloneAndGrowingBackGivesThePageBack() = runAppJourney {
        val home = HomeRobot(this)
        val camera = CameraRobot(this)
        signIn.signInAs()
        home.openCamera("driveway", "Driveway")

        floatInMiniPlayer(on = true)

        awaitGone(hasText(CameraRobot.RECENT_ACTIVITY), "the page under the player")
        awaitGone(hasTestTag(bottomNavTestTag(TopLevelRoute.Home)), "the shell's nav")
        awaitGone(hasContentDescription("Back"), "the page's and the chrome's Back buttons")
        awaitGone(hasContentDescription("More options"), "the camera's menu")
        assertFalse(exists(hasTestTag(FULL_SCREEN_CHROME_TEST_TAG)), "the full-screen player's controls stay off the mini player")

        floatInMiniPlayer(on = false)

        camera.awaitOpen("Driveway")
        shell.awaitSelected(TopLevelRoute.Home)
    }
}
