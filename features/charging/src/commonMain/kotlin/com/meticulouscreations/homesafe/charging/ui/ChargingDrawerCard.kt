package com.meticulouscreations.homesafe.charging.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.charging.ChargingUiState
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.charging_drawer_charging
import homesafe.shared.generated.resources.charging_drawer_detail
import homesafe.shared.generated.resources.charging_drawer_empty
import homesafe.shared.generated.resources.charging_drawer_open
import homesafe.shared.generated.resources.charging_drawer_subtitle
import homesafe.shared.generated.resources.charging_drawer_this_month
import homesafe.shared.generated.resources.charging_drawer_title
import org.jetbrains.compose.resources.stringResource
import kotlin.math.PI
import kotlin.math.sin

/**
 * The charging app's door: current running along wires while the drawer is open, with what this
 * month has cost so far, or who's charging now. Before the relay has answered it says only what
 * the app is.
 */
@Composable
fun ChargingDrawerCard(charging: ChargingUiState, animated: Boolean, onOpen: (Offset) -> Unit, modifier: Modifier = Modifier) {
    var center by remember { mutableStateOf(Offset.Zero) }
    val shape = RoundedCornerShape(22.dp)
    val secondary = Color(0xD1FFFFFF)
    Box(
        modifier
            .fillMaxWidth()
            .onGloballyPositioned { center = it.boundsInRoot().center }
            .clip(shape)
            .border(1.dp, Color(0x33FFFFFF), shape)
            .clickable(onClickLabel = stringResource(Res.string.charging_drawer_open)) { onOpen(center) }
            .testTag("drawer_charging"),
    ) {
        ChargingWires(running = animated, Modifier.matchParentSize())
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Bolt, contentDescription = null, tint = Color(0xFF0C8A4A), modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(Res.string.charging_drawer_title), style = MaterialTheme.typography.titleMedium, color = Color.White)
                    Text(stringResource(Res.string.charging_drawer_subtitle), maxLines = 1, style = MaterialTheme.typography.labelSmall, color = secondary)
                }
                Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = secondary)
            }
            Spacer(Modifier.height(14.dp))
            val summary = charging.summary
            val month = summary.months.firstOrNull()
            val now = summary.charging.firstOrNull()
            when {
                !charging.loaded -> Unit

                summary.isEmpty -> Text(stringResource(Res.string.charging_drawer_empty), style = MaterialTheme.typography.labelMedium, color = secondary)

                else -> {
                    if (month != null) {
                        Text(stringResource(Res.string.charging_drawer_this_month, moneyText(month.cost)), style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1)
                    }
                    if (now != null) {
                        Text(stringResource(Res.string.charging_drawer_charging, now.name, (now.battery ?: 0).toString()), style = MaterialTheme.typography.labelSmall, color = secondary, maxLines = 1)
                    } else if (month != null) {
                        Text(stringResource(Res.string.charging_drawer_detail, kwhText(month.kwh), month.sessions.toString()), style = MaterialTheme.typography.labelSmall, color = secondary, maxLines = 1)
                    }
                }
            }
        }
    }
}

/**
 * Green current down three wires. It only reads the animation in the draw phase, so the card doesn't
 * recompose as it runs, and stops altogether when not [running].
 */
@Composable
internal fun ChargingWires(running: Boolean, modifier: Modifier = Modifier) {
    val still = remember { mutableFloatStateOf(0.3f) }
    val phase = if (running) {
        rememberInfiniteTransition(label = "chargingWires").animateFloat(0f, 1f, infiniteRepeatable(tween(4_000, easing = LinearEasing), RepeatMode.Restart), label = "current")
    } else {
        still
    }
    Canvas(modifier) {
        drawRect(Brush.linearGradient(listOf(Color(0xFF07211A), Color(0xFF0C3B2B), Color(0xFF06140F)), Offset.Zero, Offset(size.width, size.height)))
        val strokes = listOf(0.28f to 0.5f, 0.55f to 0.8f, 0.82f to 0.35f)
        strokes.forEachIndexed { index, (height, alpha) ->
            val path = Path()
            val steps = 48
            for (i in 0..steps) {
                val x = size.width * i / steps
                val y = size.height * height + sin((i.toFloat() / steps * 2 + index * 0.4f) * PI.toFloat()) * size.height * 0.08f
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, Color(0x2A4CE08A), style = Stroke(1.5.dp.toPx()))
            val at = (phase.value + index * 0.31f) % 1f
            val headX = size.width * at
            val headY = size.height * height + sin((at * 2 + index * 0.4f) * PI.toFloat()) * size.height * 0.08f
            drawCircle(Brush.radialGradient(listOf(Color(0xFF4CE08A).copy(alpha = alpha), Color.Transparent), Offset(headX, headY), 42.dp.toPx()), 42.dp.toPx(), Offset(headX, headY))
            drawCircle(Color(0xFFD9FFE9).copy(alpha = alpha), 2.dp.toPx(), Offset(headX, headY))
        }
    }
}
