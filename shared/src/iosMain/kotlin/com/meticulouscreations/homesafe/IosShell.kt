package com.meticulouscreations.homesafe

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.window.ComposeUIViewController
import com.meticulouscreations.homesafe.di.AppGraph
import com.meticulouscreations.homesafe.navigation.TopLevelRoute
import com.meticulouscreations.homesafe.ui.screens.DebugAutofillCredentials
import com.meticulouscreations.homesafe.ui.screens.LocalNativeTabBar
import com.meticulouscreations.homesafe.ui.screens.SecureConnectionScreen
import com.meticulouscreations.homesafe.ui.screens.ShellNavigation
import com.meticulouscreations.homesafe.ui.screens.ShellTab
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationStateBackground
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.UIKit.UIViewController

/**
 * The shell under iOS 26's Liquid Glass tab bar (`ContentView.swift`, `LiquidGlassShell`).
 *
 * Below iOS 26 the whole app is one Compose view controller ([MainViewController]) and the
 * bottom nav is drawn by Compose. On iOS 26 the tab bar is SwiftUI's `TabView` — which is what
 * gets the glass, the blur of the content scrolling under it and the rest of the system's
 * behaviour for free — and each tab is its own Compose view controller showing [ShellTab] for
 * that tab, the arrangement JetBrains describes at
 * https://kotlinlang.org/docs/multiplatform/ios-liquid-glass.html. Nested navigation stays in
 * Compose: a camera opened from the grid, or a classifier from Settings, is pushed within the
 * tab's own Navigation 3 stack, so the shared-element video and the pooled players are
 * untouched by the change.
 *
 * The three tabs share one [ShellNavigation], so the Moments tab can land a detection on the
 * Home stack exactly as it does under the Compose nav; the one difference is that switching
 * to Home then goes through [onSelectTab] to the SwiftUI selection, since the bar is native.
 *
 * Sign-in is a fourth Compose view controller ([SignInViewController]) shown before the tabs
 * exist. Swift swaps it for the `TabView` when it reports `onConnected`.
 */
class IosShell {
    /** Set by Swift: called on the main thread when Compose wants [IosTab] up — the SwiftUI selection follows. */
    var onSelectTab: ((IosTab) -> Unit)? = null

    private val nav = ShellNavigation(onTabSelected = { route -> onSelectTab?.invoke(IosTab.of(route)) })
    private val controllers = HashMap<IosTab, UIViewController>()

    init {
        IosAppVisibility.install(IosApp.graph)
        startAppServices(IosApp.graph)
    }

    /**
     * The view controller for [tab], made on first ask and kept: SwiftUI asks again on every
     * tab switch, and a new composition each time would lose the tab's scroll position and
     * nested stack presentation, and rebuild its view models.
     */
    fun viewController(tab: IosTab): UIViewController = controllers.getOrPut(tab) {
        ComposeUIViewController {
            AppChrome(IosApp.graph) {
                CompositionLocalProvider(LocalNativeTabBar provides true) {
                    ShellTab(nav, tab.route)
                }
            }
        }
    }
}

/** The shell's tabs as Swift sees them, in the order they appear in the bar. */
enum class IosTab(internal val route: TopLevelRoute) {
    HOME(TopLevelRoute.Home),
    MOMENTS(TopLevelRoute.Moments),
    SETTINGS(TopLevelRoute.Settings),
    ;

    internal companion object {
        fun of(route: TopLevelRoute): IosTab = entries.first { it.route == route }
    }
}

/**
 * The sign-in screen for the iOS 26 host, on its own: [onConnected] is Swift's cue to bring
 * up the tabs. While the server authenticates the screen shows the shell's skeleton, which
 * under [LocalNativeTabBar] draws no bottom nav — the glass bar arrives with the tabs.
 *
 * PascalCase on purpose, like [MainViewController]: Kotlin/Native exports the name verbatim
 * as the Swift API.
 */
@Suppress("ktlint:standard:function-naming", "unused")
fun SignInViewController(
    onConnected: () -> Unit,
    debugAutofillCredentials: DebugAutofillCredentials? = null,
): UIViewController {
    IosAppVisibility.install(IosApp.graph)
    startAppServices(IosApp.graph)
    return ComposeUIViewController {
        AppChrome(IosApp.graph) {
            CompositionLocalProvider(LocalNativeTabBar provides true) {
                SecureConnectionScreen(debugAutofillCredentials = debugAutofillCredentials, onConnected = onConnected)
            }
        }
    }
}

/**
 * The app's visibility for the iOS 26 host, from UIKit rather than a Compose lifecycle.
 *
 * [App] drives [AppVisibility] from its root composable's lifecycle, which is right when there
 * is one root. This host has several, and a tab's view controller is stopped whenever another
 * tab is up — which is a tab going away, not the app, and must not release every live stream
 * on the stingy background window. UIKit's own notifications mean exactly what
 * [AppVisibility] means, and they are the two moments Compose's iOS lifecycle owner maps to
 * STARTED and STOPPED, so nothing downstream sees a different signal.
 */
internal object IosAppVisibility {
    private var installed = false

    /** Idempotent: the sign-in screen and the shell both call it, whichever comes first wins. */
    fun install(appGraph: AppGraph) {
        if (installed) return
        installed = true
        val center = NSNotificationCenter.defaultCenter
        center.addObserverForName(
            name = UIApplicationWillEnterForegroundNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { update(appGraph, visible = true) }
        center.addObserverForName(
            name = UIApplicationDidEnterBackgroundNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { update(appGraph, visible = false) }
        // A geofence crossing can relaunch the app in the background (see IosApp); the first
        // screen is then made without the foreground ever having been entered.
        update(appGraph, visible = UIApplication.sharedApplication.applicationState != UIApplicationStateBackground)
    }

    private fun update(appGraph: AppGraph, visible: Boolean) {
        AppVisibility.update(visible)
        appGraph.connectionRepository.onAppVisibilityChanged(visible = visible)
    }
}
