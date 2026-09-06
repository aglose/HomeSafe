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
import androidx.compose.material.icons.filled.Face
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

/** The Settings tab's root; the shell watches the back stack's depth to hide its own bar on nested screens. */
data object SettingsHomeRoute

/** The labelling screen for one of Frigate's custom classifiers. */
data class ClassifierRoute(val modelName: String)

/** Frigate's face library: who it recognises and the faces waiting for a name. */
data object FacesRoute

/**
 * The Settings tab's own nested navigation: the settings page, and drilling into a classifier's
 * labelling screen or the face library. [content] renders the settings page and receives the
 * callbacks that open them.
 */
@Composable
fun SettingsTabNav(
    backStack: SnapshotStateList<Any>,
    content: @Composable (openClassifier: (String) -> Unit, openFaces: () -> Unit) -> Unit,
) {
    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryProvider = entryProvider {
            entry<SettingsHomeRoute> {
                content(
                    { modelName -> backStack.add(ClassifierRoute(modelName)) },
                    { backStack.add(FacesRoute) },
                )
            }
            entry<ClassifierRoute> { route ->
                ClassifierLabelingScreen(
                    modelName = route.modelName,
                    onBack = { backStack.removeLastOrNull() },
                )
            }
            entry<FacesRoute> { FaceLibraryScreen(onBack = { backStack.removeLastOrNull() }) }
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
        Text(text = "Recognition", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
        if (faceRecognitionEnabled == true) {
            RecognitionRow(
                icon = Icons.Filled.Face,
                title = "Faces",
                description = "Name the faces Frigate saw so it can tell family from strangers",
                onClick = onOpenFaces,
            )
        }
        models.forEach { model ->
            RecognitionRow(
                icon = Icons.Filled.DirectionsCar,
                title = model.displayName,
                description = "Label what the ${model.objects.joinToString(" and ")} classifier saw, and retrain it",
                onClick = { onOpen(model.name) },
            )
        }
    }
}

@Composable
private fun RecognitionRow(icon: ImageVector, title: String, description: String, onClick: () -> Unit) {
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
