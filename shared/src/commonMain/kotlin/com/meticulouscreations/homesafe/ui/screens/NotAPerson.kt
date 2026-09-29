package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meticulouscreations.homesafe.domain.model.MomentEvent
import com.meticulouscreations.homesafe.ui.theme.FrigateTheme
import com.meticulouscreations.homesafe.viewmodel.NotAPersonUiState
import kotlinx.coroutines.delay

/*
 * Marking a person detection "Not a person": the detector saw someone in clutter by the door. The
 * relay then knows that spot's phantom again and stops pushing it, the feed hides it, and the
 * detection becomes an example of what isn't a person for the person classifier.
 */

/**
 * The "Not a person" pill on a moment whose person nobody named. Quieter than "Tag car": it
 * corrects the detector rather than asking something of the reader. Spins while [marking].
 *
 * The pill is drawn small, but the touch target around it is the full 48dp; the ripple stays on
 * the pill.
 */
@Composable
internal fun NotAPersonButton(onClick: () -> Unit, modifier: Modifier = Modifier, marking: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = !marking,
                onClickLabel = "Mark as not a person",
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        NotAPersonPill(marking = marking, modifier = Modifier.clip(CircleShape).indication(interaction, ripple()))
    }
}

@Composable
private fun NotAPersonPill(marking: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (marking) {
            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
        } else {
            Icon(
                imageVector = Icons.Filled.PersonOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(12.dp),
            )
        }
        Text(
            text = "Not a person",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * What came of the last "Not a person", under the feed's filters: done, with Undo, or why it
 * wasn't. Lets itself go after [NOT_A_PERSON_BAR_MS]; nothing while [state] has neither.
 */
@Composable
internal fun NotAPersonBar(state: NotAPersonUiState, onUndo: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val marked = state.marked
    val error = state.error
    if (marked == null && error == null) return
    LaunchedEffect(marked, error) {
        delay(NOT_A_PERSON_BAR_MS)
        onDismiss()
    }
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
            .padding(start = 16.dp, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (marked != null) "Marked not a person. Its spot on ${marked.cameraDisplayName} won't alert again." else "Couldn't mark it: $error",
            style = MaterialTheme.typography.bodySmall,
            color = if (marked != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f).padding(vertical = 12.dp),
        )
        if (marked != null) {
            TextButton(onClick = onUndo) { Text("Undo") }
        } else {
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}

/** How long the bar offers Undo before it lets itself go. */
private const val NOT_A_PERSON_BAR_MS = 8_000L

private val previewMarked = MomentEvent(
    id = "1790655519.294717-p1",
    cameraName = "amcrest_1",
    label = "person",
    subLabel = null,
    startEpochSeconds = 1_790_655_519.0,
    endEpochSeconds = 1_790_655_521.0,
    topScore = 0.74,
    hasClip = true,
    hasSnapshot = false,
)

/** The pill idle and on its way to the relay; sized to the pills. */
@Preview(name = "Not a person button")
@Composable
private fun NotAPersonButtonPreview() {
    FrigateTheme {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NotAPersonButton(onClick = {})
            NotAPersonButton(onClick = {}, marking = true)
        }
    }
}

/** The bar once a mark landed, and once one didn't. */
@Preview(name = "Not a person bar", widthDp = 360)
@Composable
private fun NotAPersonBarPreview() {
    FrigateTheme {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NotAPersonBar(state = NotAPersonUiState(marked = previewMarked), onUndo = {}, onDismiss = {})
            NotAPersonBar(state = NotAPersonUiState(error = "Relay answered 502 Bad Gateway"), onUndo = {}, onDismiss = {})
        }
    }
}

