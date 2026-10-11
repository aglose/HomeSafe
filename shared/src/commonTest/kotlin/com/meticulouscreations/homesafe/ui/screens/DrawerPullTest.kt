package com.meticulouscreations.homesafe.ui.screens

import com.meticulouscreations.homesafe.navigation.TopLevelRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Where the drawer goes when the swipe that drew it out lets go, and where that swipe is on offer. */
class DrawerPullTest {

    private fun pulledTo(nav: ShellNavigation, fraction: Float) = nav.drawerPull.apply {
        start()
        dragBy(fraction)
    }

    @Test
    fun letGoPastAThirdOfTheWayItOpens() {
        val nav = ShellNavigation()
        pulledTo(nav, 0.4f).letGo(velocity = 0f)
        assertTrue(nav.drawerOpen)
        assertFalse(nav.drawerPull.active)
    }

    @Test
    fun letGoShortOfThatItShuts() {
        val nav = ShellNavigation()
        pulledTo(nav, 0.2f).letGo(velocity = 0f)
        assertFalse(nav.drawerOpen)
        assertFalse(nav.drawerPull.active)
    }

    @Test
    fun aFlickDecidesItWhicheverWayItWasGoing() {
        val out = ShellNavigation()
        pulledTo(out, 0.05f).letGo(velocity = 2_000f)
        assertTrue(out.drawerOpen)

        val back = ShellNavigation()
        pulledTo(back, 0.9f).letGo(velocity = -2_000f)
        assertFalse(back.drawerOpen)
    }

    @Test
    fun aSwipeTakenAwayFromTheFingerNeverOpensIt() {
        val nav = ShellNavigation()
        pulledTo(nav, 0.9f).letGo(velocity = null)
        assertFalse(nav.drawerOpen)
        assertFalse(nav.drawerPull.active)
    }

    @Test
    fun itStopsAtTheDrawersWidthAndAtItsEdge() {
        val nav = ShellNavigation()
        val pull = pulledTo(nav, 3f)
        assertEquals(1f, pull.fraction)
        pull.dragBy(-5f)
        assertEquals(0f, pull.fraction)
        assertTrue(pull.active, "still the finger's until it lifts")
    }

    @Test
    fun theDrawerCountsAsShowingFromTheMomentItIsPulled() {
        val nav = ShellNavigation()
        assertFalse(nav.drawerShowing)
        pulledTo(nav, 0.1f)
        assertTrue(nav.drawerShowing)
        nav.drawerPull.letGo(velocity = 0f)
        assertFalse(nav.drawerShowing)
    }

    @Test
    fun itIsOnOfferOnlyAtATabsRoot() {
        val nav = ShellNavigation()
        assertNotNull(nav.drawerPullOn(TopLevelRoute.Home))
        assertNotNull(nav.drawerPullOn(TopLevelRoute.Moments))

        nav.homeBackStack.add("front_door")
        assertNull(nav.drawerPullOn(TopLevelRoute.Home))
        assertNotNull(nav.drawerPullOn(TopLevelRoute.Settings))
    }
}
