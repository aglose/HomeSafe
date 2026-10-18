package com.meticulouscreations.homesafe.uitest

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.meticulouscreations.homesafe.navigation.BackStackViewModelStores
import com.meticulouscreations.homesafe.navigation.rememberBackStackViewModelStores
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [BackStackViewModelStores] under a real NavDisplay, wired as the shell wires Home's stack: the
 * stores are made outside the part of the tree that can stop drawing the display, the root keeps
 * the surrounding store, and a camera page pushed above it has its own.
 */
@OptIn(ExperimentalTestApi::class)
class BackStackViewModelStoresUiTest {

    private data object ListRoute

    private data class CameraRoute(val camera: String)

    /** A screen's view model that says when it has been cleared. */
    class Probe : ViewModel() {
        var cleared = false

        override fun onCleared() {
            cleared = true
        }
    }

    private class Host {
        val backStack = mutableStateListOf<Any>(ListRoute)

        /** False while something covers the display, as an app from the drawer covers the shell. */
        var drawn by mutableStateOf(true)
        val windowStore = ViewModelStore()
        val window = object : ViewModelStoreOwner {
            override val viewModelStore = windowStore
        }
        val made = mutableMapOf<Any, MutableList<Probe>>()
        var listProbe: Probe? = null
    }

    private fun ComposeUiTest.setUp(host: Host) {
        setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides host.window) {
                val stores = rememberBackStackViewModelStores(host.backStack, key = "test")
                if (host.drawn) {
                    NavDisplay(
                        backStack = host.backStack,
                        onBack = { host.backStack.removeLastOrNull() },
                        entryProvider = entryProvider {
                            entry<ListRoute> {
                                host.listProbe = viewModel { Probe() }
                                Text("list")
                            }
                            entry<CameraRoute> { route ->
                                stores.Scoped(route) {
                                    val probe = viewModel { Probe() }
                                    val made = host.made.getOrPut(route) { mutableListOf() }
                                    if (made.lastOrNull() !== probe) made += probe
                                    Text(route.camera)
                                }
                            }
                        },
                    )
                }
            }
        }
        waitForIdle()
    }

    @Test
    fun aCameraPageIsClearedOncePopped() = runComposeUiTest {
        val host = Host()
        setUp(host)
        val route = CameraRoute("front")
        runOnIdle { host.backStack.add(route) }
        waitForIdle()
        val probe = host.made.getValue(route).single()
        assertFalse(probe.cleared)

        runOnIdle { host.backStack.removeLastOrNull() }
        waitForIdle()

        assertTrue(probe.cleared, "the camera page's view model outlived the page")
    }

    @Test
    fun theRootKeepsTheWindowsStore() = runComposeUiTest {
        val host = Host()
        setUp(host)
        val list = host.listProbe!!
        runOnIdle { host.backStack.add(CameraRoute("front")) }
        waitForIdle()
        runOnIdle { host.backStack.removeLastOrNull() }
        waitForIdle()

        assertSame(list, host.listProbe)
        assertFalse(list.cleared)
    }

    @Test
    fun aPageUnderACoverKeepsItsViewModel() = runComposeUiTest {
        val host = Host()
        setUp(host)
        val route = CameraRoute("front")
        runOnIdle { host.backStack.add(route) }
        waitForIdle()
        val probe = host.made.getValue(route).single()

        runOnIdle { host.drawn = false }
        waitForIdle()
        assertFalse(probe.cleared, "covering the display cleared a page still on its stack")
        runOnIdle { host.drawn = true }
        waitForIdle()

        assertEquals(listOf(probe), host.made.getValue(route))
    }

    @Test
    fun aPagePoppedWhileCoveredIsClearedAtOnce() = runComposeUiTest {
        // Home's stack is popped to its root just as another tab takes over from it.
        val host = Host()
        setUp(host)
        val route = CameraRoute("front")
        runOnIdle { host.backStack.add(route) }
        waitForIdle()
        val probe = host.made.getValue(route).single()
        runOnIdle { host.drawn = false }
        waitForIdle()

        runOnIdle { host.backStack.removeLastOrNull() }
        waitForIdle()

        assertTrue(probe.cleared)
    }

    @Test
    fun theSameCameraOpenedAgainStartsAfresh() = runComposeUiTest {
        val host = Host()
        setUp(host)
        val route = CameraRoute("front")
        runOnIdle { host.backStack.add(route) }
        waitForIdle()
        runOnIdle { host.backStack.removeLastOrNull() }
        waitForIdle()
        runOnIdle { host.backStack.add(route) }
        waitForIdle()

        val (first, second) = host.made.getValue(route)
        assertNotSame(first, second)
        assertTrue(first.cleared)
        assertFalse(second.cleared)
    }
}
