package com.meticulouscreations.homesafe.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.MenuScope
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meticulouscreations.homesafe.isMacOs
import com.meticulouscreations.homesafe.navigation.ShellCommand
import com.meticulouscreations.homesafe.navigation.ShellCommands
import com.meticulouscreations.homesafe.navigation.TopLevelRoute
import com.meticulouscreations.homesafe.ui.screens.InnerApp
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.atlas_drawer_title
import homesafe.shared.generated.resources.desktop_actual_size
import homesafe.shared.generated.resources.desktop_back
import homesafe.shared.generated.resources.desktop_close_window
import homesafe.shared.generated.resources.desktop_enter_full_screen
import homesafe.shared.generated.resources.desktop_exit_full_screen
import homesafe.shared.generated.resources.desktop_hide_sidebar
import homesafe.shared.generated.resources.desktop_menu_file
import homesafe.shared.generated.resources.desktop_menu_go
import homesafe.shared.generated.resources.desktop_menu_view
import homesafe.shared.generated.resources.desktop_menu_window
import homesafe.shared.generated.resources.desktop_minimize
import homesafe.shared.generated.resources.desktop_quit
import homesafe.shared.generated.resources.desktop_settings
import homesafe.shared.generated.resources.desktop_show_sidebar
import homesafe.shared.generated.resources.desktop_zoom_in
import homesafe.shared.generated.resources.desktop_zoom_out
import homesafe.shared.generated.resources.desktop_zoom_window
import homesafe.shared.generated.resources.fitness_drawer_title
import homesafe.shared.generated.resources.packages_drawer_title
import homesafe.shared.generated.resources.shell_drawer_finance
import homesafe.shared.generated.resources.shell_sidebar_assistant
import homesafe.shared.generated.resources.shell_tab_home
import homesafe.shared.generated.resources.shell_tab_moments
import homesafe.shared.generated.resources.shell_tab_settings
import homesafe.shared.generated.resources.weather_drawer_title
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The menu bar: on a Mac the one across the top of the screen, beside the application menu the
 * system draws (About, Settings… ⌘, and Quit ⌘Q, wired up in Main.kt).
 *
 * Every destination has a shortcut, in the order the sidebar lists them, as the iPad's Go menu
 * does: ⌘1 to ⌘3 for the tabs, ⌘4 to ⌘7 for the apps, ⌘K the assistant; ⌘[ is Back, as in Safari and Finder; View
 * has the sidebar (⌃⌘S), full screen (⌃⌘F) and zoom (⌘+, ⌘−, ⌘0). Elsewhere Ctrl stands in for ⌘.
 * Nothing here takes a key a text field uses (no Edit menu: the fields do cut, copy and paste
 * themselves), and the Go items are greyed out until there is a shell to go anywhere in.
 */
@Composable
fun FrameWindowScope.HomeSafeMenuBar(
    windowState: WindowState,
    zoom: UiZoom,
    onBack: () -> Unit,
    onCloseWindow: () -> Unit,
    onMinimize: () -> Unit,
    onQuit: () -> Unit,
) {
    val shellUp by ShellCommands.shellUp.collectAsStateWithLifecycle()
    val sidebarAvailable by ShellCommands.sidebarAvailable.collectAsStateWithLifecycle()
    val sidebarHidden by ShellCommands.sidebarHidden.collectAsStateWithLifecycle()
    MenuBar {
        Menu(stringResource(Res.string.desktop_menu_file)) {
            Item(stringResource(Res.string.desktop_close_window), shortcut = command(Key.W), onClick = onCloseWindow)
            if (!isMacOs) {
                // The Mac has these in its application menu; elsewhere File is where they live.
                Separator()
                Item(stringResource(Res.string.desktop_settings), shortcut = command(Key.Comma), enabled = shellUp) { go(TopLevelRoute.Settings) }
                Item(stringResource(Res.string.desktop_quit), shortcut = command(Key.Q), onClick = onQuit)
            }
        }
        Menu(stringResource(Res.string.desktop_menu_view)) {
            Item(
                stringResource(if (sidebarHidden) Res.string.desktop_show_sidebar else Res.string.desktop_hide_sidebar),
                shortcut = command(Key.S, ctrl = true),
                enabled = shellUp && sidebarAvailable,
            ) { ShellCommands.send(ShellCommand.ToggleSidebar) }
            Separator()
            Item(stringResource(Res.string.desktop_actual_size), shortcut = command(Key.Zero), onClick = zoom::reset)
            Item(stringResource(Res.string.desktop_zoom_in), shortcut = command(Key.Equals), enabled = zoom.canZoomIn, onClick = zoom::zoomIn)
            Item(stringResource(Res.string.desktop_zoom_out), shortcut = command(Key.Minus), enabled = zoom.canZoomOut, onClick = zoom::zoomOut)
            Separator()
            val fullScreen = windowState.placement == WindowPlacement.Fullscreen
            Item(
                stringResource(if (fullScreen) Res.string.desktop_exit_full_screen else Res.string.desktop_enter_full_screen),
                shortcut = command(Key.F, ctrl = true),
            ) { windowState.placement = if (fullScreen) WindowPlacement.Floating else WindowPlacement.Fullscreen }
        }
        Menu(stringResource(Res.string.desktop_menu_go)) {
            Item(stringResource(Res.string.desktop_back), shortcut = command(Key.LeftBracket), enabled = shellUp, onClick = onBack)
            Separator()
            destination(Res.string.shell_tab_home, Key.One, shellUp) { go(TopLevelRoute.Home) }
            destination(Res.string.shell_tab_moments, Key.Two, shellUp) { go(TopLevelRoute.Moments) }
            destination(Res.string.shell_tab_settings, Key.Three, shellUp) { go(TopLevelRoute.Settings) }
            Separator()
            destination(Res.string.weather_drawer_title, Key.Four, shellUp) { open(InnerApp.WEATHER) }
            destination(Res.string.atlas_drawer_title, Key.Five, shellUp) { open(InnerApp.ATLAS) }
            destination(Res.string.fitness_drawer_title, Key.Six, shellUp) { open(InnerApp.FITNESS) }
            destination(Res.string.shell_drawer_finance, Key.Seven, shellUp) { open(InnerApp.FINANCE) }
            destination(Res.string.packages_drawer_title, Key.Eight, shellUp) { open(InnerApp.PACKAGES) }
            Separator()
            destination(Res.string.shell_sidebar_assistant, Key.K, shellUp) { ShellCommands.send(ShellCommand.Assistant) }
        }
        Menu(stringResource(Res.string.desktop_menu_window)) {
            Item(stringResource(Res.string.desktop_minimize), shortcut = command(Key.M), onClick = onMinimize)
            Item(stringResource(Res.string.desktop_zoom_window)) {
                windowState.placement = if (windowState.placement == WindowPlacement.Maximized) WindowPlacement.Floating else WindowPlacement.Maximized
            }
        }
    }
}

@Composable
private fun MenuScope.destination(title: StringResource, key: Key, enabled: Boolean, onClick: () -> Unit) {
    Item(stringResource(title), shortcut = command(key), enabled = enabled, onClick = onClick)
}

private fun go(tab: TopLevelRoute) = ShellCommands.send(ShellCommand.Go(tab))

private fun open(app: InnerApp) = ShellCommands.send(ShellCommand.Open(app))

/** ⌘ and [key] on a Mac, Ctrl and [key] elsewhere; [ctrl] adds ⌃ on a Mac, as ⌃⌘F is full screen there. */
private fun command(key: Key, ctrl: Boolean = false): KeyShortcut =
    if (isMacOs) KeyShortcut(key, meta = true, ctrl = ctrl) else KeyShortcut(key, ctrl = true, shift = ctrl)
