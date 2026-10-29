package com.meticulouscreations.homesafe.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.application
import com.meticulouscreations.homesafe.PlatformContext
import com.meticulouscreations.homesafe.di.AppGraph
import com.meticulouscreations.homesafe.di.createAppGraph
import com.meticulouscreations.homesafe.navigation.ShellCommand
import com.meticulouscreations.homesafe.navigation.ShellCommands
import com.meticulouscreations.homesafe.navigation.TopLevelRoute
import com.meticulouscreations.homesafe.weather.data.DesktopWeatherRefresh
import java.awt.Desktop
import java.awt.desktop.AppReopenedListener

/** The desktop app's one graph, built before its window, as the iOS app's is. */
object DesktopApp {
    val graph: AppGraph by lazy { createAppGraph(platformContext = PlatformContext()) }
}

/**
 * The desktop app: one window with the whole app in it ([HomeSafeWindow]), the Mac's menu bar
 * across the top of the screen ([HomeSafeMenuBar]) and an icon in it that delivers the app's
 * notifications ([HomeSafeTray]).
 *
 * Closing the window doesn't quit: like Mail or Messages on a Mac, HomeSafe stays running, so the
 * cameras' alerts still arrive, until it is quit from its menu (⌘Q) or the menu-bar icon's.
 * Clicking it in the Dock brings the window back. One window only, as on the iPad: each would
 * build its own shell, racing for notification clicks.
 */
fun main() {
    // Before anything touches AWT: the menu bar at the top of the screen rather than in the window,
    // and the menu-bar icon drawn in the system's colour as the Mac's own are.
    System.setProperty("apple.laf.useScreenMenuBar", "true")
    System.setProperty("apple.awt.enableTemplateImages", "true")

    val graph = DesktopApp.graph
    // The weather's check while the app is open; the switch in the weather app turns it on and off.
    DesktopWeatherRefresh.register { graph.weatherAlertCheck }

    application {
        var windowVisible by remember { mutableStateOf(true) }
        val showWindow = { windowVisible = true }

        LaunchedEffect(Unit) {
            installMacHandlers(
                onReopen = showWindow,
                onSettings = {
                    showWindow()
                    ShellCommands.send(ShellCommand.Go(TopLevelRoute.Settings))
                },
                onQuit = ::exitApplication,
            )
        }

        HomeSafeTray(
            graph = graph,
            onOpen = showWindow,
            onQuit = ::exitApplication,
        )

        HomeSafeWindow(
            graph = graph,
            visible = windowVisible,
            // Away from a Mac, with no menu-bar icon to come back from, closing the window quits.
            onCloseRequest = { if (trayAvailable()) windowVisible = false else exitApplication() },
            onQuit = ::exitApplication,
        )
    }
}

/** The Mac's application menu and Dock: Settings… (⌘,), Quit, and a click on the Dock icon. */
private fun installMacHandlers(onReopen: () -> Unit, onSettings: () -> Unit, onQuit: () -> Unit) {
    if (!Desktop.isDesktopSupported()) return
    val desktop = Desktop.getDesktop()
    if (desktop.isSupported(Desktop.Action.APP_PREFERENCES)) desktop.setPreferencesHandler { onSettings() }
    if (desktop.isSupported(Desktop.Action.APP_QUIT_HANDLER)) {
        desktop.setQuitHandler { _, response ->
            onQuit()
            response.performQuit()
        }
    }
    if (desktop.isSupported(Desktop.Action.APP_EVENT_REOPENED)) desktop.addAppEventListener(AppReopenedListener { onReopen() })
}
