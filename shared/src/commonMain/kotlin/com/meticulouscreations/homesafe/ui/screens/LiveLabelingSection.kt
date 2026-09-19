package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.meticulouscreations.homesafe.viewmodel.LiveLabelCard
import com.meticulouscreations.homesafe.viewmodel.LiveLabelingViewModel
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import kotlinx.coroutines.delay

/**
 * "On camera now": the cars [cameraName] is tracking this moment, each with its newest queued crop
 * and the category chips, so a car the long labelling queue buried can be named while it's still in
 * the driveway. Draws nothing unless a classifier runs on something in view and has a crop of it.
 *
 * Its own view model and its own state collection, so the ten-second refresh recomposes this
 * section and not the camera screen around it (see [CameraDetailScreen]'s note on scopes).
 */
@Composable
fun LiveLabelingSection(cameraName: String, modifier: Modifier = Modifier) {
    val viewModel = assistedMetroViewModel<LiveLabelingViewModel, LiveLabelingViewModel.Factory>(key = "live-labeling:$cameraName") {
        create(cameraName)
    }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Polls only while the screen is started: a backgrounded app, or a camera screen covered by
    // another, stops asking the server what's in view.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                viewModel.refresh()
                delay(LIVE_LABELING_POLL_MS)
            }
        }
    }

    val filedAs = uiState.filedAs
    val error = uiState.error
    // The notice outlives the last card, so filing the only car in view still says where it went.
    if (uiState.cards.isEmpty() && filedAs == null && error == null) return

    val uncertain = uiState.uncertainCards
    val confident = uiState.confidentCards
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = "On camera now", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
        Text(
            text = "Name a car while it's in view. The model learns it at the next training.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            filedAs != null -> Text(text = "Filed as ${categoryDisplayName(filedAs)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
            error != null -> Text(text = error, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
        }
        uncertain.forEach { card ->
            key(card.key) { LiveCropCard(card = card, busy = card.key in uiState.busyKeys, onLabel = viewModel::label) }
        }
        if (confident.isNotEmpty()) {
            ConfidentCropsRow(count = confident.size, expanded = uiState.showConfident, busy = false, onToggle = viewModel::toggleConfident, onClear = null)
        }
        if (uiState.showConfident) {
            confident.forEach { card ->
                key(card.key) { LiveCropCard(card = card, busy = card.key in uiState.busyKeys, onLabel = viewModel::label) }
            }
        }
    }
}

@Composable
private fun LiveCropCard(card: LiveLabelCard, busy: Boolean, onLabel: (LiveLabelCard, String) -> Unit) {
    CropCard(
        crop = card.candidate.crop,
        imageUrl = card.imageUrl,
        categories = card.categories,
        busy = busy,
        decided = null,
        onLabel = { onLabel(card, it) },
        onDiscard = null,
        knownAs = card.candidate.tracked.subLabel,
    )
}

/** Frigate saves a crop on each classification attempt, a few a second at most; ten seconds keeps up without hammering it. */
private const val LIVE_LABELING_POLL_MS = 10_000L
