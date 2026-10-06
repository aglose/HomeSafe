package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.meticulouscreations.homesafe.domain.model.ClassifierModel
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.asUiText
import com.meticulouscreations.homesafe.text.resolve
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.settings_recognition_classifier_description
import homesafe.shared.generated.resources.settings_recognition_faces
import homesafe.shared.generated.resources.settings_recognition_faces_description
import homesafe.shared.generated.resources.settings_recognition_objects_separator
import homesafe.shared.generated.resources.settings_recognition_title
import homesafe.shared.generated.resources.settings_server_title
import homesafe.shared.generated.resources.uptime_link_description
import homesafe.shared.generated.resources.uptime_title
import org.jetbrains.compose.resources.stringResource

/** The Settings tab's root; the shell watches the back stack's depth to hide its own bar on nested screens. */
data object SettingsHomeRoute

/** This device's alert preferences: the notifications switch, what to hear about, and when. */
data object AlertsRoute

/** Away mode: who's home, the household's phones, and automatic presence. */
data object AwayRoute

/** The labelling screen for one of Frigate's custom classifiers. */
data class ClassifierRoute(val modelName: String)

/** Frigate's face library: who it recognises and the faces waiting for a name. */
data object FacesRoute

/** The server's diagnostics — address, load, detector, disk, retention, AI features — off the main page. */
data object ServerRoute

/** The server's uptime record, one tap below its diagnostics. */
data object UptimeRoute

/**
 * The Settings tab's own nested navigation: the settings page, and the pages its rows open —
 * alerts, away mode, a classifier's labelling screen, the face library, and the server's
 * diagnostics with its uptime record below them. [content] renders the settings page and
 * receives `open`, which pushes the route it is given.
 */
@Composable
fun SettingsTabNav(
    backStack: SnapshotStateList<Any>,
    content: @Composable (open: (Any) -> Unit) -> Unit,
) {
    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        transitionSpec = { sharedAxis(forward = true) },
        popTransitionSpec = { sharedAxis(forward = false) },
        predictivePopTransitionSpec = { sharedAxis(forward = false) },
        entryProvider = entryProvider {
            entry<SettingsHomeRoute> { content { route -> backStack.add(route) } }
            entry<AlertsRoute> { AlertsSettingsScreen(onBack = { backStack.removeLastOrNull() }) }
            entry<AwayRoute> { AwaySettingsScreen(onBack = { backStack.removeLastOrNull() }) }
            entry<ClassifierRoute> { route ->
                ClassifierLabelingScreen(
                    modelName = route.modelName,
                    onBack = { backStack.removeLastOrNull() },
                )
            }
            entry<FacesRoute> { FaceLibraryScreen(onBack = { backStack.removeLastOrNull() }) }
            entry<ServerRoute> { ServerSettingsScreen(onBack = { backStack.removeLastOrNull() }, onOpenUptime = { backStack.add(UptimeRoute) }) }
            entry<UptimeRoute> { ServerUptimeScreen(onBack = { backStack.removeLastOrNull() }) }
        },
    )
}

/**
 * The Settings rows that teach the server who and what it's looking at: the face library when
 * Frigate's face recognition is on ([faceRecognitionEnabled] is null until the config has been
 * read), and one row per custom classifier ("Known Cars"). Renders nothing when there's neither.
 */
@Composable
fun RecognitionSection(
    models: List<ClassifierModel>,
    faceRecognitionEnabled: Boolean?,
    onOpen: (String) -> Unit,
    onOpenFaces: () -> Unit,
) {
    val faces = faceRecognitionEnabled == true
    if (models.isEmpty() && !faces) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(Res.string.settings_recognition_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp),
        )
        SettingsGroup {
            if (faces) {
                SettingsNavRow(
                    icon = Icons.Filled.Face,
                    title = stringResource(Res.string.settings_recognition_faces),
                    summary = stringResource(Res.string.settings_recognition_faces_description),
                    onClick = onOpenFaces,
                )
            }
            models.forEachIndexed { index, model ->
                if (faces || index > 0) SettingsRowDivider()
                SettingsNavRow(
                    icon = Icons.Filled.DirectionsCar,
                    title = model.displayName,
                    summary = stringResource(Res.string.settings_recognition_classifier_description, objectList(model.objects)),
                    onClick = { onOpen(model.name) },
                )
            }
        }
    }
}

/**
 * The way into the server's diagnostics ([ServerSettingsScreen]), last on the page, with a
 * [summary] line — route, disk, health — so the page still says at a glance whether all is well.
 */
@Composable
internal fun ServerSummaryRow(summary: String, onOpen: () -> Unit) {
    SettingsNavRow(icon = Icons.Filled.Dns, title = stringResource(Res.string.settings_server_title), summary = summary, onClick = onOpen)
}

/** The way from the server's diagnostics into its uptime record ([ServerUptimeScreen]), a card of its own between the others. */
@Composable
internal fun UptimeLinkRow(onOpen: () -> Unit) {
    SettingsGroup {
        SettingsNavRow(
            icon = Icons.Filled.MonitorHeart,
            title = stringResource(Res.string.uptime_title),
            summary = stringResource(Res.string.uptime_link_description),
            onClick = onOpen,
        )
    }
}

/** A classifier's Frigate labels, which are data, as one phrase: "car", "car and truck". */
@Composable
private fun objectList(objects: List<String>): String =
    UiText.Joined(objects.map { it.asUiText() }, UiText.of(Res.string.settings_recognition_objects_separator)).resolve()
