package com.meticulouscreations.homesafe.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.rememberTrayState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meticulouscreations.homesafe.desktop.DesktopNotice
import com.meticulouscreations.homesafe.desktop.DesktopNotifications
import com.meticulouscreations.homesafe.data.createNotificationMute
import com.meticulouscreations.homesafe.di.AppGraph
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.desktop_app_name
import homesafe.shared.generated.resources.desktop_quit
import homesafe.shared.generated.resources.desktop_tray_last_alert
import homesafe.shared.generated.resources.desktop_tray_mute
import homesafe.shared.generated.resources.desktop_tray_open
import org.jetbrains.compose.resources.stringResource
import java.awt.SystemTray

/** Whether there is a menu bar (or a system tray) to put an icon in, and so somewhere to come back from with the window closed. */
fun trayAvailable(): Boolean = runCatching { SystemTray.isSupported() }.getOrDefault(false)

/**
 * HomeSafe's icon in the Mac's menu bar, and what it delivers: the app's notifications
 * ([DesktopNotifications], posted by the alert poller and the weather's check), as banners from
 * the Mac's notification centre. Clicking the icon opens the window; its menu offers the latest
 * alert, a switch to mute them all (the same switch as Settings → Notifications), and Quit.
 */
@Composable
fun ApplicationScope.HomeSafeTray(graph: AppGraph, onOpen: () -> Unit, onQuit: () -> Unit) {
    if (!trayAvailable()) return
    val state = rememberTrayState()
    var latest by remember { mutableStateOf<DesktopNotice?>(null) }
    // The one switch the whole app reads (the desktop's is a single object, kept in the preferences).
    val mute = remember(graph) { createNotificationMute(graph.platformContext) }
    val muted by mute.muted.collectAsStateWithLifecycle()

    LaunchedEffect(state) {
        DesktopNotifications.notices.collect { notice ->
            latest = notice
            state.sendNotification(Notification(notice.title, notice.body, if (notice.urgent) Notification.Type.Warning else Notification.Type.Info))
        }
    }

    val openLatest: () -> Unit = {
        onOpen()
        latest?.open?.invoke()
    }
    Tray(
        icon = remember { TrayGlyph() },
        state = state,
        tooltip = stringResource(Res.string.desktop_app_name),
        // A click on a notification's banner arrives here too, so it opens what the banner was about.
        onAction = openLatest,
    ) {
        Item(stringResource(Res.string.desktop_tray_open), onClick = onOpen)
        Item(stringResource(Res.string.desktop_tray_last_alert), enabled = latest?.open != null, onClick = openLatest)
        Separator()
        CheckboxItem(stringResource(Res.string.desktop_tray_mute), checked = muted) { mute.setMuted(it) }
        Separator()
        Item(stringResource(Res.string.desktop_quit), onClick = onQuit)
    }
}

/**
 * The app's mark for the menu bar: the house with the eye in it, as lines. Black on clear, which
 * the Mac takes as a template and draws in the menu bar's own colour, light or dark
 * (`apple.awt.enableTemplateImages`, set in Main.kt).
 */
private class TrayGlyph : Painter() {
    override val intrinsicSize = Size(GLYPH, GLYPH)

    override fun DrawScope.onDraw() {
        val unit = size.minDimension / GLYPH
        val stroke = Stroke(width = 1.6f * unit, join = StrokeJoin.Round)
        val house = Path().apply {
            moveTo(3f * unit, 8f * unit)
            lineTo(9f * unit, 2.8f * unit)
            lineTo(15f * unit, 8f * unit)
            lineTo(15f * unit, 15.5f * unit)
            lineTo(3f * unit, 15.5f * unit)
            close()
        }
        drawPath(house, Color.Black, style = stroke)
        drawCircle(Color.Black, radius = 2.6f * unit, center = Offset(9f * unit, 10.6f * unit), style = Stroke(width = 1.4f * unit))
        drawCircle(Color.Black, radius = 1f * unit, center = Offset(9f * unit, 10.6f * unit))
    }

    private companion object {
        const val GLYPH = 18f
    }
}
