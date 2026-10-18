package com.meticulouscreations.homesafe.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The clearing rules of [BackStackViewModelStores]: a route's view models go once it has left the
 * back stack and nothing draws it any more, and not before.
 */
class BackStackViewModelStoresTest {

    private data class DetailRoute(val camera: String)

    private data object ListRoute

    /** A screen's view model that says when it has been cleared. */
    private class Probe : ViewModel() {
        var cleared = false

        init {
            addCloseable { cleared = true }
        }
    }

    private fun ViewModelStore.probe(): Probe =
        ViewModelProvider.create(this, viewModelFactory { initializer { Probe() } })[Probe::class]

    private val holder = BackStackViewModelStores.Holder()

    /** [route]'s view model, as its screen would ask for it. */
    private fun probeFor(route: Any): Probe {
        holder.track(route)
        return holder.provider.getOrCreate(route).probe()
    }

    @Test
    fun aPoppedPageNoLongerDrawnIsClearedAtOnce() {
        val detail = probeFor(DetailRoute("front"))
        holder.retainOnly(setOf(ListRoute, DetailRoute("front")))
        assertFalse(detail.cleared)

        holder.retainOnly(setOf(ListRoute))

        assertTrue(detail.cleared)
    }

    @Test
    fun aPageSlidingAwayKeepsItsViewModelUntilItIsGone() {
        val route = DetailRoute("front")
        val detail = probeFor(route)
        val drawn = holder.provider.acquireToken(route)

        holder.retainOnly(setOf(ListRoute))
        assertFalse(detail.cleared, "cleared while its exit was still on screen")

        drawn.close()
        assertTrue(detail.cleared)
    }

    @Test
    fun aPageStillOnTheStackOutlastsItsScreenLeavingTheComposition() {
        // Home under another tab, or the shell under an app from the drawer: the page isn't
        // drawn, but Back would still land on it.
        val route = DetailRoute("front")
        val detail = probeFor(route)
        holder.provider.acquireToken(route).close()
        holder.retainOnly(setOf(ListRoute, route))

        assertFalse(detail.cleared)
        assertSame(detail, holder.provider.getOrCreate(route).probe(), "came back to a new view model")
    }

    @Test
    fun aPagePoppedWhileItsStackWasAwayIsClearedWhenTheStackIsNextSeen() {
        // Each poll of the back stack sees only where it is now: Home's stack popped to its root
        // as another tab took over still lets its camera page go.
        val detail = probeFor(DetailRoute("front"))
        val zones = probeFor(DetailRoute("back"))
        holder.retainOnly(setOf(ListRoute, DetailRoute("front"), DetailRoute("back")))

        holder.retainOnly(setOf(ListRoute))

        assertTrue(detail.cleared)
        assertTrue(zones.cleared)
    }

    @Test
    fun theSameRouteOpenedAgainGetsAFreshViewModel() {
        val first = probeFor(DetailRoute("front"))
        holder.retainOnly(setOf(ListRoute))

        val second = probeFor(DetailRoute("front"))

        assertNotSame(first, second)
        assertFalse(second.cleared)
    }

    @Test
    fun eachRouteHasItsOwnStore() {
        val front = probeFor(DetailRoute("front"))
        val back = probeFor(DetailRoute("back"))
        holder.retainOnly(setOf(ListRoute, DetailRoute("back")))

        assertTrue(front.cleared)
        assertFalse(back.cleared)
    }
}
