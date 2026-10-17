// Public on purpose: :androidApp's screenshot tests call this from another module (see HomePreviews.kt).
@file:Suppress("ktlint:compose:preview-public-check")

package com.meticulouscreations.homesafe.fitness.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.fitness.domain.WorkoutFocus
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.fitness_run_title
import org.jetbrains.compose.resources.stringResource

/*
 * Today's doors with the body part each one works, as large and as placed as TodayScreen's
 * DoorCard draws them, over a plain gradient of the door's colours in place of the fibre shader.
 */

@Composable
private fun Door(name: String, tints: Pair<Color, Color>, outline: BodyOutline, height: Dp, small: Boolean, modifier: Modifier = Modifier) {
    val type = FitnessTypography(FontFamily.Default)
    Box(
        modifier
            .height(height)
            .clip(FitnessCardShape)
            .background(Brush.linearGradient(listOf(tints.first, tints.second)))
            .border(1.dp, Color(0x33FFFFFF), FitnessCardShape),
    ) {
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(listOf(Color(0x99000000), Color(0x33000000), Color.Transparent))))
        BodyOutlineDrawing(outline, Modifier.align(Alignment.CenterEnd).padding(end = if (small) 10.dp else 50.dp).size(minOf(height - 20.dp, 120.dp)))
        Text(name, style = if (small) type.title else type.hero, color = Color.White, maxLines = 1, modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 18.dp))
    }
}

@Preview(name = "Fitness, door outlines")
@Composable
fun FitnessDoorOutlinesPreview() {
    val colors = FitnessPalette()
    Column(Modifier.background(colors.background).width(380.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (focus in listOf(WorkoutFocus.CHEST, WorkoutFocus.BACK, WorkoutFocus.LEGS)) {
            Door(stringResource(focus.label), colors.focus(focus), BodyOutline.of(focus), 112.dp, small = false, modifier = Modifier.fillMaxWidth())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (focus in listOf(WorkoutFocus.SHOULDERS, WorkoutFocus.ARMS)) {
                Door(stringResource(focus.label), colors.focus(focus), BodyOutline.of(focus), 96.dp, small = true, modifier = Modifier.weight(1f))
            }
        }
        Door(stringResource(Res.string.fitness_run_title), colors.run, BodyOutline.RUNS, 112.dp, small = false, modifier = Modifier.fillMaxWidth())
    }
}
