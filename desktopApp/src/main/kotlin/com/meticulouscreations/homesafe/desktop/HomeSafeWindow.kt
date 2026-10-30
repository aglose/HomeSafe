package com.meticulouscreations.homesafe.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.meticulouscreations.homesafe.App
import com.meticulouscreations.homesafe.DesktopStorage
import com.meticulouscreations.homesafe.appicon.DesktopAppIcon
import com.meticulouscreations.homesafe.di.AppGraph
import com.meticulouscreations.homesafe.isMacOs
import com.meticulouscreations.homesafe.ui.LocalShortcutKey
import com.meticulouscreations.homesafe.ui.ShortcutKey
import com.meticulouscreations.homesafe.ui.theme.FrigateTheme
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.desktop_app_name
import kotlinx.coroutines.flow.debounce
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.Taskbar
import kotlin.math.roundToInt

/**
 * The app's window: [App] as the phones have it, in a window that comes back where it was left,
 * at the size it was left, and that the shell lays out for its width (a sidebar once it is wide
 * enough, see `ShellSidebar`).
 *
 * On a Mac the title bar is see-through and the window's content runs up under it, with a strip
 * of the app's own colour where the traffic lights sit ([TITLE_BAR_HEIGHT]): one dark surface from
 * edge to edge, as Apple's own apps have, with nothing of the app's under the lights.
 */
// A window of its own, not an element laid out in a parent, so there is nothing for a Modifier to do.
@Suppress("ktlint:compose:modifier-missing-check")
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ApplicationScope.HomeSafeWindow(graph: AppGraph, state: WindowState, visible: Boolean, onCloseRequest: () -> Unit, onQuit: () -> Unit) {
    val zoom = rememberUiZoom()
    val back = remember { DirectNavigationEventInput() }
    val appIcon by DesktopAppIcon.icon.collectAsState()
    val icon = rememberMacIconPainter(painterResource(appIcon.preview))
    DockIcon(icon)

    Window(
        onCloseRequest = onCloseRequest,
        state = state,
        visible = visible,
        title = stringResource(Res.string.desktop_app_name),
        icon = icon,
    ) {
        // In the window's points, which zoom turns into fewer of the app's dp: scaled with it, so
        // zooming in never narrows the app past the sidebar's width. No larger than the screen
        // the window is on, which a zoomed-in minimum on a small laptop's would otherwise be.
        LaunchedEffect(window, zoom.scale) {
            val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
            window.minimumSize = Dimension(
                (MIN_WIDTH * zoom.scale).roundToInt().coerceAtMost(screen.width),
                (MIN_HEIGHT * zoom.scale + TITLE_BAR_HEIGHT.value).roundToInt().coerceAtMost(screen.height),
            )
        }
        LaunchedEffect(window) {
            if (isMacOs) {
                window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
                window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
                window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
            }
        }
        HomeSafeMenuBar(
            windowState = state,
            zoom = zoom,
            onBack = back::backCompleted,
            onCloseWindow = onCloseRequest,
            onMinimize = { state.isMinimized = true },
            onQuit = onQuit,
        )
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density * zoom.scale, density.fontScale)) {
            Column(Modifier.fillMaxSize()) {
                if (isMacOs && state.placement != WindowPlacement.Fullscreen) {
                    FrigateTheme { Box(Modifier.fillMaxWidth().height(TITLE_BAR_HEIGHT).background(MaterialTheme.colorScheme.background)) }
                }
                Box(
                    Modifier
                        .weight(1f)
                        // The mouse's back button, as a browser takes it.
                        .onPointerEvent(PointerEventType.Press) { event -> if (event.button == PointerButton.Back) back.backCompleted() },
                ) {
                    BackInput(back)
                    // The sidebar and the tooltips say each shortcut the menu bar has, in this platform's keys.
                    CompositionLocalProvider(LocalShortcutKey provides if (isMacOs) ShortcutKey.Command else ShortcutKey.Control) {
                        App(graph)
                    }
                }
            }
        }
    }
}

/**
 * Feeds ⌘[ and the mouse's back button to the same dispatcher Escape and the phones' back gesture
 * reach, so whatever Back would close or pop on screen is what they do.
 */
