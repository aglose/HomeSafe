package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.meticulouscreations.homesafe.domain.model.SeenBox
import com.meticulouscreations.homesafe.domain.model.boxBetween
import com.meticulouscreations.homesafe.domain.model.cameraDisplayName
import com.meticulouscreations.homesafe.domain.model.subLabelDisplayName
import com.meticulouscreations.homesafe.text.resolve
import com.meticulouscreations.homesafe.viewmodel.CameraFrame
import com.meticulouscreations.homesafe.viewmodel.CarTaggingUiState
import com.meticulouscreations.homesafe.viewmodel.CarTaggingViewModel
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.cars_box_unnamed
import homesafe.shared.generated.resources.cars_camera_frame
import homesafe.shared.generated.resources.cars_how_to_tag
import homesafe.shared.generated.resources.cars_new_frame
import homesafe.shared.generated.resources.cars_selected_named
import homesafe.shared.generated.resources.cars_selected_unnamed
import homesafe.shared.generated.resources.cars_selected_untracked
import homesafe.shared.generated.resources.cars_title
import homesafe.shared.generated.resources.cars_tracking
import homesafe.shared.generated.resources.cars_tracking_none
import homesafe.shared.generated.resources.cars_whose_car
import homesafe.shared.generated.resources.common_back
import homesafe.shared.generated.resources.common_cancel
import homesafe.shared.generated.resources.common_retry
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.random.Random

/**
 * Tagging the cars a camera can see, by hand: a still of [cameraName] as big as the screen allows,
 * the boxes Frigate is tracking drawn over it with what it calls each car, and the known cars to
 * name the chosen one. Tap a box to tag that car, or drag a rectangle round a car Frigate missed
 * or boxed badly. It is where the "In view now" strip gets checked: a car the strip names wrongly,
 * or misses, is put right here, and the classifier learns from the correction.
 */
@Composable
fun CarTaggingScreen(cameraName: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel = assistedMetroViewModel<CarTaggingViewModel, CarTaggingViewModel.Factory>(key = "car-tagging:$cameraName") {
        create(cameraName)
    }
    // Each launch of the screen loads everything fresh; a rotation is the same launch and keeps it.
    val launch = rememberSaveable { Random.nextLong() }
    LaunchedEffect(launch) { viewModel.open(launch) }
    val latest by viewModel.uiState.collectAsStateWithLifecycle()
    // Until the view model has started this launch, what it holds is the last one's: show loading instead.
    val uiState = latest.takeIf { it.launch == launch } ?: CarTaggingUiState()

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag(CAR_TAGGING_TEST_TAG)) {
        Header(cameraName = cameraName, refreshing = uiState.isLoading, onBack = onBack, onRefresh = viewModel::refresh)
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val frame = uiState.frame
            when {
                frame != null -> FrameEditor(
                    frame = frame,
                    uiState = uiState,
                    onTap = viewModel::tapAt,
                    onDraw = viewModel::drawBox,
                )

                uiState.loadError != null -> Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = uiState.loadError?.resolve().orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    OutlinedButton(onClick = viewModel::load) { Text(stringResource(Res.string.common_retry)) }
                }

                else -> CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }
        if (uiState.frame != null) {
            TagPanel(uiState = uiState, onTag = viewModel::tag, onCancel = viewModel::clearSelection)
        }
    }
}

