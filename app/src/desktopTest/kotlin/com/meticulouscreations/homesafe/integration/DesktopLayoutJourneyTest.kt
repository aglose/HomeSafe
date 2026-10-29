package com.meticulouscreations.homesafe.integration

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isSelected
import com.meticulouscreations.homesafe.navigation.TopLevelRoute
import com.meticulouscreations.homesafe.ui.screens.InnerApp
import com.meticulouscreations.homesafe.ui.screens.SIDEBAR_TEST_TAG
import com.meticulouscreations.homesafe.ui.screens.sidebarAppTestTag
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * The app in a laptop's window, as the desktop app opens: the shell's sidebar, and every
 * destination in it, each shown beside it. Every step leaves a picture under app/build/journeys.
 *
 * Weather, Atlas and Fitness each have a journey of their own. Their skies and backdrops are
 * runtime shaders, which a test window, with no GPU, draws on the CPU at about a second a frame:
 * walked one after another they outlast a journey's time limit on CI.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopLayoutJourneyTest {
    @Test
    fun theSidebarReachesEveryTabAndEveryApp() = runAppJourney(window = JourneyWindow.DESKTOP) {
        signIn.signInAs()
        awaitTag(SIDEBAR_TEST_TAG)
        shell.awaitSelected(TopLevelRoute.Home)
        settle()
        snapshot("home")

        shell.openTab(TopLevelRoute.Moments)
        settle()
        snapshot("moments")

        shell.openTab(TopLevelRoute.Settings)
        settle()
        snapshot("settings")

        openInSidebar(InnerApp.FINANCE)

        // Back to a tab from an app: the app makes way for it, and Home is as it was left.
        shell.openTab(TopLevelRoute.Home)
        settle()
        snapshot("home again")
    }

    @Test
    fun weatherBesideTheSidebar() = runAppJourney(window = JourneyWindow.DESKTOP) { signedInThenOpen(InnerApp.WEATHER) }

    @Test
    fun atlasBesideTheSidebar() = runAppJourney(window = JourneyWindow.DESKTOP) { signedInThenOpen(InnerApp.ATLAS) }

    @Test
    fun fitnessBesideTheSidebar() = runAppJourney(window = JourneyWindow.DESKTOP) { signedInThenOpen(InnerApp.FITNESS) }

    @Test
    fun theSignInScreenAtALaptopsSize() = runAppJourney(window = JourneyWindow.DESKTOP) {
        signIn.awaitForm()
        settle()
        snapshot("sign in")
    }

    private fun AppJourney.signedInThenOpen(app: InnerApp) {
        signIn.signInAs()
        awaitTag(SIDEBAR_TEST_TAG)
        openInSidebar(app)
    }

    /** [app]'s row in the sidebar, taken: the row is selected and the app drawn beside it. */
    private fun AppJourney.openInSidebar(app: InnerApp) {
        tap(hasTestTag(sidebarAppTestTag(app)), "${app.name.lowercase()} in the sidebar")
        awaitNode(hasTestTag(sidebarAppTestTag(app)) and isSelected(), "${app.name.lowercase()}, selected")
        // Long enough for the page to be in, not for its sky's animation, which costs a second a frame here.
        settle(APP_SETTLE)
        snapshot(app.name.lowercase())
    }

    private companion object {
        val APP_SETTLE = 300.milliseconds
    }
}
