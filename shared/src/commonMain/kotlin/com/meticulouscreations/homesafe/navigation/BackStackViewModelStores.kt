package com.meticulouscreations.homesafe.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.ViewModelStoreProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.rememberViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * View-model stores for the screens of one back stack: one per route, cleared once that route has
 * left the stack and its screen has left the composition (so a page sliding away still has its
 * view model for the length of its exit).
 *
 * Without them every `metroViewModel()` on a pushed page lands in the window's own store and lives
 * as long as the app does: a camera page opened once kept polling that camera's recordings for the
 * rest of the session, whatever was on screen.
 *
 * Navigation 3's `rememberViewModelStoreNavEntryDecorator` isn't used because it ties the stores
 * to the NavDisplay's composition. It clears all of them whenever the display leaves it (Home
 * under another tab, the shell under an app from the drawer), and misses a route popped while the
 * display was away (Home's stack is popped to its root just as it goes off screen). These are held
 * in the surrounding owner's store instead and follow the back stack itself, so they are made with
 * [rememberBackStackViewModelStores] above anything that can take the display away.
 *
 * Only the screens wrapped in [Scoped] get a store of their own. Anything else keeps the
 * surrounding one: a stack's root, which is never popped (and whose view models the shell's top
 * bar reads too), and pages that share their root's view model.
 */
@Stable
class BackStackViewModelStores internal constructor(private val holder: Holder) {

    /** [content] with a view-model store of its own for [route], a value on the back stack. */
    @Composable
    fun Scoped(route: Any, content: @Composable () -> Unit) {
        holder.track(route)
        val owner = rememberViewModelStoreOwner(key = route, provider = holder.provider)
        CompositionLocalProvider(LocalViewModelStoreOwner provides owner, content = content)
    }

    /**
     * The stores themselves, kept in the surrounding view-model store so they outlast any one
     * composition of the stack. A store that is still composed when its route goes is cleared only
     * when it is let go of ([ViewModelStoreProvider] counts who holds it).
     */
    internal class Holder : ViewModel() {
        val provider = ViewModelStoreProvider(parentStore = null as ViewModelStore?)
        private val routes = mutableSetOf<Any>()

        /** [route] is drawn with a store of its own (it is on the stack, or still on its way off). */
        fun track(route: Any) {
            routes.add(route)
        }

        /**
         * The back stack is now [live]: clears the store of every route that has left it, now or
         * once its screen has gone. (A route that has no store, like the root, clears nothing.)
         */
        fun retainOnly(live: Set<Any>) {
            val gone = routes.filterNot(live::contains)
            routes.clear()
            routes.addAll(live)
            gone.forEach(provider::clearKey)
        }

        override fun onCleared() {
            routes.clear()
            provider.clearAllKeys()
        }
    }
}

/**
 * The [BackStackViewModelStores] for [backStack], which [key] names among the stacks sharing the
 * surrounding view-model store. Call it where the stack's state lives, outside anything that stops
 * drawing the stack's NavDisplay for a while: it watches [backStack] for as long as it is composed,
 * and clears every store it holds when it leaves for good (signing out drops the whole shell).
 */
@Composable
fun rememberBackStackViewModelStores(backStack: List<Any>, key: String): BackStackViewModelStores {
    val holder = viewModel(key = "back-stack-view-models:$key") { BackStackViewModelStores.Holder() }
    LaunchedEffect(holder, backStack) {
        snapshotFlow { backStack.toSet() }.collect(holder::retainOnly)
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(holder, lifecycle) {
        onDispose {
            // A destroyed lifecycle is the window being recreated, which keeps its view models.
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)) holder.retainOnly(emptySet())
        }
    }
    return remember(holder) { BackStackViewModelStores(holder) }
}
