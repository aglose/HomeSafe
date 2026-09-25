package com.meticulouscreations.homesafe.integration

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import com.meticulouscreations.homesafe.domain.model.ConnectionRoute
import com.meticulouscreations.homesafe.fakefrigate.FakeFrigateState
import com.meticulouscreations.homesafe.navigation.TopLevelRoute
import com.meticulouscreations.homesafe.ui.screens.SIGN_IN_CONNECT_TEST_TAG
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Signing in, end to end: the form, the real connection repository and Ktor client, and a server
 * that accepts, refuses, errors or isn't there at all. The first screen of every other journey,
 * so if these fail the rest will too — look here first.
 */
class SignInJourneyTest {

    @Test
    fun theRightPasswordOpensTheShellOnHomeWithTheServersCameras() = runAppJourney {
        signIn.signInAs()

        shell.awaitSelected(TopLevelRoute.Home)
        shell.awaitCameraCard("back_yard", "Back Yard")
        shell.awaitCameraCard("driveway", "Driveway")
        shell.awaitCameraCard("front_door", "Front Door")
        val login = server.requests.single { it.path == "/api/login" }
        assertTrue("\"user\":\"admin\"" in login.body, "the typed username is what was sent: $login")
    }

    @Test
    fun aWrongPasswordKeepsTheFormUpAndSaysWhy() = runAppJourney {
        signIn.awaitForm()
        signIn.enter(server.baseUrl, FakeFrigateState.ADMIN.username, "not-the-password")
        signIn.connect()

        signIn.awaitError("Login failed")
        assertFalse(exists(hasText(ConnectionRoute.TAILSCALE.label)), "no shell behind a refused password")
        assertFalse(server.received { it.path == "/api/config" }, "nothing past the login is asked for")
    }

    @Test
    fun aServerErrorOnLoginIsReportedRatherThanTreatedAsAWrongPassword() = runAppJourney {
        state.edit { failures["/api/login"] = 500 }
        signIn.awaitForm()
        signIn.enter(server.baseUrl, FakeFrigateState.ADMIN.username, FakeFrigateState.ADMIN.password)
        signIn.connect()

        signIn.awaitError("500")
    }

    @Test
    fun aServerThatIsNotThereLeavesTheFormUsable() = runAppJourney {
        signIn.awaitForm()
        // Port 9 (discard) on this machine: nothing listens, so the connection is refused outright.
        signIn.enter("http://127.0.0.1:9", FakeFrigateState.ADMIN.username, FakeFrigateState.ADMIN.password)
        signIn.connect()

        // The failure comes back after the LAN probe gives up (at most a couple of seconds), so
        // give it time to have happened rather than catching the form before it was ever left.
        val tapped = TimeSource.Monotonic.markNow()
        awaitUntil("the form to be back and usable after the failed attempt", timeout = 30.seconds) {
            tapped.elapsedNow() > 4.seconds && exists(hasTestTag(SIGN_IN_CONNECT_TEST_TAG) and isEnabled())
        }
        assertFalse(exists(hasText(ConnectionRoute.TAILSCALE.label)), "no shell without a server")
        assertTrue(server.requests.isEmpty(), "the fake server was never involved: ${server.requests}")
    }

    @Test
    fun whileTheServerIsThinkingTheShellSkeletonStandsInForTheForm() = runAppJourney {
        state.edit { loginDelayMillis = 4_000 }
        signIn.awaitForm()
        signIn.enter(server.baseUrl, FakeFrigateState.ADMIN.username, FakeFrigateState.ADMIN.password)
        signIn.connect()

        // The skeleton is the shell's own chrome with a status icon where the route badge will go.
        awaitNode(hasContentDescription("Status"), "the skeleton's status icon")
        awaitGone(hasTestTag(SIGN_IN_CONNECT_TEST_TAG), "the sign-in form")
        assertFalse(exists(hasText(ConnectionRoute.TAILSCALE.label)), "not signed in yet")

        shell.awaitSignedIn()
        shell.awaitCameraCard("back_yard", "Back Yard")
    }

    @Test
    fun aViewerAccountSignsInToo() = runAppJourney {
        signIn.signInAs(FakeFrigateState.VIEWER)

        shell.awaitCameraCard("back_yard", "Back Yard")
        val login = server.requests.single { it.path == "/api/login" }
        assertTrue("\"user\":\"viewer\"" in login.body, "$login")
    }

    @Test
    fun signingInSendsThePasswordExactlyOnce() = runAppJourney {
        signIn.signInAs()
        shell.awaitCameraCard("back_yard", "Back Yard")

        assertEquals(1, server.requests.count { it.path == "/api/login" }, "one login: ${server.requests}")
    }
}
