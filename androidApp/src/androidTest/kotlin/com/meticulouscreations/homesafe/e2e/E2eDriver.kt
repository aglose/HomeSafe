package com.meticulouscreations.homesafe.e2e

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.meticulouscreations.homesafe.MainActivity
import com.meticulouscreations.homesafe.domain.model.ConnectionRoute
import com.meticulouscreations.homesafe.fakefrigate.FakeFrigateServer
import com.meticulouscreations.homesafe.fakefrigate.FakeFrigateState
import com.meticulouscreations.homesafe.fakefrigate.FakeUser
import com.meticulouscreations.homesafe.navigation.MomentDeepLink
import com.meticulouscreations.homesafe.navigation.TopLevelRoute
import com.meticulouscreations.homesafe.ui.screens.HOME_FEED_TEST_TAG
import com.meticulouscreations.homesafe.ui.screens.SIGN_IN_CONNECT_TEST_TAG
import com.meticulouscreations.homesafe.ui.screens.SIGN_IN_PASSWORD_TEST_TAG
import com.meticulouscreations.homesafe.ui.screens.SIGN_IN_SERVER_URL_TEST_TAG
import com.meticulouscreations.homesafe.ui.screens.SIGN_IN_USERNAME_TEST_TAG
import com.meticulouscreations.homesafe.ui.screens.bottomNavTestTag
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Drives the installed app — the real [MainActivity], launched the way the launcher or a
 * notification launches it — against a [FakeFrigateServer] running inside the test process
 * (which, under instrumentation, is the app's own process, so `127.0.0.1` reaches it).
 *
 * Like the shared module's integration journeys, the Compose clock is frozen for the whole test
 * (the app has frame loops that never go idle) and every wait pumps frames itself; a wait that
 * times out reports the semantics tree and the fake server's request log.
 */
class E2eDriver(val compose: ComposeTestRule, val server: FakeFrigateServer) {

    fun launch(intent: Intent? = null): ActivityScenario<MainActivity> =
        if (intent == null) ActivityScenario.launch(MainActivity::class.java) else ActivityScenario.launch(intent)

    /** A `homesafe://moment` link to [cameraName]'s detection at [startEpochSeconds], as a notification tap delivers it. */
    fun momentIntent(eventId: String, cameraName: String, startEpochSeconds: Double): Intent {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uri = MomentDeepLink(eventId = eventId, cameraName = cameraName, startEpochSeconds = startEpochSeconds).toUri()
        return Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setClass(context, MainActivity::class.java)
    }

    fun awaitUntil(description: String, timeout: Duration = DEFAULT_WAIT, condition: () -> Boolean) {
        val deadline = TimeSource.Monotonic.markNow() + timeout
        var lastError: Throwable? = null
        while (true) {
            compose.mainClock.advanceTimeByFrame()
            val met = try {
                condition()
            } catch (error: AssertionError) {
                lastError = error
                false
            } catch (error: IllegalStateException) {
                // Only "nothing composed yet" is worth waiting out; anything else is a real failure
                // (say, an exception thrown during layout) and swallowing it would hide the cause.
                if (error.message?.contains("compose hierarch", ignoreCase = true) != true) throw error
                lastError = error
                false
            }
            if (met) return
            if (deadline.hasPassedNow()) {
                throw AssertionError("Timed out after $timeout waiting for $description.\n${diagnostics()}", lastError)
            }
            Thread.sleep(POLL.inWholeMilliseconds)
        }
    }

    fun settle(duration: Duration = 1.seconds) {
        repeat((duration.inWholeMilliseconds / FRAME_MILLIS).toInt().coerceAtLeast(1)) { compose.mainClock.advanceTimeByFrame() }
    }

    fun count(matcher: SemanticsMatcher): Int = compose.onAllNodes(matcher).fetchSemanticsNodes().size

    fun exists(matcher: SemanticsMatcher): Boolean = count(matcher) > 0

    fun awaitNode(matcher: SemanticsMatcher, description: String = matcher.description, timeout: Duration = DEFAULT_WAIT) {
        awaitUntil("a node that is $description", timeout) { exists(matcher) }
    }

    fun awaitSingle(matcher: SemanticsMatcher, description: String = matcher.description): SemanticsNodeInteraction {
        awaitUntil("exactly one node that is $description") { count(matcher) == 1 }
        return compose.onNode(matcher)
    }

    fun awaitGone(matcher: SemanticsMatcher, description: String = matcher.description) {
        awaitUntil("no node to be $description") { !exists(matcher) }
    }

    fun awaitText(text: String) = awaitNode(hasText(text), "text \"$text\"")

    fun tap(matcher: SemanticsMatcher, description: String = matcher.description) {
        awaitSingle(matcher, description).performClick()
        settle(500.milliseconds)
    }

    fun awaitSignInForm() = awaitSingle(hasTestTag(SIGN_IN_CONNECT_TEST_TAG) and isEnabled(), "the enabled Connect button")

    fun signIn(user: FakeUser = FakeFrigateState.ADMIN) {
        awaitSignInForm()
        awaitSingle(hasTestTag(SIGN_IN_SERVER_URL_TEST_TAG)).performTextReplacement(server.baseUrl)
        awaitSingle(hasTestTag(SIGN_IN_USERNAME_TEST_TAG)).performTextReplacement(user.username)
        awaitSingle(hasTestTag(SIGN_IN_PASSWORD_TEST_TAG)).performTextReplacement(user.password)
        settle()
        // With the keyboard up, MainActivity (adjustResize, imePadding) shrinks the form's scroll
        // viewport and Connect sits below it, clipped: a tap there lands on the background. Do what
        // a person would — put the keyboard away and bring the button into view — then tap.
        Espresso.closeSoftKeyboard()
        settle()
        awaitSingle(hasTestTag(SIGN_IN_CONNECT_TEST_TAG)).performScrollTo()
        settle()
        tap(hasTestTag(SIGN_IN_CONNECT_TEST_TAG), "the Connect button")
        awaitSignedIn()
    }

    /** The real shell: the route badge replaces the skeleton's status icon, and the form is gone. */
    fun awaitSignedIn() {
        awaitNode(hasText(ConnectionRoute.TAILSCALE.label), "the Tailscale route badge")
        awaitGone(hasTestTag(SIGN_IN_CONNECT_TEST_TAG), "the sign-in form")
    }

    fun openTab(tab: TopLevelRoute) {
        tap(hasTestTag(bottomNavTestTag(tab)), "the ${tab.label} tab")
        awaitSelected(tab)
    }

    fun awaitSelected(tab: TopLevelRoute) = awaitNode(hasTestTag(bottomNavTestTag(tab)) and isSelected(), "the ${tab.label} tab, selected")

    /**
     * Scrolls Home's camera list to [cameraName]'s card and waits for its [displayName]: a phone
     * shows a card or two, and a lazy list composes nothing it isn't showing. A jump by key, not
     * a pixel scroll, which a lazy list animates and a frozen clock never finishes.
     */
    fun awaitCameraCard(cameraName: String, displayName: String) {
        awaitUntil("the $displayName card, scrolled to") {
            exists(hasText(displayName)) || run {
                // Throws while the list doesn't hold the key yet (still loading): try again next frame.
                runCatching { compose.onNode(hasTestTag(HOME_FEED_TEST_TAG)).performScrollToKey(cameraName) }
                false
            }
        }
    }

    /** A camera's own screen: its name between the Back button and the overflow menu. */
    fun awaitCameraScreen(displayName: String) {
        awaitNode(hasContentDescription("More options"), "the camera screen's overflow menu")
        awaitNode(hasContentDescription("Back"), "the camera screen's Back button")
        awaitText(displayName)
    }

    fun diagnostics(): String {
        val tree = runCatching { compose.onAllNodes(isRoot()).printToString(maxDepth = Int.MAX_VALUE) }.getOrElse { "<no semantics tree: $it>" }
        val requests = server.requests.takeLast(30).joinToString("\n  ", prefix = "  ")
        return "--- Semantics tree ---\n$tree\n--- Last requests to the fake server ---\n$requests\n--- Unhandled ---\n  ${server.unhandled}"
    }

    companion object {
        val DEFAULT_WAIT: Duration = 30.seconds
        private val POLL: Duration = 10.milliseconds
        private const val FRAME_MILLIS = 16L
    }
}