@Composable
private fun BackInput(input: DirectNavigationEventInput) {
    val owner = LocalNavigationEventDispatcherOwner.current ?: return
    DisposableEffect(owner, input) {
        owner.navigationEventDispatcher.addInput(input)
        onDispose { owner.navigationEventDispatcher.removeInput(input) }
    }
}

/** The window's size, place and full-screen state as last left, kept in the preferences. */
@Composable
fun rememberSavedWindowState(): WindowState {
    val preferences = remember { DesktopStorage.preferences("window") }
    val state = remember {
        val width = preferences.getFloat("width", DEFAULT_WIDTH)
        val height = preferences.getFloat("height", DEFAULT_HEIGHT)
        val x = preferences.getFloat("x", Float.NaN)
        val y = preferences.getFloat("y", Float.NaN)
        WindowState(
            placement = if (preferences.getBoolean("maximized", false)) WindowPlacement.Maximized else WindowPlacement.Floating,
            position = if (x.isNaN() || y.isNaN()) WindowPosition.PlatformDefault else WindowPosition(x.dp, y.dp),
            size = DpSize(width.dp, height.dp),
        )
    }
    LaunchedEffect(state) {
        snapshotFlow { Triple(state.size, state.position, state.placement) }
            .debounce(SAVE_DELAY_MS)
            .collect { (size, position, placement) ->
                if (placement == WindowPlacement.Floating) {
                    preferences.putFloat("width", size.width.value)
                    preferences.putFloat("height", size.height.value)
                    if (position is WindowPosition.Absolute) {
                        preferences.putFloat("x", position.x.value)
                        preferences.putFloat("y", position.y.value)
                    }
                }
                preferences.putBoolean("maximized", placement == WindowPlacement.Maximized)
                preferences.flush()
            }
    }
    return state
}

/** How large the app draws everything: ⌘+ and ⌘− in View, as a browser's zoom; kept between launches. */
class UiZoom(initial: Float, private val save: (Float) -> Unit) {
    var scale by mutableFloatStateOf(initial)
        private set

    fun zoomIn() = set(scale + STEP)

    fun zoomOut() = set(scale - STEP)

    fun reset() = set(1f)

    val canZoomIn: Boolean get() = scale < MAX - 0.001f
    val canZoomOut: Boolean get() = scale > MIN + 0.001f

    private fun set(value: Float) {
        scale = value.coerceIn(MIN, MAX)
        save(scale)
    }

    private companion object {
        const val STEP = 0.1f
        const val MIN = 0.7f
        const val MAX = 1.6f
    }
}

@Composable
private fun rememberUiZoom(): UiZoom = remember {
    val preferences = DesktopStorage.preferences("window")
    UiZoom(preferences.getFloat("zoom", 1f)) { scale ->
        preferences.putFloat("zoom", scale)
        preferences.flush()
    }
}

/** The icon chosen in Settings, in the Dock while the app runs: the packaged icon is only the classic one. */
@Composable
private fun DockIcon(icon: Painter) {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    LaunchedEffect(icon) {
        if (!Taskbar.isTaskbarSupported() || !Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE)) return@LaunchedEffect
        runCatching { Taskbar.getTaskbar().iconImage = icon.toAwtImage(density, direction, Size(DOCK_ICON_PX, DOCK_ICON_PX)) }
    }
}

/** On a Mac the title bar's height, where the traffic lights are; the window's content starts below it. */
private val TITLE_BAR_HEIGHT = 28.dp

private const val DEFAULT_WIDTH = 1280f
private const val DEFAULT_HEIGHT = 840f

/**
 * The smallest the window's page goes, in the app's dp: a little past where the shell lists
 * everything in a sidebar (840dp, `showsSidebar()`), so a computer's window is never laid out as
 * a phone's, with a nav floating over the foot of the page, and still half of a 1920-point screen,
 * for two windows side by side. Tall enough for the sidebar's rows and a page under its title.
 */
private const val MIN_WIDTH = 880f
private const val MIN_HEIGHT = 600f

private const val DOCK_ICON_PX = 512f
private const val SAVE_DELAY_MS = 500L
