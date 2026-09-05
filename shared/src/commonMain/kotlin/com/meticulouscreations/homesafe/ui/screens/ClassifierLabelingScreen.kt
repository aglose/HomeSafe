package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.meticulouscreations.homesafe.domain.model.ClassifierDataset
import com.meticulouscreations.homesafe.domain.model.UnlabeledCrop
import com.meticulouscreations.homesafe.domain.model.subLabelDisplayName
import com.meticulouscreations.homesafe.ui.formatClockTime
import com.meticulouscreations.homesafe.viewmodel.ClassifierLabelingUiState
import com.meticulouscreations.homesafe.viewmodel.ClassifierLabelingViewModel
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel

/**
 * Teach a Frigate classifier by labelling what it saw. Each queued crop shows the model's own
 * guess; one tap files it under a category (or throws it away), and Train retrains on the box
 * once there's something new. This is the loop that turns "car" into "Sarah's Tesla".
 */
@Composable
fun ClassifierLabelingScreen(
    modelName: String,
    onBack: () -> Unit,
) {
    val viewModel = assistedMetroViewModel<ClassifierLabelingViewModel, ClassifierLabelingViewModel.Factory>(key = "classifier:$modelName") {
        create(modelName)
    }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Header(title = subLabelDisplayName(modelName), subtitle = "Teach Frigate what it's looking at", onBack = onBack, onRefresh = viewModel::load)

        when {
            uiState.isLoading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
            uiState.loadError != null -> ErrorPanel(message = uiState.loadError.orEmpty(), onRetry = viewModel::load)
            else -> uiState.dataset?.let { data -> Body(uiState = uiState, data = data, viewModel = viewModel) }
        }
    }
}

@Composable
private fun Header(title: String, subtitle: String, onBack: () -> Unit, onRefresh: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.primary)
        }
        Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, textAlign = TextAlign.Center)
            Text(text = subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        IconButton(onClick = onRefresh) {
            Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun Body(uiState: ClassifierLabelingUiState, data: ClassifierDataset, viewModel: ClassifierLabelingViewModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 24.dp, end = 24.dp, bottom = bottomNavClearance()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { CategoriesCard(uiState = uiState, data = data, viewModel = viewModel) }
        item { TrainCard(uiState = uiState, data = data, onTrain = viewModel::train) }
        item {
            Text(
                text = if (data.queue.isEmpty()) "Nothing waiting to be labelled" else "${data.queue.size} waiting to be labelled",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (data.queue.isEmpty()) {
            item {
                Text(
                    text = "Frigate saves a crop every time this model looks at a ${data.model.objects.joinToString(" or ")}. They'll show up here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(data.queue, key = { it.fileName }) { crop ->
            CropCard(
                crop = crop,
                imageUrl = viewModel.imageUrl(crop.fileName),
                categories = data.categories,
                busy = crop.fileName in uiState.busyFiles,
                decided = uiState.decided[crop.fileName],
                onLabel = { viewModel.label(crop.fileName, it) },
                onDiscard = { viewModel.discard(crop.fileName) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoriesCard(uiState: ClassifierLabelingUiState, data: ClassifierDataset, viewModel: ClassifierLabelingViewModel) {
    Card {
        Text(text = "Categories", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            data.categories.forEach { category ->
                Chip(label = "${categoryDisplayName(category)} · ${data.categoryCounts[category] ?: 0}", selected = false, onClick = {})
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = uiState.newCategoryDraft,
                onValueChange = viewModel::setNewCategoryDraft,
                label = { Text("New category, e.g. Ron and Judy's Mercedes") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = viewModel::createCategory, enabled = uiState.newCategoryDraft.isNotBlank()) { Text("Add") }
        }
        Text(
            text = "\"Not ours\" is for any ${data.model.objects.joinToString(" or ")} you don't care about. It teaches the model what to ignore and never becomes a name.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TrainCard(uiState: ClassifierLabelingUiState, data: ClassifierDataset, onTrain: () -> Unit) {
    Card {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = when {
                        !data.hasTrained -> "Never trained"
                        data.newImagesSinceTraining > 0 -> "${data.newImagesSinceTraining} new since last training"
                        else -> "Up to date"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (data.canTrain) "Takes about half a minute on the server. Frigate switches to the new model when it's done." else "Needs two categories with images before it can train.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onTrain, enabled = data.canTrain && !uiState.isTraining) {
                if (uiState.isTraining) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text("Train")
                }
            }
        }
        uiState.notice?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = if (uiState.noticeIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CropCard(
    crop: UnlabeledCrop,
    imageUrl: String?,
    categories: List<String>,
    busy: Boolean,
    decided: String?,
    onLabel: (String) -> Unit,
    onDiscard: () -> Unit,
) {
    Card {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .width(132.dp)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                if (imageUrl != null) {
                    AsyncImage(model = imageUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                if (busy) CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val guess = crop.guessedCategory
                Text(
                    text = when {
                        guess == null || guess == "unknown" -> "Model hasn't guessed"
                        crop.guessedScore != null -> "Model thinks: ${categoryDisplayName(guess)} (${(crop.guessedScore * 100).toInt()}%)"
                        else -> "Model thinks: ${categoryDisplayName(guess)}"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                crop.capturedEpochSeconds?.let {
                    Text(text = formatClockTime(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (decided != null || (decided == null && crop.fileName.isEmpty())) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(16.dp))
                        Text(text = "Filed as ${categoryDisplayName(decided.orEmpty())}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                    }
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        categories.forEach { category ->
                            Chip(label = categoryDisplayName(category), selected = category == guess, onClick = { if (!busy) onLabel(category) })
                        }
                    }
                    TextButton(onClick = onDiscard, enabled = !busy) { Text("Discard this crop") }
                }
            }
        }
    }
}

@Composable
private fun Card(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f), shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val background = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val foreground = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = foreground,
        maxLines = 1,
        modifier = Modifier
            .clip(CircleShape)
            .background(background, CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (selected) 0f else 0.2f), CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun ErrorPanel(message: String, onRetry: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = "Couldn't load the classifier: $message", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = onRetry) { Text("Retry") }
    }
}

/** `none` reads as "Not ours"; everything else gets the same humanising as sub-labels in the feed. */
private fun categoryDisplayName(category: String): String =
    if (category == ClassifierDataset.NONE_CATEGORY) "Not ours" else subLabelDisplayName(category)

@Suppress("unused")
private val unusedColor: Color = Color.Unspecified
