package com.meticulouscreations.homesafe.integration

import androidx.compose.ui.test.hasText
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * The Home tab after sign-in: one card per camera the server's config lists and the "In view
 * now" strip of parked cars — each read over HTTP from the fake server, cached in Room, and drawn
 * by the real view model.
 */
class HomeJourneyTest {

    @Test
    fun everyCameraOnTheServerGetsACard() = runAppJourney {
        val home = HomeRobot(this)
        signIn.signInAs()

        home.awaitCard("front_door", "Front Door")
        home.awaitCard("driveway", "Driveway")
        home.awaitCard("back_yard", "Back Yard")
    }

    @Test
    fun aCameraTheServerHasDisabledSaysSoOnItsCard() = runAppJourney {
        state.edit {
            val index = cameras.indexOfFirst { it.name == "back_yard" }
            cameras[index] = cameras[index].copy(enabled = false)
        }
        val home = HomeRobot(this)
        signIn.signInAs()

        home.awaitBadge("back_yard", "Back Yard", "Disabled")
        home.awaitCard("front_door", "Front Door")
        assertFalse(exists(hasText("Front Door") and hasText("Disabled")), "only the camera the server switched off says so")
    }

    @Test
    fun aServerWithNoCamerasSaysSoInsteadOfAnEmptyPage() = runAppJourney {
        state.edit { cameras.clear() }
        val home = HomeRobot(this)
        signIn.signInAs()

        home.awaitList()
        awaitText("No cameras found on this server.")
    }

    @Test
    fun theParkedCarInViewOpensItsPageAndThenTheCameraWatchingIt() = runAppJourney {
        val camera = CameraRobot(this)
        signIn.signInAs()

        // Sarah's Tesla stopped in the driveway's parking zone twenty minutes ago and is still there:
        // a pill at the top of Home, its details on the In View page.
        awaitText("Sarah's Tesla")
        // Let the row finish fading in before aiming a tap at it.
        settle()
        tapText("Sarah's Tesla")

        awaitText("In view now")
        awaitText("Sarah's Tesla")
        settle()
        tapText("Sarah's Tesla")

        camera.awaitOpen("Driveway")
    }
}
