package com.meticulouscreations.homesafe.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.MenuScope
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.rememberTrayState
import com.meticulouscreations.homesafe.data.createNotificationMute
import com.meticulouscreations.homesafe.desktop.DesktopNotice
import com.meticulouscreations.homesafe.desktop.DesktopNotifications
import com.meticulouscreations.homesafe.di.AppGraph
import com.meticulouscreations.homesafe.fitness.live.LivePhase
import com.meticulouscreations.homesafe.fitness.live.LiveWorkout
import com.meticulouscreations.homesafe.fitness.live.LiveWorkoutLines
import com.meticulouscreations.homesafe.fitness.live.LiveWorkouts
import com.meticulouscreations.homesafe.navigation.FitnessDeepLink
import com.meticulouscreations.homesafe.navigation.FitnessDeepLinks
import com.meticulouscreations.homesafe.text.resolve
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.desktop_app_name
import homesafe.shared.generated.resources.desktop_quit
import homesafe.shared.generated.resources.desktop_tray_last_alert
import homesafe.shared.generated.resources.desktop_tray_minimize
import homesafe.shared.generated.resources.desktop_tray_mute
import homesafe.shared.generated.resources.desktop_tray_open
import homesafe.shared.generated.resources.fitness_live_open
import homesafe.shared.generated.resources.fitness_rest_minus
import homesafe.shared.generated.resources.fitness_rest_plus
import homesafe.shared.generated.resources.fitness_rest_remaining
import homesafe.shared.generated.resources.fitness_rest_skip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import java.awt.SystemTray

/** Whether there is a menu bar (or a system tray) to put an icon in, and so somewhere to come back from with the window closed. */
fun trayAvailable(): Boolean = runCatching { SystemTray.isSupported() }.getOrDefault(false)

/**
 * HomeSafe's icon in the Mac's menu bar, and what it delivers: the app's notifications
 * ([DesktopNotifications], posted by the alert poller and the weather's check). They go to the
 * Mac's Notification Center as the app's own ([MacNotifications]), where a click opens what that
 * one was about; where those can't be posted (not allowed, or not a packaged Mac app) they are
 * the icon's banners instead, which can't say which was clicked, so a click opens the latest.
 * Its menu opens the window, or minimises it when it is up (so HomeSafe is a click away from the
 * menu bar either way), then offers the latest alert, a switch to mute them all (the same switch
 * as Settings → Notifications), and Quit.
 */
@Composable
fun ApplicationScope.HomeSafeTray(graph: AppGraph, windowUp: Boolean, onOpen: () -> Unit, onMinimize: () -> Unit, onQuit: () -> Unit) {
    if (!trayAvailable()) return
    val state = rememberTrayState()
    var latest by remember { mutableStateOf<DesktopNotice?>(null) }
    // The one switch the whole app reads (the desktop's is a single object, kept in the preferences).
    val mute = remember(graph) { createNotificationMute(graph.platformContext) }
    val muted by mute.muted.collectAsState()

    LaunchedEffect(state) {
        DesktopNotifications.notices.collect { notice ->
            latest = notice
            // Off the UI thread: Notification Center is asked, each time, whether HomeSafe may still notify.
            if (!withContext(Dispatchers.IO) { MacNotifications.post(notice) }) {
                state.sendNotification(Notification(notice.title, notice.body, if (notice.urgent) Notification.Type.Warning else Notification.Type.Info))
            }
        }
    }

    val openLatest: () -> Unit = {
        onOpen()
        latest?.open()
    }
    // Up and in front, or out of the way: the window the other way from how it is.
    val openOrMinimize = if (windowUp) onMinimize else onOpen
    // The workout in progress: its rest drains as a ring in the icon, and the menu leads with it.
    val workout by LiveWorkouts.process.workout.collectAsState()
    val now by produceState(System.currentTimeMillis(), workout?.rest) {
        while (workout?.rest != null) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
        value = System.currentTimeMillis()
    }
    val resting = workout?.phase(now) == LivePhase.RESTING
    val glyph = remember { TrayGlyph() }
    // In steps of a sixtieth, so the icon is drawn again no more than the ring visibly moves.
    val left = if (resting) ((workout?.restLeft(now) ?: 0f) * 60).toInt() else -1
    val ring = remember(left) { if (left >= 0) RestRingGlyph(left / 60f) else null }
    Tray(
        icon = ring ?: glyph,
        state = state,
        tooltip = stringResource(Res.string.desktop_app_name),
        // A click on the icon (where a click on it isn't its menu, away from a Mac) arrives here, and
        // so does one on the icon's own banners, naming none. While those are what shows the
        // notifications, opening the latest one is the better guess; otherwise the window goes the
        // other way from how it is.
        onAction = { if (MacNotifications.posting) openOrMinimize() else openLatest() },
    ) {
        workout?.let { current -> WorkoutItems(current, now, onOpen) }
        Item(stringResource(if (windowUp) Res.string.desktop_tray_minimize else Res.string.desktop_tray_open), onClick = openOrMinimize)
        Item(stringResource(Res.string.desktop_tray_last_alert), enabled = latest?.link != null, onClick = openLatest)
        Separator()
        CheckboxItem(stringResource(Res.string.desktop_tray_mute), checked = muted) { mute.setMuted(it) }
        Separator()
        Item(stringResource(Res.string.desktop_quit), onClick = onQuit)
    }
}

/**
 * The menu's lines for the workout in progress: what it is doing, as a line that can't be
 * chosen, then the way into it and, while a rest counts, the rest's buttons. A menu's words are
 * read as it opens, so the time in it is the time then; the icon keeps it after that.
 */
@Composable
private fun MenuScope.WorkoutItems(workout: LiveWorkout, now: Long, onOpen: () -> Unit) {
    val lines = remember(workout) { LiveWorkoutLines.of(workout) }
    val phase = workout.phase(now)
    val status = when (phase) {
        LivePhase.TRAINING -> lines.title.resolve()
        LivePhase.RESTING -> stringResource(Res.string.fitness_rest_remaining, workout.restClock(now))
        LivePhase.REST_OVER -> lines.restOver.resolve()
    }
    Item(status, enabled = false, onClick = {})
    Item(stringResource(Res.string.fitness_live_open)) {
        onOpen()
        FitnessDeepLinks.open(FitnessDeepLink.Workout)
    }
    if (phase == LivePhase.RESTING) {
        Item(stringResource(Res.string.fitness_rest_skip), onClick = LiveWorkouts.process::skipRest)
        Item(stringResource(Res.string.fitness_rest_plus)) { LiveWorkouts.process.adjustRest(15) }
        Item(stringResource(Res.string.fitness_rest_minus)) { LiveWorkouts.process.adjustRest(-15) }
    }
    Separator()
}

/**
 * The menu-bar icon while a rest counts: a ring that drains clockwise as the rest goes, with a
 * dot at its head. Black on clear, a template like the house ([TrayGlyph]).
 */
private class RestRingGlyph(private val left: Float) : Painter() {
    override val intrinsicSize = Size(GLYPH, GLYPH)

    override fun DrawScope.onDraw() {
        val unit = size.minDimension / GLYPH
        val stroke = 2f * unit
        val inset = stroke / 2 + 1f * unit
        val arc = Size(size.width - inset * 2, size.height - inset * 2)
        drawArc(Color.Black.copy(alpha = 0.3f), 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
        drawArc(Color.Black, -90f, 360f * left, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
        drawCircle(Color.Black, radius = 1.6f * unit, center = Offset(size.width / 2, size.height / 2))
    }

    private companion object {
        const val GLYPH = 18f
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
