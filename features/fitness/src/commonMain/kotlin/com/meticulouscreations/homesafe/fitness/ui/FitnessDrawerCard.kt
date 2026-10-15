package com.meticulouscreations.homesafe.fitness.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.fitness.FitnessUiState
import com.meticulouscreations.homesafe.fitness.ui.FitnessPalette
import com.meticulouscreations.homesafe.fitness.ui.shader.FiberField
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.fitness_drawer_empty
import homesafe.shared.generated.resources.fitness_drawer_open
import homesafe.shared.generated.resources.fitness_drawer_subtitle
import homesafe.shared.generated.resources.fitness_drawer_title
import homesafe.shared.generated.resources.fitness_drawer_week
import homesafe.shared.generated.resources.fitness_today_headline_due
import homesafe.shared.generated.resources.fitness_today_headline_working
import homesafe.shared.generated.resources.fitness_today_resume_detail
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The fitness app's door: muscle fibre in the colours of the workout that is up next (or the one
 * in progress), moving while the drawer is open, with which day that is and how the week has gone.
 */
@Composable
fun FitnessDrawerCard(fitness: FitnessUiState, animated: Boolean, onOpen: (Offset) -> Unit, modifier: Modifier = Modifier) {
    var center by remember { mutableStateOf(Offset.Zero) }
    val shape = RoundedCornerShape(22.dp)
    val palette = remember { FitnessPalette() }
    val workout = fitness.workout
    val due = fitness.days.firstOrNull { it.due }
    val tints = (workout?.workout?.focus ?: due?.focus)?.let(palette::focus) ?: palette.phase(fitness.phase.kind)
    val secondary = Color(0xD1FFFFFF)
    Box(
        modifier
            .fillMaxWidth()
            .onGloballyPositioned { center = it.boundsInRoot().center }
            .clip(shape)
            .border(1.dp, Color(0x33FFFFFF), shape)
            .clickable(onClickLabel = stringResource(Res.string.fitness_drawer_open)) { onOpen(center) }
            .testTag("drawer_fitness"),
    ) {
        // Small enough to draw live, and stopped when the drawer is shut.
        FiberField(tints.first, tints.second, seed = 0.4f, Modifier.matchParentSize(), energy = if (workout != null) 1f else 0.4f, running = animated)
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color(0x4D000000), Color(0x99000000)))))
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.FitnessCenter, contentDescription = null, tint = Color(0xFFD9441A), modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(Res.string.fitness_drawer_title), style = MaterialTheme.typography.titleMedium, color = Color.White)
                    Text(stringResource(Res.string.fitness_drawer_subtitle), maxLines = 1, style = MaterialTheme.typography.labelSmall, color = secondary)
                }
                Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = secondary)
            }
            Spacer(Modifier.height(14.dp))
            when {
                workout != null -> {
                    Text(
                        stringResource(Res.string.fitness_today_headline_working, stringResource(workout.workout.focus.label)),
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        maxLines = 1,
                    )
                    Text(pluralStringResource(Res.plurals.fitness_today_resume_detail, workout.setCount, workout.setCount), style = MaterialTheme.typography.labelSmall, color = secondary)
                }

                due != null -> {
                    Text(stringResource(Res.string.fitness_today_headline_due, stringResource(due.focus.label)), style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1)
                    Text(
                        pluralStringResource(Res.plurals.fitness_drawer_week, fitness.week.sessions, fitness.week.sessions),
                        style = MaterialTheme.typography.labelSmall,
                        color = secondary,
                        maxLines = 1,
                    )
                }

                else -> Text(stringResource(Res.string.fitness_drawer_empty), style = MaterialTheme.typography.labelMedium, color = secondary)
            }
        }
    }
}
