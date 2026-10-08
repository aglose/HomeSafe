package com.meticulouscreations.homesafe.fitness.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.fitness.ImportState
import com.meticulouscreations.homesafe.fitness.domain.BodyPart
import com.meticulouscreations.homesafe.fitness.domain.ImportedExercise
import com.meticulouscreations.homesafe.text.resolve
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.fitness_import_auto
import homesafe.shared.generated.resources.fitness_import_body
import homesafe.shared.generated.resources.fitness_import_confirm
import homesafe.shared.generated.resources.fitness_import_done
import homesafe.shared.generated.resources.fitness_import_done_button
import homesafe.shared.generated.resources.fitness_import_done_more
import homesafe.shared.generated.resources.fitness_import_hint
import homesafe.shared.generated.resources.fitness_import_nothing_new
import homesafe.shared.generated.resources.fitness_import_preview
import homesafe.shared.generated.resources.fitness_import_row_detail
import homesafe.shared.generated.resources.fitness_import_row_known
import homesafe.shared.generated.resources.fitness_import_row_new
import homesafe.shared.generated.resources.fitness_import_row_sets
import homesafe.shared.generated.resources.fitness_import_shelf
import homesafe.shared.generated.resources.fitness_import_skipped
import homesafe.shared.generated.resources.fitness_import_skipped_more
import homesafe.shared.generated.resources.fitness_import_summary
import homesafe.shared.generated.resources.fitness_import_unread
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Bringing the notes in. Paste a note (or several, one after another) and the page shows, as it
 * is typed, what it reads there: each exercise, its shelf and how many sets it found, and any
 * line it could make nothing of. Nothing is saved until Import is pressed, and pressing it twice
 * adds nothing twice.
 */
@Composable
internal fun ImportScreen(
    import: ImportState,
    padding: PaddingValues,
    onText: (String) -> Unit,
    onPart: (BodyPart?) -> Unit,
    onConfirm: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FitnessTheme.colors
    val type = FitnessTheme.type
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = FitnessGutter).testTag("fitness_import"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val imported = import.importedExercises
        if (imported != null) {
            FitnessCard(Modifier.testTag("fitness_import_done")) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = colors.good, modifier = Modifier.size(36.dp))
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(
                        Res.string.fitness_import_done,
                        pluralStringResource(Res.plurals.fitness_import_row_new, imported, imported),
                        pluralStringResource(Res.plurals.fitness_import_row_sets, import.importedSets, import.importedSets),
                    ),
                    style = type.title,
                    color = colors.text,
                )
                Text(stringResource(Res.string.fitness_import_done_more), style = type.body, color = colors.textMuted, modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(14.dp))
                ForgeButton(stringResource(Res.string.fitness_import_done_button), onDone, Modifier.fillMaxWidth().testTag("fitness_import_done_button"), icon = Icons.Filled.Check)
            }
        }
        Text(stringResource(Res.string.fitness_import_body), style = type.body, color = colors.textMuted)
        FitnessTextField(
            import.text,
            onText,
            stringResource(Res.string.fitness_import_hint),
            Modifier.fillMaxWidth().heightIn(min = 170.dp, max = 320.dp).testTag("fitness_import_text"),
            singleLine = false,
            minLines = 7,
        )
        CardLabel(stringResource(Res.string.fitness_import_shelf))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceChip(stringResource(Res.string.fitness_import_auto), import.part == null, { onPart(null) })
            BodyPart.entries.forEach { part -> ChoiceChip(stringResource(part.label), import.part == part, { onPart(part) }) }
        }

        val plan = import.plan ?: return@Column
        FitnessCard(Modifier.testTag("fitness_import_preview"), title = stringResource(Res.string.fitness_import_preview)) {
            Text(
                if (plan.isEmpty) {
                    stringResource(if (plan.exercises.isEmpty()) Res.string.fitness_import_unread else Res.string.fitness_import_nothing_new)
                } else {
                    stringResource(
                        Res.string.fitness_import_summary,
                        pluralStringResource(Res.plurals.fitness_import_row_new, plan.newExercises, plan.newExercises),
                        pluralStringResource(Res.plurals.fitness_import_row_sets, plan.newSets, plan.newSets),
                    )
                },
                style = type.headline,
                color = colors.text,
            )
            if (!plan.isEmpty) {
                Spacer(Modifier.height(12.dp))
                ForgeButton(stringResource(Res.string.fitness_import_confirm), onConfirm, Modifier.fillMaxWidth().testTag("fitness_import_confirm"), icon = Icons.Filled.Check)
            }
            Spacer(Modifier.height(6.dp))
            plan.exercises.forEach { PlannedRow(it) }
            if (plan.skipped.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                CardLabel(stringResource(Res.string.fitness_import_skipped), color = colors.amber)
                plan.skipped.take(SKIPPED_SHOWN).forEach { line ->
                    Text(line, style = type.label, color = colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
                val more = plan.skipped.size - SKIPPED_SHOWN
                if (more > 0) {
                    Text(
                        pluralStringResource(Res.plurals.fitness_import_skipped_more, more, more),
                        style = type.label,
                        color = colors.amber,
                        modifier = Modifier.padding(top = 6.dp).testTag("fitness_import_skipped_more"),
                    )
                }
            }
        }
    }
}

/** How many unread lines the preview lists before it gives the rest as a count. */
private const val SKIPPED_SHOWN = 12

/** An exercise the notes hold: its name and shelf, the heaviest rung read for it, and how much of it is new. */
@Composable
private fun PlannedRow(item: ImportedExercise) {
    val colors = FitnessTheme.colors
    val type = FitnessTheme.type
    val exercise = item.exercise
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(exercise.name, style = type.bodyStrong, color = if (item.isNew || item.sets.isNotEmpty()) colors.text else colors.textFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val top = item.sets.maxWithOrNull(compareBy({ it.weight }, { it.reps }))
            Text(
                top?.let { stringResource(Res.string.fitness_import_row_detail, stringResource(exercise.bodyPart.label), FitnessFormat.set(exercise.loadKind, it.weight, it.reps).resolve()) }
                    ?: stringResource(exercise.bodyPart.label),
                style = type.label,
                color = colors.textFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            if (item.sets.isEmpty() && !item.isNew) {
                stringResource(Res.string.fitness_import_row_known)
            } else {
                pluralStringResource(Res.plurals.fitness_import_row_sets, item.sets.size, item.sets.size)
            },
            style = type.label,
            color = if (item.sets.isEmpty() && !item.isNew) colors.textFaint else colors.good,
            maxLines = 1,
        )
    }
}
