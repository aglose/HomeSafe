package com.meticulouscreations.homesafe.integration

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import com.meticulouscreations.homesafe.fakefrigate.FakeEvent
import com.meticulouscreations.homesafe.navigation.TopLevelRoute
import com.meticulouscreations.homesafe.ui.screens.HOME_STATUS_TEST_TAG
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * The Home tab after sign-in: one card per camera the server's config lists, the summary at the
 * top built from the newest detection the app has fetched, and the "In view now" strip of parked
 * cars — each read over HTTP from the fake server, cached in Room, and drawn by the real view
 * model.
 */
class HomeJourneyTest {

    @Test
    fun everyCameraOnTheServerGetsACardAndTheSummaryCountsThemOn() = runAppJourney {
        val home = HomeRobot(this)
        signIn.signInAs()

        home.awaitCard("front_door", "Front Door")
        home.awaitCard("driveway", "Driveway")
        home.awaitCard("back_yard", "Back Yard")
        home.awaitSummary("3 cameras on", substring = true)
        assertFalse(exists(hasText("of 3 cameras on", substring = true)), "every camera is enabled on the server")
    }

    @Test
    fun theSummaryLeadsWithTheNewestDetectionAndWhereItHappened() = runAppJourney {
        val home = HomeRobot(this)
        signIn.signInAs()

        // The household's newest detection: a person in the front door's "porch" zone, minutes ago.
        home.awaitSummary("Person in the porch")
        home.awaitSummary("min ago", substring = true)
    }

    @Test
    fun aDetectionNewerThanTheRestTakesOverTheSummary() = runAppJourney {
        val start = System.currentTimeMillis() / 1000.0 - 45
        state.edit { events.add(FakeEvent(eventId(start, "cat001"), camera = "back_yard", label = "cat", startTime = start, endTime = start + 15)) }
        val home = HomeRobot(this)
        signIn.signInAs()

        // The back yard has no zones, so the summary says which camera saw it.
        home.awaitSummary("Cat at Back Yard")
        assertFalse(exists(hasTestTag(HOME_STATUS_TEST_TAG) and hasText("Person in the porch")), "the older front-door person is no longer the headline")
    }

    @Test
    fun tappingTheSummaryOpensTheMomentsItSummarises() = runAppJourney {
        val home = HomeRobot(this)
        signIn.signInAs()
        home.awaitSummary("Person in the porch")

        home.tapSummary()

        shell.awaitSelected(TopLevelRoute.Moments)
        awaitGone(hasTestTag(HOME_STATUS_TEST_TAG), "the home summary")
    }

    @Test
    fun aCameraTheServerHasDisabledSaysSoOnItsCardAndInTheSummary() = runAppJourney {
        state.edit {
            val index = cameras.indexOfFirst { it.name == "back_yard" }
            cameras[index] = cameras[index].copy(enabled = false)
        }
        val home = HomeRobot(this)
        signIn.signInAs()

        home.awaitSummary("2 of 3 cameras on", substring = true)
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
        assertFalse(exists(hasText("cameras on", substring = true)), "no camera count for a server without cameras")
    }

    @Test
    fun theParkedCarInViewOpensTheCameraWatchingIt() = runAppJourney {
        val camera = CameraRobot(this)
        signIn.signInAs()

        // Sarah's Tesla stopped in the driveway's parking zone twenty minutes ago and is still there.
        awaitText("In view now")
        awaitText("Sarah's Tesla")
        // Let the strip finish opening out before aiming a tap at it.
        settle()
        tapText("Sarah's Tesla")

        camera.awaitOpen("Driveway")
    }

    private fun eventId(startEpochSeconds: Double, suffix: String): String =
        "%.6f".format(Locale.ROOT, startEpochSeconds) + "-" + suffix
}
