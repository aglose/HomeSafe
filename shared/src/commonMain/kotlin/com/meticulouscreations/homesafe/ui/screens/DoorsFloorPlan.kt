package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoorFront
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.meticulouscreations.homesafe.domain.model.Door
import com.meticulouscreations.homesafe.domain.model.PlanBounds
import com.meticulouscreations.homesafe.domain.model.PlanFloor
import com.meticulouscreations.homesafe.domain.model.PlanPoint
import com.meticulouscreations.homesafe.viewmodel.DoorsUiState
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.doors_chip_closed
import homesafe.shared.generated.resources.doors_chip_offline
import homesafe.shared.generated.resources.doors_chip_open
import homesafe.shared.generated.resources.doors_chip_unknown
import homesafe.shared.generated.resources.doors_plan_done
import homesafe.shared.generated.resources.doors_plan_marker
import homesafe.shared.generated.resources.doors_plan_marker_action
import homesafe.shared.generated.resources.doors_plan_place
import homesafe.shared.generated.resources.doors_plan_place_hint
import homesafe.shared.generated.resources.doors_plan_remove
import homesafe.shared.generated.resources.doors_plan_unplaced
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.min
import kotlin.math.roundToInt

/** What the floor plan card asks of the page. */
@Immutable
internal class DoorsPlanActions(
    val onSelectFloor: (floorId: String) -> Unit = {},
    val onStartPlacing: () -> Unit = {},
    val onFinishPlacing: () -> Unit = {},
    val onChoosePlacingDoor: (doorId: String) -> Unit = {},
    val onPlaceAt: (x: Float, y: Float) -> Unit = { _, _ -> },
    val onRemoveFromPlan: (doorId: String) -> Unit = {},
)

/**
 * Feet to pixels for one floor drawn into a [width] × [height] box: the floor scaled to fit inside
 * [padding] on every side, keeping its proportions, and centred.
 */
internal class PlanTransform(private val bounds: PlanBounds, width: Float, height: Float, padding: Float) {
    val scale: Float = min((width - 2 * padding) / bounds.width.coerceAtLeast(1f), (height - 2 * padding) / bounds.height.coerceAtLeast(1f))
    private val originX = (width - bounds.width * scale) / 2 - bounds.left * scale
    private val originY = (height - bounds.height * scale) / 2 - bounds.top * scale

    fun toPx(point: PlanPoint): Offset = Offset(originX + point.x * scale, originY + point.y * scale)

    fun toFeet(px: Offset): PlanPoint = PlanPoint((px.x - originX) / scale, (px.y - originY) / scale)
}

/**
 * The house's floor plan at the top of the Doors page: one floor at a time (the chips switch), to
 * scale, its inside walls where the plan has them, its rooms named where the plan names them, and each door that has been placed as a dot
 * at its spot — orange while open, ringed red while its sensor is offline. A dot opens that
 * door's card below ([onShowDoor]).
 *
 * Placing (from "Place doors"): pick a door from the chips under the plan, then tap where it is;
 * the next door not on the plan yet is picked for the next tap. The plan file itself has no
 * doors, only the outside walls and the rooms' names, so this is how the dots get there.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DoorsFloorPlan(state: DoorsUiState, actions: DoorsPlanActions, onShowDoor: (doorId: String) -> Unit) {
    val plan = state.floorPlan ?: return
    val floor = state.floor ?: return
    SettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FlowRow(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (plan.floors.size > 1) {
                    plan.floors.forEach { each ->
                        FilterChip(selected = each.id == floor.id, onClick = { actions.onSelectFloor(each.id) }, label = { Text(each.name) })
                    }
                }
            }
            if (state.doors.isNotEmpty()) {
                TextButton(onClick = if (state.placing) actions.onFinishPlacing else actions.onStartPlacing, modifier = Modifier.testTag(DOORS_PLACE_TEST_TAG)) {
                    Text(stringResource(if (state.placing) Res.string.doors_plan_done else Res.string.doors_plan_place))
                }
            }
        }
        FloorDrawing(
            floor = floor,
            doors = state.doors.filter { it.spot?.floorId == floor.id },
            placing = state.placing,
            placingDoorId = state.placingDoorId,
            onPlaceAt = actions.onPlaceAt,
            onDoorClick = if (state.placing) actions.onChoosePlacingDoor else onShowDoor,
        )
        if (state.placing) {
            SettingsCaption(stringResource(Res.string.doors_plan_place_hint))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.doors.forEach { door ->
                    FilterChip(
                        selected = door.id == state.placingDoorId,
                        onClick = { actions.onChoosePlacingDoor(door.id) },
                        label = { Text(door.name) },
                        leadingIcon = if (door.spot != null) ({ Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }) else null,
                    )
                }
            }
            state.doors.firstOrNull { it.id == state.placingDoorId && it.spot != null }?.let { door ->
                TextButton(onClick = { actions.onRemoveFromPlan(door.id) }) { Text(stringResource(Res.string.doors_plan_remove, door.name)) }
            }
        } else {
            val unplaced = state.doors.count { it.spot == null }
            if (unplaced > 0) SettingsCaption(pluralStringResource(Res.plurals.doors_plan_unplaced, unplaced, unplaced))
        }
    }
}

/** One floor, drawn to fit the card's width and at most [PLAN_MAX_HEIGHT] tall, with its doors' dots over it. */
@Composable
private fun FloorDrawing(
    floor: PlanFloor,
    doors: List<Door>,
    placing: Boolean,
    placingDoorId: String?,
    onPlaceAt: (x: Float, y: Float) -> Unit,
    onDoorClick: (doorId: String) -> Unit,
) {
    val measurer = rememberTextMeasurer()
    val wall = MaterialTheme.colorScheme.onSurfaceVariant
    val inside = MaterialTheme.colorScheme.surfaceContainerHigh
    val label = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val bounds = floor.bounds
        val height = min(maxWidth * (bounds.height / bounds.width.coerceAtLeast(1f)) + PLAN_PADDING * 2, PLAN_MAX_HEIGHT)
        val density = LocalDensity.current
        val transform = remember(floor, maxWidth, height) {
            with(density) { PlanTransform(bounds, maxWidth.toPx(), height.toPx(), PLAN_PADDING.toPx()) }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .testTag(DOORS_PLAN_TEST_TAG)
                .then(
                    if (placing) {
                        Modifier.pointerInput(transform) {
                            detectTapGestures { tap ->
                                val feet = transform.toFeet(tap)
                                onPlaceAt(feet.x, feet.y)
                            }
                        }
                    } else {
                        Modifier
                    },
                ),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val outline = Path().apply {
                    floor.outline.forEachIndexed { i, point -> transform.toPx(point).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } }
                    close()
                }
                drawPath(outline, inside)
                floor.walls.forEach { inner -> drawLine(wall.copy(alpha = 0.7f), transform.toPx(inner.from), transform.toPx(inner.to), 1.5.dp.toPx()) }
                drawPath(outline, wall, style = Stroke(width = 2.dp.toPx()))
                floor.stairs.forEach { stair -> drawStairs(transform.toPx(stair), transform.scale, wall) }
                floor.rooms.forEach { room ->
                    val text = measurer.measure(room.name, label)
                    val at = transform.toPx(room.at)
                    drawText(text, topLeft = Offset(at.x - text.size.width / 2f, at.y - text.size.height / 2f))
                }
            }
            doors.forEach { door ->
                val spot = door.spot ?: return@forEach
                val at = transform.toPx(PlanPoint(spot.x, spot.y))
                DoorDot(
                    door = door,
                    picked = placing && door.id == placingDoorId,
                    onClick = { onDoorClick(door.id) },
                    // The dot's centre on the spot: its 48dp target is centred on it too.
                    modifier = Modifier.offset { IntOffset((at.x - DOT_TARGET.toPx() / 2).roundToInt(), (at.y - DOT_TARGET.toPx() / 2).roundToInt()) },
                )
            }
        }
    }
}

