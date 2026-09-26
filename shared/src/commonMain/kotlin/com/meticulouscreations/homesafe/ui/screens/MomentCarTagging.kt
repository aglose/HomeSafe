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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meticulouscreations.homesafe.domain.model.subLabelDisplayName
import com.meticulouscreations.homesafe.viewmodel.MomentCarTagUiState

/*
 * Tagging the car of a moment the classifier left as plain "Car" — in the Moments feed, on a
 * camera's recent activity, and on the camera screen a notification opened. The picker is a
 * dialog rather than a screen of its own because there is nothing to point at: the moment already
 * says which car, so all that's left to ask is whose it is.
 */

/**
 * The "Tag car" pill on a moment whose car nobody has named. Tinted like the primary actions
 * rather than the grey badge it sits beside, since it is the one thing on the card that asks
 * something of the reader.
 */
@Composable
internal fun TagCarButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
            .clickable(onClickLabel = "Tag this car", onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Sell,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = "Tag car",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1,
        )
    }
}

/**
 * On the camera screen a detection opened, when its car is one the classifier didn't know:
 * [summary] says which moment, and the tag is a tap away. Once tagged it says as what
 * ([taggedAs], a category key) instead of offering it again.
 */
@Composable
internal fun TagCarPrompt(summary: String, taggedAs: String?, onTag: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f), shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(32.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.DirectionsCar,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = if (taggedAs == null) "Frigate didn't recognise this car" else "Tagged as ${subLabelDisplayName(taggedAs)}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (taggedAs == null) TagCarButton(onClick = onTag)
    }
}

/**
 * Whose car it is: one of the known cars, or a new one by name. Shown while [state] has a target.
 * Every choice goes straight to the server; once it has landed the dialog only says what came of
 * it, so the reader knows whether the model is retraining, and closes on Done.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TagCarDialog(
    state: MomentCarTagUiState,
    onTag: (String) -> Unit,
    onNewCarDraftChange: (String) -> Unit,
    onTagAsNewCar: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val target = state.target ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (state.done) "Car tagged" else "Whose car is this?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = target.summary, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.notice?.let { notice ->
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state.noticeIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                    )
                }
                when {
                    state.done -> Unit

                    state.loadError != null -> {
                        Text(text = state.loadError, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = onRetry) { Text("Retry") }
                    }

                    state.isLoading -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(text = "Loading the known cars…", style = MaterialTheme.typography.bodyMedium)
                    }

                    else -> {
                        if (state.knownCars.isEmpty()) {
                            Text(
                                text = "The classifier doesn't know any cars yet. Name this one below.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        } else {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                state.knownCars.forEach { car ->
                                    KnownCarChip(label = subLabelDisplayName(car), onClick = { if (!state.isSaving) onTag(car) })
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = state.newCarDraft,
                                onValueChange = onNewCarDraftChange,
                                label = { Text("New car's name") },
                                placeholder = { Text("e.g. Grandma's Van") },
                                singleLine = true,
                                enabled = !state.isSaving,
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { if (state.newCarDraft.isNotBlank()) onTagAsNewCar() }),
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedButton(onClick = onTagAsNewCar, enabled = state.newCarDraft.isNotBlank() && !state.isSaving) { Text("Add") }
                        }
                        if (state.isSaving) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Text(text = "Teaching the classifier…", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (state.done) {
                TextButton(onClick = onDismiss) { Text("Done") }
            } else {
                TextButton(onClick = onDismiss, enabled = !state.isSaving) { Text("Cancel") }
            }
        },
    )
}

/**
 * One known car to tag as: the live tagging screen's pill, a step lighter, because the dialog's
 * own surface is the colour that pill is filled with.
 */
@Composable
private fun KnownCarChip(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), CircleShape)
            .clickable(onClickLabel = "Tag as $label", onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}