@Composable
private fun Header(cameraName: String, refreshing: Boolean, onBack: () -> Unit, onRefresh: () -> Unit) {
    // This header replaces the shell's top bar (hidden while a nested screen is up), so it
    // steps in from the status bar itself.
    Row(
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.common_back), tint = MaterialTheme.colorScheme.primary)
        }
        Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = stringResource(Res.string.cars_title), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
            Text(
                text = cameraDisplayName(cameraName),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        IconButton(onClick = onRefresh, enabled = !refreshing) {
            Icon(Icons.Filled.Refresh, contentDescription = stringResource(Res.string.cars_new_frame), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * The frame with the tracked cars' boxes over it, and the rectangle being drawn. Sized to the
 * frame's own shape, so a point on it maps to a fraction of the frame by plain division — the
 * coordinates Frigate's boxes and the tag's crop are both in.
 */
@Composable
private fun FrameEditor(frame: CameraFrame, uiState: CarTaggingUiState, onTap: (Double, Double) -> Unit, onDraw: (SeenBox) -> Unit) {
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.merge(TextStyle(color = Color.White))
    val selectedColor = MaterialTheme.colorScheme.primary
    val unnamedLabel = stringResource(Res.string.cars_box_unnamed)
    Box(modifier = Modifier.padding(horizontal = 8.dp).aspectRatio(frame.width.toFloat() / frame.height)) {
        AsyncImage(model = frame.jpeg, contentDescription = stringResource(Res.string.cars_camera_frame), contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(frame) {
                    detectTapGestures { at -> onTap(at.x.toDouble() / size.width, at.y.toDouble() / size.height) }
                }
                .pointerInput(frame) {
                    detectDragGestures(
                        onDragStart = { at ->
                            dragStart = at
                            dragEnd = at
                        },
                        onDrag = { change, _ -> dragEnd = change.position },
                        onDragEnd = {
                            val start = dragStart
                            val end = dragEnd
                            if (start != null && end != null) {
                                val w = size.width.toDouble()
                                val h = size.height.toDouble()
                                onDraw(boxBetween(start.x / w, start.y / h, end.x / w, end.y / h))
                            }
                            dragStart = null
                            dragEnd = null
                        },
                        onDragCancel = {
                            dragStart = null
                            dragEnd = null
                        },
                    )
                },
        ) {
            for (car in uiState.trackedCars) {
                val box = car.box ?: continue
                val selected = car.eventId == uiState.selectedEventId
                val rect = box.toRect(size)
                drawRect(
                    color = if (selected) selectedColor else TrackedBoxColor,
                    topLeft = rect.topLeft,
                    size = rect.size,
                    style = Stroke(width = (if (selected) 3.dp else 2.dp).toPx()),
                )
                val name = car.subLabel?.takeIf { it.isNotBlank() }?.let(::subLabelDisplayName) ?: unnamedLabel
                val text = textMeasurer.measure(name, labelStyle)
                val pad = 4.dp.toPx()
                val labelTop = (rect.top - text.size.height - 2 * pad).takeIf { it >= 0f } ?: rect.top
                drawRect(
                    color = (if (selected) selectedColor else TrackedBoxColor).copy(alpha = 0.85f),
                    topLeft = Offset(rect.left, labelTop),
                    size = Size(text.size.width + 2 * pad, text.size.height + 2 * pad),
                )
                drawText(text, topLeft = Offset(rect.left + pad, labelTop + pad))
            }
            // A drawn selection that isn't just a tracked car's own box.
            val selection = uiState.selection
            if (selection != null && uiState.selectedCar?.box != selection) {
                drawDashedRect(selection.toRect(size), selectedColor)
            }
            val start = dragStart
            val end = dragEnd
            if (start != null && end != null) {
                drawDashedRect(Rect(start, end).normalized(), Color.White)
            }
        }
    }
}

/** What to do with the chosen car: who it is. With nothing chosen, how to choose. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagPanel(uiState: CarTaggingUiState, onTag: (String) -> Unit, onCancel: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        uiState.notice?.let { notice ->
            Text(
                text = notice.resolve(),
                style = MaterialTheme.typography.labelMedium,
                color = if (uiState.noticeIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
            )
        }
        if (uiState.selection == null) {
            val tracked = uiState.trackedCars.size
            Text(
                text = stringResource(Res.string.cars_how_to_tag),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (tracked == 0) stringResource(Res.string.cars_tracking_none) else pluralStringResource(Res.plurals.cars_tracking, tracked, tracked),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        val car = uiState.selectedCar
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = stringResource(Res.string.cars_whose_car), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    text = when {
                        car == null -> stringResource(Res.string.cars_selected_untracked)
                        car.subLabel.isNullOrBlank() -> stringResource(Res.string.cars_selected_unnamed)
                        else -> stringResource(Res.string.cars_selected_named, subLabelDisplayName(car.subLabel))
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (uiState.isSaving) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
            } else {
                TextButton(onClick = onCancel) { Text(stringResource(Res.string.common_cancel)) }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            uiState.categories.forEach { category ->
                Chip(
                    label = categoryDisplayName(category),
                    selected = car?.subLabel == category,
                    onClick = { if (!uiState.isSaving) onTag(category) },
                )
            }
        }
    }
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
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

private fun SeenBox.toRect(size: Size): Rect =
    Rect(
        left = (x * size.width).toFloat(),
        top = (y * size.height).toFloat(),
        right = ((x + width) * size.width).toFloat(),
        bottom = ((y + height) * size.height).toFloat(),
    )

private fun Rect.normalized(): Rect = Rect(minOf(left, right), minOf(top, bottom), maxOf(left, right), maxOf(top, bottom))

private fun DrawScope.drawDashedRect(rect: Rect, color: Color) {
    val dash = 8.dp.toPx()
    drawRect(
        color = color,
        topLeft = rect.topLeft,
        size = rect.size,
        style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash / 2))),
    )
}

/** Amber: stands out against cars and asphalt alike, and isn't the theme's accent, which marks the chosen one. */
private val TrackedBoxColor = Color(0xFFFFB300)

internal const val CAR_TAGGING_TEST_TAG = "car-tagging"
