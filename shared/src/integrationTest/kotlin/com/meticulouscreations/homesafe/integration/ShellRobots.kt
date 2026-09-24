package com.meticulouscreations.homesafe.integration

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.performTextReplacement
import com.meticulouscreations.homesafe.domain.model.ConnectionRoute
import com.meticulouscreations.homesafe.fakefrigate.FakeFrigateState
import com.meticulouscreations.homesafe.fakefrigate.FakeUser
import com.meticulouscreations.homesafe.navigation.TopLevelRoute
import com.meticulouscreations.homesafe.ui.screens.SIGN_IN_CONNECT_TEST_TAG
import com.meticulouscreations.homesafe.ui.screens.SIGN_IN_PASSWORD_TEST_TAG
import com.meticulouscreations.homesafe.ui.screens.SIGN_IN_SERVER_URL_TEST_TAG
import com.meticulouscreations.homesafe.ui.screens.SIGN_IN_USERNAME_TEST_TAG
import com.meticulouscreations.homesafe.ui.screens.bottomNavTestTag

/** The sign-in screen, the first thing every journey sees. */
@OptIn(ExperimentalTestApi::class)
internal class SignInRobot(private val journey: AppJourney) {

    /** Waits for the form to be up and ready to take a sign-in (Connect enabled). */
    fun awaitForm() {
        journey.awaitSingle(hasTestTag(SIGN_IN_CONNECT_TEST_TAG) and isEnabled(), "the enabled Connect button")
    }

    fun field(tag: String): SemanticsNodeInteraction = journey.awaitSingle(hasTestTag(tag), "the \"$tag\" field")

    fun enter(serverUrl: String, username: String, password: String) {
        field(SIGN_IN_SERVER_URL_TEST_TAG).performTextReplacement(serverUrl)
        field(SIGN_IN_USERNAME_TEST_TAG).performTextReplacement(username)
        field(SIGN_IN_PASSWORD_TEST_TAG).performTextReplacement(password)
        journey.settle()
    }

    fun connect() = journey.tap(hasTestTag(SIGN_IN_CONNECT_TEST_TAG), "the Connect button")

    /** Signs in to the fake server as [user] through the form, and waits for the shell. */
    fun signInAs(user: FakeUser = FakeFrigateState.ADMIN, serverUrl: String = journey.server.baseUrl) {
        awaitForm()
        enter(serverUrl, user.username, user.password)
        connect()
        journey.shell.awaitSignedIn()
    }

    /** Waits for the form to come back with an error containing [message]. */
    fun awaitError(message: String) {
        journey.awaitText(message, substring = true)
        awaitForm()
    }
}

/** The shell around the three tabs: its top bar and its bottom navigation. */
@OptIn(ExperimentalTestApi::class)
internal class ShellRobot(private val journey: AppJourney) {

    /**
     * Waits for the real shell: the top bar's route badge only appears once a connection is live
     * (the sign-in skeleton draws the same bar with a plain status icon), and the form is gone.
     */
    fun awaitSignedIn() {
        journey.awaitNode(hasText(ConnectionRoute.TAILSCALE.label), "the Tailscale route badge")
        journey.awaitGone(hasTestTag(SIGN_IN_CONNECT_TEST_TAG), "the sign-in form")
    }

    fun openTab(tab: TopLevelRoute) {
        journey.tap(hasTestTag(bottomNavTestTag(tab)), "the ${tab.label} tab")
        awaitSelected(tab)
    }

    fun awaitSelected(tab: TopLevelRoute) {
        journey.awaitNode(hasTestTag(bottomNavTestTag(tab)) and isSelected(), "the ${tab.label} tab, selected")
    }
}
