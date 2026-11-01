package com.meticulouscreations.homesafe.integration

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import com.meticulouscreations.homesafe.fakefrigate.FakeFrigateState
import com.meticulouscreations.homesafe.finance.ui.FINANCE_LOCK_TEST_TAG
import com.meticulouscreations.homesafe.finance.ui.FINANCE_UNLOCK_TEST_TAG
import com.meticulouscreations.homesafe.ui.screens.SIGN_IN_APPS_TEST_TAG
import com.meticulouscreations.homesafe.ui.screens.SIGN_IN_CONNECT_TEST_TAG
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Weather, Fitness and Finance without the camera server: reached from the sign-in screen, before
 * any sign-in and while one is still being answered. None of these journeys signs in first, and
 * the first two never touch the server at all.
 */
class AppsWithoutServerJourneyTest {

    private fun AppJourney.openDrawerFromTheForm() {
        signIn.awaitForm()
        // Under the buttons, which on a short window is below the fold.
        scrollIntoView(hasTestTag(SIGN_IN_APPS_TEST_TAG), "the apps button under the form")
        tap(hasTestTag(SIGN_IN_APPS_TEST_TAG), "the apps button under the form")
        awaitTag("drawer_fitness")
    }

    @Test
    fun fitnessOpensFromTheSignInFormAndClosesBackToIt() = runAppJourney {
        openDrawerFromTheForm()
        assertTrue(exists(hasTestTag("drawer_weather")) && exists(hasTestTag("drawer_finance")) && exists(hasTestTag("drawer_atlas")), "all four apps are in the drawer")
        assertFalse(exists(hasText("Cameras")), "and nothing that needs the server: there are no cameras to go to yet")

        tap(hasTestTag("drawer_fitness"), "the Fitness card")
        awaitTag("fitness_app")

        tap(hasTestTag("fitness_back"), "Fitness's close button")
        awaitGone(hasTestTag("fitness_app"), "the fitness app")
        signIn.awaitForm()
        assertTrue(server.requests.isEmpty(), "the server was never asked for anything: ${server.requests}")
    }

    @Test
    fun financeOpensOnItsLockWhileThereIsNoServer() = runAppJourney {
        openDrawerFromTheForm()
        awaitText("Locked while offline")

        // The last of the drawer's cards: on a small screen it starts below the fold.
        scrollIntoView(hasTestTag("drawer_finance"), "the Finance card")
        tap(hasTestTag("drawer_finance"), "the Finance card")
        awaitTag(FINANCE_LOCK_TEST_TAG)
        assertFalse(exists(hasTestTag("finance_app")), "none of the app is drawn behind its lock")
        // A desktop or an emulator with no fingerprint enrolled has nothing to check with, and says so.
        if (!exists(hasTestTag(FINANCE_UNLOCK_TEST_TAG))) awaitText("no fingerprint or face unlock", substring = true)

        tap(hasTestTag("finance_back"), "the lock's close button")
        awaitGone(hasTestTag(FINANCE_LOCK_TEST_TAG), "the finance lock")
        signIn.awaitForm()
    }

    @Test
    fun aSignInThatLandsBehindAnOpenAppWaitsForItToBeClosed() = runAppJourney {
        state.edit { loginDelayMillis = 3_000 }
        signIn.awaitForm()
        signIn.enter(server.baseUrl, FakeFrigateState.ADMIN.username, FakeFrigateState.ADMIN.password)
        signIn.connect()

        // The skeleton's menu button works while the server is still thinking.
        awaitNode(hasContentDescription("Status"), "the skeleton's status icon")
        tap(hasTestTag("shell_menu"), "the skeleton's menu button")
        tap(hasTestTag("drawer_fitness"), "the Fitness card")
        awaitTag("fitness_app")

        // The sign-in finishes underneath: the cameras are fetched, and the app stays where it is.
        awaitUntil("the sign-in to land behind the fitness app", timeout = 30.seconds) { server.received { it.path == "/api/config" } }
        settle(2.seconds)
        assertTrue(exists(hasTestTag("fitness_app")), "the fitness app isn't taken away by the shell arriving")
        assertFalse(exists(hasText("Tailscale")), "and the shell waits")

        tap(hasTestTag("fitness_back"), "Fitness's close button")
        shell.awaitSignedIn()
        shell.awaitCameraCard("back_yard", "Back Yard")
    }
}
