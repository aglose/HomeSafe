package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
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

/** The labelling screen for one of Frigate's custom classifiers. */
data class ClassifierRoute(val modelName: String)

/** Frigate's face library: who it recognises and the faces waiting for a name. */
data object FacesRoute

/** The server's diagnostics — address, load, detector, disk, retention, AI features — off the main page. */
data object ServerRoute

/** The server's uptime record, one tap below its diagnostics. */
data object UptimeRoute

/**
 * The Settings tab's own nested navigation: the settings page, and drilling into a classifier's
 * labelling screen, the face library, or the server's diagnostics and its uptime record. [content] renders the
 * settings page and receives the callbacks that open them.
 */
@Composable
fun SettingsTabNav(
    backStack: SnapshotStateList<Any>,
    content: @Composable (openClassifier: (String) -> Unit, openFaces: () -> Unit, openServer: () -> Unit) -> Unit,
) {
    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        transitionSpec = { sharedAxis(forward = true) },
        popTransitionSpec = { sharedAxis(forward = false) },
        predictivePopTransitionSpec = { sharedAxis(forward = false) },
        entryProvider = entryProvider {
            entry<SettingsHomeRoute> {
                content(
                    { modelName -> backStack.add(ClassifierRoute(modelName)) },
                    { backStack.add(FacesRoute) },
                    { backStack.add(ServerRoute) },
                )
            }
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
    if (models.isEmpty() && faceRecognitionEnabled != true) return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = stringResource(Res.string.settings_recognition_title), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
        if (faceRecognitionEnabled == true) {
            SettingsLinkRow(
                icon = Icons.Filled.Face,
                title = stringResource(Res.string.settings_recognition_faces),
                description = stringResource(Res.string.settings_recognition_faces_description),
                onClick = onOpenFaces,
            )
        }
        models.forEach { model ->
            SettingsLinkRow(
                icon = Icons.Filled.DirectionsCar,
                title = model.displayName,
                description = stringResource(Res.string.settings_recognition_classifier_description, objectList(model.objects)),
                onClick = { onOpen(model.name) },
            )
        }
    }
}

/**
 * The way into the server's diagnostics ([ServerSettingsScreen]), last on the page, with a
 * [summary] line — route, disk, health — so the page still says at a glance whether all is well.
 */
@Composable
internal fun ServerSummaryRow(summary: String, onOpen: () -> Unit) {
    SettingsLinkRow(icon = Icons.Filled.Dns, title = stringResource(Res.string.settings_server_title), description = summary, onClick = onOpen)
}

/** The way from the server's diagnostics into its uptime record ([ServerUptimeScreen]). */
@Composable
internal fun UptimeLinkRow(onOpen: () -> Unit) {
    SettingsLinkRow(
        icon = Icons.Filled.MonitorHeart,
        title = stringResource(Res.string.uptime_title),
        description = stringResource(Res.string.uptime_link_description),
        onClick = onOpen,
    )
}

/** A classifier's Frigate labels, which are data, as one phrase: "car", "car and truck". */
@Composable
private fun objectList(objects: List<String>): String =
    UiText.Joined(objects.map { it.asUiText() }, UiText.of(Res.string.settings_recognition_objects_separator)).resolve()

/** One row that opens a page of its own: an icon, what it is, a line about it, and a chevron. */
@Composable
private fun SettingsLinkRow(icon: ImageVector, title: String, description: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f), shape)
            .clickable(onClick = onClick)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(text = description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