/** A flight of stairs where the plan has one: a few treads, about three feet across. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStairs(center: Offset, feet: Float, color: Color) {
    val half = 1.5f * feet
    val stroke = 1.dp.toPx()
    for (i in 0..4) {
        val y = center.y - half + i * (2 * half / 4)
        drawLine(color.copy(alpha = 0.6f), Offset(center.x - half, y), Offset(center.x + half, y), stroke)
    }
    drawLine(color.copy(alpha = 0.6f), Offset(center.x - half, center.y - half), Offset(center.x - half, center.y + half), stroke)
    drawLine(color.copy(alpha = 0.6f), Offset(center.x + half, center.y - half), Offset(center.x + half, center.y + half), stroke)
}

/** One door on the plan: a dot with its door, and its name under it. */
@Composable
private fun DoorDot(door: Door, picked: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val open = door.open == true
    val offline = door.online == false
    val state = stringResource(
        when {
            offline -> Res.string.doors_chip_offline
            door.open == null -> Res.string.doors_chip_unknown
            open -> Res.string.doors_chip_open
            else -> Res.string.doors_chip_closed
        },
    )
    val description = stringResource(Res.string.doors_plan_marker, door.name, state)
    val ring = when {
        offline -> MaterialTheme.colorScheme.error
        picked -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier.width(DOT_TARGET)) {
        Box(
            modifier = Modifier
                .size(DOT_TARGET)
                .clip(CircleShape)
                .clickable(onClickLabel = stringResource(Res.string.doors_plan_marker_action), role = Role.Button, onClick = onClick)
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(DOT_SIZE)
                    .clip(CircleShape)
                    .background(if (open) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
                    .border(if (picked || offline) 3.dp else 1.dp, ring, CircleShape),
            )
            Icon(
                imageVector = if (open) Icons.Filled.MeetingRoom else Icons.Filled.DoorFront,
                contentDescription = null,
                tint = if (open) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            text = door.name,
            style = MaterialTheme.typography.labelSmall,
            color = if (open) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false,
            // Wider than the dot, centred under it.
            modifier = Modifier.wrapContentWidth(unbounded = true).offset(y = (-8).dp),
        )
    }
}

private val DOT_SIZE = 28.dp
private val DOT_TARGET = 48.dp
private val PLAN_PADDING = 20.dp
private val PLAN_MAX_HEIGHT = 440.dp

/** The plan's drawing, which a tap places a door on while placing. */
internal const val DOORS_PLAN_TEST_TAG = "doors_plan"

/** "Place doors" / "Done" over the plan. */
internal const val DOORS_PLACE_TEST_TAG = "doors_place"
