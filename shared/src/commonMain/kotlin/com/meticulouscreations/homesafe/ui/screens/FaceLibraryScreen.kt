package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.meticulouscreations.homesafe.domain.model.FaceLibrary
import com.meticulouscreations.homesafe.domain.model.KnownPerson
import com.meticulouscreations.homesafe.domain.model.UnlabeledCrop
import com.meticulouscreations.homesafe.domain.model.subLabelDisplayName
import com.meticulouscreations.homesafe.network.FrigateFaceApi
import com.meticulouscreations.homesafe.ui.formatClockTime
import com.meticulouscreations.homesafe.viewmodel.FaceLibraryUiState
import com.meticulouscreations.homesafe.viewmodel.FaceLibraryViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel

/**
 * Teach Frigate who's who. Every face it got a good look at shows up here with its best guess;
 * one tap files it under a person (registering that face on the spot) or throws it away. This is
 * what turns "Person on the front lawn" into "Andrew on the front lawn" — and what lets the
 * "only strangers" alert rule tell the two apart.
 */
@Composable
fun FaceLibraryScreen(viewModel: FaceLibraryViewModel = metroViewModel(), onBack: () -> Unit) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Header(title = "Faces", subtitle = "Teach Frigate who's who", onBack = onBack, onRefresh = viewModel::load)

        when {
            uiState.isLoading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }

            uiState.loadError != null -> ErrorPanel(message = uiState.loadError.orEmpty(), onRetry = viewModel::load)

            else -> uiState.library?.let { library -> Body(uiState = uiState, library = library, viewModel = viewModel) }
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
private fun Body(uiState: FaceLibraryUiState, library: FaceLibrary, viewModel: FaceLibraryViewModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = bottomNavClearance()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "people", contentType = "people") { PeopleCard(uiState = uiState, library = library, viewModel = viewModel) }
        item(key = "queue-title", contentType = "title") {
            Text(
                text = if (library.attempts.isEmpty()) "No faces waiting" else "${library.attempts.size} ${if (library.attempts.size == 1) "face" else "faces"} waiting for a name",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (library.attempts.isEmpty()) {
            item(key = "queue-empty", contentType = "title") {
                Text(
                    text = "Frigate saves every face it gets a good look at, with its best guess. Walk past a camera facing it and check back in a minute.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(library.attempts, key = { it.fileName }, contentType = { "attempt" }) { attempt ->
            AttemptCard(
                attempt = attempt,
                imageUrl = viewModel.imageUrl(FrigateFaceApi.TRAIN_FOLDER, attempt.fileName),
                people = library.people,
                busy = attempt.fileName in uiState.busyFiles,
                decided = uiState.decided[attempt.fileName],
                onLabel = { viewModel.label(attempt.fileName, it) },
                onDiscard = { viewModel.discard(attempt.fileName) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PeopleCard(uiState: FaceLibraryUiState, library: FaceLibrary, viewModel: FaceLibraryViewModel) {
    Card {
        Text(text = "People", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        if (library.people.isEmpty()) {
            Text(
                text = "Nobody yet. Add the people who live here, then file their faces below as Frigate catches them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                library.people.forEach { person ->
                    Chip(
                        label = "${person.displayName} · ${person.imageCount}",
                        selected = uiState.expandedPerson == person.name,
                        onClick = { viewModel.togglePerson(person.name) },
                    )
                }
            }
        }
        val expanded = library.people.firstOrNull { it.name == uiState.expandedPerson }
        if (expanded != null) {
            PersonGallery(
                person = expanded,
                busyFiles = uiState.busyFiles,
                imageUrl = { viewModel.imageUrl(expanded.name, it) },
                onDelete = { viewModel.deleteImage(expanded.name, it) },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = uiState.newPersonDraft,
                onValueChange = viewModel::setNewPersonDraft,
                label = { Text("New person, e.g. Andrew") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = viewModel::createPerson, enabled = uiState.newPersonDraft.isNotBlank()) { Text("Add") }
        }
        Text(
            text = "A face is recognised the moment it's filed — no training step. Five to ten faces per person from different cameras, distances and lighting makes it dependable. Tap a name to see or prune their faces.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        uiState.notice?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = if (uiState.noticeIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

/** A person's registered faces in a strip, each with a delete button. Empty when they were added but never filed. */
@Composable
private fun PersonGallery(person: KnownPerson, busyFiles: Set<String>, imageUrl: (String) -> String?, onDelete: (String) -> Unit) {
    if (person.imageFiles.isEmpty()) {
        Text(
            text = "${person.displayName} has no faces yet. File one from the list below.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(person.imageFiles, key = { it }) { file ->
            Box(modifier = Modifier.size(84.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    imageUrl(file)?.let { url ->
                        AsyncImage(model = url, contentDescription = "${person.displayName}'s face", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    if (file in busyFiles) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp).align(Alignment.Center), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Remove this face",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                        .clickable(enabled = file !in busyFiles) { onDelete(file) }
                        .padding(3.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AttemptCard(
    attempt: UnlabeledCrop,
    imageUrl: String?,
    people: List<KnownPerson>,
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
                val guess = attempt.guessedCategory?.takeIf { it != FaceLibrary.UNKNOWN_GUESS }
                Text(
                    text = when {
                        guess == null -> "Frigate doesn't recognise this face"
                        attempt.guessedScore != null -> "Frigate thinks: ${subLabelDisplayName(guess)} (${(attempt.guessedScore * 100).toInt()}%)"
                        else -> "Frigate thinks: ${subLabelDisplayName(guess)}"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                attempt.capturedEpochSeconds?.let {
                    Text(text = formatClockTime(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (decided != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(16.dp))
                        Text(text = "Filed as ${subLabelDisplayName(decided.orEmpty())}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                    }
                } else {
                    if (people.isEmpty()) {
                        Text(
                            text = "Add a person above to file this face.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            people.forEach { person ->
                                Chip(label = person.displayName, selected = person.name == guess, onClick = { if (!busy) onLabel(person.name) })
                            }
                        }
                    }
                    TextButton(onClick = onDiscard, enabled = !busy) { Text("Not one of us") }
                }
            }
        }
    }
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
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
        Text(text = "Couldn't load the face library: $message", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = onRetry) { Text("Retry") }
    }
}
