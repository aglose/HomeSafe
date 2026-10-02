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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meticulouscreations.homesafe.domain.model.MomentEvent
import com.meticulouscreations.homesafe.domain.model.present
import com.meticulouscreations.homesafe.domain.model.summary
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.resolve
import com.meticulouscreations.homesafe.ui.theme.FrigateTheme
import com.meticulouscreations.homesafe.viewmodel.LandedPersonUiState
import com.meticulouscreations.homesafe.viewmodel.NotAPersonUiState
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.common_ok
import homesafe.shared.generated.resources.common_undo
import homesafe.shared.generated.resources.moments_not_a_person
import homesafe.shared.generated.resources.moments_not_a_person_mark_action
import homesafe.shared.generated.resources.moments_not_a_person_mark_failed
import homesafe.shared.generated.resources.moments_not_a_person_marked
import homesafe.shared.generated.resources.moments_not_a_person_marked_on_camera
import homesafe.shared.generated.resources.moments_not_a_person_question
import homesafe.shared.generated.resources.moments_not_a_person_wont_alert
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.stringResource

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
                onClickLabel = stringResource(Res.string.moments_not_a_person_mark_action),
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
            text = stringResource(Res.string.moments_not_a_person),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * What came of the last "Not a person", under the feed's filters: done, with Undo, or why it
 * wasn't; nothing while [state] has neither. The view model lets it go after a while, as it does
 * a download's result, so no timer runs in the composition.
 */
@Composable
internal fun NotAPersonBar(state: NotAPersonUiState, onUndo: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val marked = state.marked
    val error = state.error
    if (marked == null && error == null) return
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
            text = if (marked != null) stringResource(Res.string.moments_not_a_person_marked_on_camera, marked.cameraDisplayName) else error?.resolve().orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = if (marked != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f).padding(vertical = 12.dp),
        )
        if (marked != null) {
            TextButton(onClick = onUndo) { Text(stringResource(Res.string.common_undo)) }
        } else {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_ok)) }
        }
    }
}

/**
 * On the camera screen a detection opened, when its person is one nobody named: was anyone there?
 * [state] says which moment, and the mark is a tap away; once made it says what it did and offers
 * Undo, and a mark or Undo that didn't land says why. Nothing while [state] has no detection.
 */
@Composable
internal fun NotAPersonPrompt(state: LandedPersonUiState, onMark: () -> Unit, onUndo: () -> Unit, modifier: Modifier = Modifier) {
    if (state.eventId == null) return
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f), shape)
            .padding(start = 16.dp, end = if (state.marked) 4.dp else 12.dp, top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(32.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.PersonOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(if (state.marked) Res.string.moments_not_a_person_marked else Res.string.moments_not_a_person_question),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val error = state.error
            Text(
                text = when {
                    error != null -> error.resolve()
                    state.marked -> stringResource(Res.string.moments_not_a_person_wont_alert, state.cameraDisplayName)
                    else -> state.summary.resolve()
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (state.marked) {
            TextButton(onClick = onUndo, enabled = !state.isSaving) { Text(stringResource(Res.string.common_undo)) }
        } else {
            NotAPersonButton(onClick = onMark, marking = state.isSaving)
        }
    }
}

/** The day the previews are set on: the morning after [previewMarked]. */
private val PREVIEW_DAY = LocalDate(2026, 9, 30)

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

/** The camera screen's prompt: asking, marked with Undo, and a mark that didn't land. */
@Preview(name = "Not a person prompt", widthDp = 360)
@Composable
private fun NotAPersonPromptPreview() {
    val asking = LandedPersonUiState(eventId = previewMarked.id, summary = previewMarked.present(PREVIEW_DAY, TimeZone.UTC).summary, cameraDisplayName = "Front Door")
    FrigateTheme {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NotAPersonPrompt(state = asking, onMark = {}, onUndo = {})
            NotAPersonPrompt(state = asking.copy(marked = true), onMark = {}, onUndo = {})
            NotAPersonPrompt(state = asking.copy(error = UiText.of(Res.string.moments_not_a_person_mark_failed, "Relay answered 502 Bad Gateway")), onMark = {}, onUndo = {})
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
            NotAPersonBar(state = NotAPersonUiState(error = UiText.of(Res.string.moments_not_a_person_mark_failed, "Relay answered 502 Bad Gateway")), onUndo = {}, onDismiss = {})
        }
    }
}
