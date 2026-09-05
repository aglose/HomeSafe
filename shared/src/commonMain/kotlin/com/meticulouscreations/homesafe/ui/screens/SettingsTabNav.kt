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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.meticulouscreations.homesafe.domain.model.ClassifierModel

/** The Settings tab's root; the shell watches the back stack's depth to hide its own bar on nested screens. */
data object SettingsHomeRoute

/** The labelling screen for one of Frigate's custom classifiers. */
data class ClassifierRoute(val modelName: String)

/**
 * The Settings tab's own nested navigation: the settings page, and drilling into a classifier's
 * labelling screen. [content] renders the settings page and receives the callback that opens a
 * classifier.
 */
@Composable
fun SettingsTabNav(
    backStack: SnapshotStateList<Any>,
    content: @Composable (openClassifier: (String) -> Unit) -> Unit,
) {
    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryProvider = entryProvider {
            entry<SettingsHomeRoute> { content { modelName -> backStack.add(ClassifierRoute(modelName)) } }
            entry<ClassifierRoute> { route ->
                ClassifierLabelingScreen(
                    modelName = route.modelName,
                    onBack = { backStack.removeLastOrNull() },
                )
            }
        },
    )
}

/**
 * A Settings row per custom classifier on the server ("Known Cars"), opening its labelling
 * screen. Renders nothing when the server has no classifiers (or while they're still loading).
 */
@Composable
fun RecognitionSection(models: List<ClassifierModel>, onOpen: (String) -> Unit) {
    if (models.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = "Recognition", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
        models.forEach { model ->
            val shape = RoundedCornerShape(16.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f), shape)
                    .clickable { onOpen(model.name) }
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.DirectionsCar, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(text = model.displayName, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        text = "Label what the ${model.objects.joinToString(" and ")} classifier saw, and retrain it",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
