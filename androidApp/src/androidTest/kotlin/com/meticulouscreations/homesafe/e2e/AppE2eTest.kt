package com.meticulouscreations.homesafe.e2e

import android.content.Intent
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.meticulouscreations.homesafe.MainActivity
import com.meticulouscreations.homesafe.fakefrigate.FakeFrigateServer
import com.meticulouscreations.homesafe.fakefrigate.FakeFrigateState
import com.meticulouscreations.homesafe.navigation.TopLevelRoute
import com.meticulouscreations.homesafe.ui.screens.SIGN_IN_CONNECT_TEST_TAG
import com.meticulouscreations.homesafe.ui.screens.SIGN_IN_SERVER_URL_TEST_TAG
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The installed app end to end: the real [com.meticulouscreations.homesafe.MainActivity] with
 * its own app graph, edge-to-edge window, system back and intents, against a fake Frigate. Each
 * test starts from a freshly cleared app (the Test Orchestrator's `clearPackageData`, see
 * androidApp/build.gradle.kts), so nothing a previous test signed in to carries over.
 *
 * The screens themselves are covered in depth by the shared module's integration journeys; these
 * are the few things only the real Activity can show — the Android testing guidance's thin E2E
 * layer on top of many smaller tests.
 */
@RunWith(AndroidJUnit4::class)
class AppE2eTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var server: FakeFrigateServer
    private lateinit var app: E2eDriver

    @Before
    fun setUp() {
        server = FakeFrigateServer(FakeFrigateState.household()).start()
        app = E2eDriver(compose, server)
        compose.mainClock.autoAdvance = false
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun aColdLaunchOpensOnTheSignInForm() {
        app.launch().use {
            app.awaitSignInForm()
            app.awaitText("PERCYSAFE")
            // Nothing is asked of a server before the user names one.
            assertEquals(emptyList<Any>(), server.requests)
        }
    }

    @Test
    fun signingInOpensHomeWithTheServersCameras() {
        app.launch().use {
            app.signIn()

            app.awaitSelected(TopLevelRoute.Home)
            app.awaitCameraCard("back_yard", "Back Yard")
            app.awaitCameraCard("driveway", "Driveway")
            app.awaitCameraCard("front_door", "Front Door")
        }
    }

    @Test
    fun everyTabOpensFromTheBottomNav() {
        app.launch().use {
            app.signIn()

            app.openTab(TopLevelRoute.Moments)
            app.openTab(TopLevelRoute.Settings)
            app.openTab(TopLevelRoute.Home)
            app.awaitCameraCard("back_yard", "Back Yard")
        }
    }

    @Test
    fun systemBackFromACameraReturnsToTheCameraList() {
        app.launch().use {
            app.signIn()
            app.awaitCameraCard("back_yard", "Back Yard")
            app.tap(hasText("Back Yard"), "the Back Yard card")
            app.awaitCameraScreen("Back Yard")

            Espresso.pressBack()

            app.awaitGone(hasContentDescription("More options"), "the camera screen")
            app.awaitCameraCard("driveway", "Driveway")
            app.awaitSelected(TopLevelRoute.Home)
        }
    }

    @Test
    fun systemBackFromTheShellLeavesTheAppRatherThanReturningToSignIn() {
        app.launch().use { scenario ->
            app.signIn()

            Espresso.pressBackUnconditionally()

            // Finished, or (Android 12+ for a task's root) moved to the background — either way
            // the app is left rather than the back stack popping to the sign-in form it replaced.
            app.awaitUntil("the activity to leave the foreground") { !scenario.state.isAtLeast(Lifecycle.State.STARTED) }
        }
    }

    @Test
    fun aRelaunchOffersTheLastServerSignedInTo() {
        app.launch().use { app.signIn() }

        app.launch().use {
            app.awaitSignInForm()
            app.awaitNode(
                hasTestTag(SIGN_IN_SERVER_URL_TEST_TAG) and hasText(server.baseUrl),
                "the URL field holding ${server.baseUrl}",
            )
        }
    }

    @Test
    fun theSignInFormIsVisibleToUiAutomatorByResourceId() {
        app.launch().use {
            app.awaitSignInForm()
            // MainActivity turns on testTagsAsResourceId; this is what UiAutomator, Macrobenchmark
            // journeys and `android layout` (Android CLI journeys) find the form by.
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            assertNotNull(device.wait(Until.findObject(By.res(SIGN_IN_CONNECT_TEST_TAG)), 10_000))
            assertNotNull(device.findObject(By.res(SIGN_IN_SERVER_URL_TEST_TAG)))
        }
    }

    @Test
    fun aNotificationTapBeforeSignInLandsOnThatMomentOnceSignedIn() {
        val event = server.state.events.first { it.camera == "driveway" }
        app.launch(app.momentIntent(event.id, event.camera, event.startTime)).use {
            app.signIn()

            app.awaitCameraScreen("Driveway")
            app.awaitSelected(TopLevelRoute.Home)
        }
    }

    @Test
    fun aNotificationTapWhileRunningOpensThatMomentInTheSameActivity() {
        app.launch().use { scenario ->
            app.signIn()
            app.openTab(TopLevelRoute.Settings)
            val event = server.state.events.first { it.camera == "back_yard" }
            var running: MainActivity? = null
            var launchIntent: Intent? = null
            scenario.onActivity {
                running = it
                launchIntent = it.intent
            }

            // singleTask: the tap reaches the running Activity's onNewIntent, not a second instance.
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            context.startActivity(app.momentIntent(event.id, event.camera, event.startTime).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

            app.awaitCameraScreen("Back Yard")
            app.awaitSelected(TopLevelRoute.Home)
            // Not scenario.state: ActivityScenario follows its Activity by the Intent that launched
            // it, and onNewIntent's setIntent() swapped that out, so the scenario lost sight of it
            // at the pause that came before the new intent.
            app.awaitUntil("the one resumed MainActivity to be the instance that was already running") {
                resumedActivities().let { it.size == 1 && it.single() === running }
            }
            // Hand the launch Intent back so the scenario sees the Activity finish when it closes.
            InstrumentationRegistry.getInstrumentation().runOnMainSync { running?.intent = launchIntent }
        }
    }

    private fun resumedActivities(): List<android.app.Activity> {
        var resumed: List<android.app.Activity> = emptyList()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).toList()
        }
        return resumed
    }
}
