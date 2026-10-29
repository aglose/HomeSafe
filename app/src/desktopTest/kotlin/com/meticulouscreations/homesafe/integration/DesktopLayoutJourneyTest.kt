package com.meticulouscreations.homesafe.integration

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isSelected
import com.meticulouscreations.homesafe.navigation.TopLevelRoute
import com.meticulouscreations.homesafe.ui.screens.InnerApp
import com.meticulouscreations.homesafe.ui.screens.SIDEBAR_TEST_TAG
import com.meticulouscreations.homesafe.ui.screens.sidebarAppTestTag
import kotlin.test.Test

/**
 * The app in a laptop's window, as the desktop app opens: the shell's sidebar, and every
 * destination in it, each shown beside it. Every step leaves a picture under app/build/journeys.
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

        for (app in listOf(InnerApp.WEATHER, InnerApp.ATLAS, InnerApp.FITNESS, InnerApp.FINANCE)) {
            tap(hasTestTag(sidebarAppTestTag(app)), "${app.name.lowercase()} in the sidebar")
            awaitNode(hasTestTag(sidebarAppTestTag(app)) and isSelected(), "${app.name.lowercase()}, selected")
            settle()
            snapshot(app.name.lowercase())
        }

        // Back to a tab from an app: the app makes way for it, and Home is as it was left.
        shell.openTab(TopLevelRoute.Home)
        settle()
        snapshot("home again")
    }

    @Test
    fun theSignInScreenAtALaptopsSize() = runAppJourney(window = JourneyWindow.DESKTOP) {
        signIn.awaitForm()
        settle()
        snapshot("sign in")
    }
}
