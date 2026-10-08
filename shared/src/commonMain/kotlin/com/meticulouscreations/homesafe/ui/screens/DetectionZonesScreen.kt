package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.meticulouscreations.homesafe.domain.model.MaskLayer
import com.meticulouscreations.homesafe.domain.model.cameraDisplayName
import com.meticulouscreations.homesafe.text.resolve
import com.meticulouscreations.homesafe.ui.VIDEO_ASPECT
import com.meticulouscreations.homesafe.ui.components.EditorPolygon
import com.meticulouscreations.homesafe.ui.components.MaskPolygonEditor
import com.meticulouscreations.homesafe.ui.isCompactLandscape
import com.meticulouscreations.homesafe.ui.rememberPredictiveBack
import com.meticulouscreations.homesafe.viewmodel.DetectionZonesUiState
import com.meticulouscreations.homesafe.viewmodel.DetectionZonesViewModel
import com.meticulouscreations.homesafe.viewmodel.EditorShape
import com.meticulouscreations.homesafe.viewmodel.MaskEditorState
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.common_back
import homesafe.shared.generated.resources.common_cancel
import homesafe.shared.generated.resources.common_delete
import homesafe.shared.generated.resources.common_done
import homesafe.shared.generated.resources.common_list_separator
import homesafe.shared.generated.resources.common_retry
import homesafe.shared.generated.resources.common_save
import homesafe.shared.generated.resources.common_undo
import homesafe.shared.generated.resources.zones_add_ignore_area
import homesafe.shared.generated.resources.zones_add_zone
import homesafe.shared.generated.resources.zones_any_object
import homesafe.shared.generated.resources.zones_counts_objects
import homesafe.shared.generated.resources.zones_discard
import homesafe.shared.generated.resources.zones_frigate_key
import homesafe.shared.generated.resources.zones_hint_area_selected
import homesafe.shared.generated.resources.zones_hint_corner_minimum
import homesafe.shared.generated.resources.zones_hint_corner_selected
import homesafe.shared.generated.resources.zones_hint_keep_adding
import homesafe.shared.generated.resources.zones_hint_no_zones
import homesafe.shared.generated.resources.zones_hint_nothing_ignored
import homesafe.shared.generated.resources.zones_hint_place_corners
import homesafe.shared.generated.resources.zones_hint_select_ignore_area
import homesafe.shared.generated.resources.zones_hint_select_zone
import homesafe.shared.generated.resources.zones_hint_zone_selected
import homesafe.shared.generated.resources.zones_keep_editing
import homesafe.shared.generated.resources.zones_layer_count
import homesafe.shared.generated.resources.zones_leave_body_ignore_areas
import homesafe.shared.generated.resources.zones_leave_body_zones
import homesafe.shared.generated.resources.zones_leave_title
import homesafe.shared.generated.resources.zones_name_label
import homesafe.shared.generated.resources.zones_new_badge
import homesafe.shared.generated.resources.zones_placeholder_name
import homesafe.shared.generated.resources.zones_refresh_frame
import homesafe.shared.generated.resources.zones_remove_corner
import homesafe.shared.generated.resources.zones_save_and_leave
import homesafe.shared.generated.resources.zones_saved_ignore_areas
import homesafe.shared.generated.resources.zones_saved_zones
import homesafe.shared.generated.resources.zones_saving
import homesafe.shared.generated.resources.zones_title
import homesafe.shared.generated.resources.zones_unsaved_one
import homesafe.shared.generated.resources.zones_unsaved_three
import homesafe.shared.generated.resources.zones_unsaved_two
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Google Home-style "zones" editor for one camera: a still frame with polygon layers drawn over
 * it — named zones, plus two kinds of ignore area (objects, motion). Tap to place corners, tap
 * the first corner (or Done) to close the shape, drag corners to adjust. Nothing reaches the
 * server until Save: a save bar appears the moment there's something to save, and leaving with
 * unsaved work asks first.
 *
 * Upright, the frame is the full width of the page and its tools scroll beneath it. On a phone on
 * its side that frame would be taller than the window, with its tools a scroll away from the
 * shape they act on — so there the frame stands at the left, as large as the height allows, and
 * the tools have a column of their own beside it.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DetectionZonesScreen(
    cameraName: String,
    onBack: () -> Unit,
) {
    val viewModel = assistedMetroViewModel<DetectionZonesViewModel, DetectionZonesViewModel.Factory>(key = "zones:$cameraName") {
        create(cameraName)
    }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val dirty = uiState.editor.isDirty
    var showLeaveDialog by remember { mutableStateOf(false) }
    var leaveAfterSave by remember { mutableStateOf(false) }

    // Both the header arrow and the system back go through the same guard. Clean, Back is the
    // back stack's, whose predictive pop previews the camera page; dirty, it is caught here and
    // only asks, so nothing moves under the finger (Material: no peek at a page Back won't reach).
    val requestBack = { if (dirty && !uiState.isSaving) showLeaveDialog = true else onBack() }
    rememberPredictiveBack(enabled = dirty) { requestBack() }
    LaunchedEffect(Unit) { viewModel.reloadIfClean() }
    LaunchedEffect(uiState.justSaved, uiState.saveError) {
        if (leaveAfterSave && uiState.justSaved) onBack()
        if (uiState.saveError != null) leaveAfterSave = false
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Header(cameraName = cameraName, uiState = uiState, onBack = requestBack, onSave = viewModel::save)

        // The frame (or what stands in for it while it loads, or fails to), and the tools that
        // act on it: the same two pieces either way round, only arranged differently.
        val frame: @Composable () -> Unit = {
            when {
                uiState.isLoading -> Box(modifier = Modifier.aspectRatio(VIDEO_ASPECT), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }

                uiState.loadError != null -> ErrorPanel(message = uiState.loadError?.resolve().orEmpty(), onRetry = viewModel::load)

                else -> EditorCanvas(uiState = uiState, viewModel = viewModel)
            }
        }
        val tools: @Composable () -> Unit = {
            if (!uiState.isLoading && uiState.loadError == null) {
                Toolbar(uiState = uiState, viewModel = viewModel)
                StatusLine(uiState = uiState)
                if (dirty || uiState.isSaving || uiState.saveError != null) {
                    SaveBar(uiState = uiState, onSave = viewModel::save, onDiscard = viewModel::discardChanges)
                }
                if (uiState.editor.layer == MaskLayer.ZONES) {
                    ZoneList(uiState = uiState, viewModel = viewModel)
                }
            }
        }

        if (isCompactLandscape()) {
            Row(
                modifier = Modifier.weight(1f).padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Box(modifier = Modifier.weight(EDITOR_FRAME_WEIGHT).fillMaxHeight().padding(bottom = 16.dp), contentAlignment = Alignment.TopCenter) { frame() }
                Column(
                    modifier = Modifier
                        // Its share of the width, up to what it has any use for.
                        .weight(1f, fill = false)
                        .widthIn(max = EDITOR_TOOLS_MAX_WIDTH)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = bottomNavClearance()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    LayerPicker(editor = uiState.editor, onSelect = viewModel::switchLayer)
                    tools()
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = contentGutter(EDITOR_FRAME_MAX_WIDTH))
                    .padding(bottom = bottomNavClearance()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                LayerPicker(editor = uiState.editor, onSelect = viewModel::switchLayer)
                frame()
                tools()
            }
        }
    }

    if (showLeaveDialog) {
        AlertDialog(
            onDismissRequest = { showLeaveDialog = false },
            title = { Text(stringResource(Res.string.zones_leave_title)) },
            text = { Text(uiState.editor.layer.sentence(zones = Res.string.zones_leave_body_zones, ignoreAreas = Res.string.zones_leave_body_ignore_areas)) },
            confirmButton = {
                Button(onClick = {
                    showLeaveDialog = false
                    leaveAfterSave = true
                    viewModel.save()
                }) { Text(stringResource(Res.string.zones_save_and_leave)) }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { showLeaveDialog = false }) { Text(stringResource(Res.string.zones_keep_editing)) }
                    TextButton(onClick = {
                        showLeaveDialog = false
                        viewModel.discardChanges()
                        onBack()
                    }) { Text(stringResource(Res.string.zones_discard)) }
                }
            },
        )
    }
}

/** The widest the tools' column gets beside the frame on a phone on its side: the three-button toolbar on one line. */
private val EDITOR_TOOLS_MAX_WIDTH = 360.dp

/** The frame's share of the width beside the tools (theirs is 1): a little the larger half. */
private const val EDITOR_FRAME_WEIGHT = 1.1f

/** The widest the frame is drawn in a scrolling page; past it, a tablet would show nothing but the frame. */
private val EDITOR_FRAME_MAX_WIDTH = 840.dp

@Composable
private fun Header(cameraName: String, uiState: DetectionZonesUiState, onBack: () -> Unit, onSave: () -> Unit) {
    // This header replaces the shell's top bar (hidden while a nested screen is up), so it
    // steps in from the status bar itself.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 24.dp, vertical = nestedHeaderVerticalPadding()),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.common_back), tint = MaterialTheme.colorScheme.primary)
        }
        Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(Res.string.zones_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
            Text(
                text = cameraDisplayName(cameraName),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        TextButton(onClick = onSave, enabled = uiState.editor.isDirty && !uiState.isSaving) {
            if (uiState.isSaving) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
            } else {
                Text(stringResource(Res.string.common_save))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LayerPicker(editor: MaskEditorState, onSelect: (MaskLayer) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // FlowRow, so on a narrow phone the third chip drops to a second line whole instead of wrapping its text.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MaskLayer.entries.forEach { layer ->
                val count = editor.shapes.getValue(layer).size
                val name = stringResource(layer.label)
                val label = if (count > 0) stringResource(Res.string.zones_layer_count, name, count) else name
                Chip(label = label, accent = layerColor(layer), selected = layer == editor.layer, onClick = { onSelect(layer) })
            }
        }
        Text(
            text = stringResource(editor.layer.description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Chip(label: String, accent: Color?, selected: Boolean, onClick: () -> Unit) {
    val background = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    val foreground = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(background, CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (selected) 0f else 0.2f), CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (accent != null) Box(modifier = Modifier.size(8.dp).background(accent, CircleShape))
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = foreground, maxLines = 1)
    }
}

@Composable
private fun EditorCanvas(uiState: DetectionZonesUiState, viewModel: DetectionZonesViewModel) {
    val config = uiState.config ?: return
    val editor = uiState.editor
    val shape = RoundedCornerShape(20.dp)
    val layerAccent = layerColor(editor.layer)
    val placeholderName = stringResource(Res.string.zones_placeholder_name, editor.nextZoneNumber)
    val polygons = editor.layerShapes.mapIndexed { index, item -> EditorPolygon(item.polygon, shapeColor(item, index, layerAccent), item.zone?.displayName) }
    // Only the frame is clipped to the rounded shape. The editor sits on top unclipped, so a
    // corner handle on the very edge of the frame draws whole, over the border, instead of
    // being sliced in half by the clip.
    //
    // As large as its place allows at the frame's own shape: the full width in a scrolling
    // page, or the full height where it has a pane to itself (see DetectionZonesScreen).
    Box(
        modifier = Modifier.aspectRatio(config.detectWidth.toFloat() / config.detectHeight.coerceAtLeast(1)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f), shape),
        ) {
            // The frame is at detect resolution, the same aspect ratio as this box, so FillBounds
            // doesn't distort it — and makes canvas pixels map to relative coordinates by division.
            uiState.snapshotUrl?.let { url ->
                AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
            }
        }
        MaskPolygonEditor(
            polygons = polygons,
            selectedIndex = editor.selectedIndex,
            selectedVertexIndex = editor.selectedVertexIndex,
            draft = editor.draft,
            draftColor = if (editor.layer == MaskLayer.ZONES) zoneColor(editor.layerShapes.size) else layerAccent,
            onTap = viewModel::tapAt,
            onFinishDraft = { viewModel.finishDraft(placeholderName) },
            onSelectVertex = viewModel::selectVertex,
            onInsertVertex = viewModel::insertVertex,
            onMoveVertex = viewModel::moveVertex,
            onMoveDraftVertex = viewModel::moveDraftVertex,
            labelStyle = MaterialTheme.typography.labelSmall,
            modifier = Modifier.fillMaxSize().testTag(DETECTION_ZONES_CANVAS_TEST_TAG),
        )
        IconButton(onClick = viewModel::refreshSnapshot, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
            Icon(Icons.Filled.Refresh, contentDescription = stringResource(Res.string.zones_refresh_frame), tint = Color.White.copy(alpha = 0.85f))
        }
    }
}

@Composable
private fun Toolbar(uiState: DetectionZonesUiState, viewModel: DetectionZonesViewModel) {
    val editor = uiState.editor
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (editor.isDrawing) {
            val placeholderName = stringResource(Res.string.zones_placeholder_name, editor.nextZoneNumber)
            OutlinedButton(onClick = viewModel::undoDraftPoint, enabled = !editor.draft.isNullOrEmpty()) { Text(stringResource(Res.string.common_undo)) }
            OutlinedButton(onClick = viewModel::cancelDraft) { Text(stringResource(Res.string.common_cancel)) }
            Button(onClick = { viewModel.finishDraft(placeholderName) }, enabled = editor.canFinishDraft) { Text(stringResource(Res.string.common_done)) }
        } else {
            Button(onClick = viewModel::startDraft, enabled = !uiState.isSaving) {
                Text(editor.layer.sentence(zones = Res.string.zones_add_zone, ignoreAreas = Res.string.zones_add_ignore_area))
            }
            if (editor.selectedVertexIndex != null) {
                OutlinedButton(onClick = viewModel::removeSelectedVertex, enabled = editor.canRemoveSelectedVertex && !uiState.isSaving) {
                    Text(stringResource(Res.string.zones_remove_corner))
                }
            } else {
                OutlinedButton(onClick = viewModel::deleteSelected, enabled = editor.selectedIndex != null && !uiState.isSaving) {
                    Text(stringResource(Res.string.common_delete))
                }
            }
        }
    }
}

/** Appears the moment there's something to save, so saving is never a hunt for a header button. */
@Composable
private fun SaveBar(uiState: DetectionZonesUiState, onSave: () -> Unit, onDiscard: () -> Unit) {
    val layers = uiState.editor.dirtyLayers.map { stringResource(it.inlineName) }
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = when {
                uiState.isSaving -> stringResource(Res.string.zones_saving)
                uiState.saveError != null -> uiState.saveError.resolve()
                layers.size >= 3 -> stringResource(Res.string.zones_unsaved_three, layers[0], layers[1], layers[2])
                layers.size == 2 -> stringResource(Res.string.zones_unsaved_two, layers[0], layers[1])
                else -> stringResource(Res.string.zones_unsaved_one, layers.firstOrNull().orEmpty())
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (uiState.saveError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDiscard, enabled = !uiState.isSaving) { Text(stringResource(Res.string.zones_discard)) }
        Button(onClick = onSave, enabled = uiState.editor.isDirty && !uiState.isSaving) {
            if (uiState.isSaving) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Text(stringResource(Res.string.common_save))
            }
        }
    }
}

@Composable
private fun StatusLine(uiState: DetectionZonesUiState) {
    val editor = uiState.editor
    val layer = editor.layer
    val text = when {
        uiState.justSaved -> layer.sentenceRes(zones = Res.string.zones_saved_zones, ignoreAreas = Res.string.zones_saved_ignore_areas)
        editor.isDrawing && !editor.canFinishDraft -> Res.string.zones_hint_place_corners
        editor.isDrawing -> Res.string.zones_hint_keep_adding
        editor.selectedVertexIndex != null && !editor.canRemoveSelectedVertex -> Res.string.zones_hint_corner_minimum
        editor.selectedVertexIndex != null -> Res.string.zones_hint_corner_selected
        editor.selectedIndex != null && layer == MaskLayer.ZONES -> Res.string.zones_hint_zone_selected
        editor.selectedIndex != null -> Res.string.zones_hint_area_selected
        editor.polygons.isEmpty() && layer == MaskLayer.ZONES -> Res.string.zones_hint_no_zones
        editor.polygons.isEmpty() -> Res.string.zones_hint_nothing_ignored
        else -> layer.sentenceRes(zones = Res.string.zones_hint_select_zone, ignoreAreas = Res.string.zones_hint_select_ignore_area)
    }
    val color = if (uiState.justSaved) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
    Text(text = stringResource(text), style = MaterialTheme.typography.bodySmall, color = color)
}

/** The zones on this camera as rows, with the selected one expanded into its name and object filter. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ZoneList(uiState: DetectionZonesUiState, viewModel: DetectionZonesViewModel) {
    val editor = uiState.editor
    if (editor.layerShapes.isEmpty()) return
    val intoView = remember { BringIntoViewRequester() }
    // The details of a just-selected (or just-drawn) zone sit below the canvas and toolbar,
    // often under the fold — scroll them into view so naming it doesn't need a hunt.
    LaunchedEffect(editor.selectedIndex, editor.layerShapes.size) {
        if (editor.selectedIndex != null) intoView.bringIntoView()
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        editor.layerShapes.forEachIndexed { index, item ->
            val zone = item.zone ?: return@forEachIndexed
            val selected = index == editor.selectedIndex
            val rowShape = RoundedCornerShape(16.dp)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .let { if (selected) it.bringIntoViewRequester(intoView) else it }
                    .clip(rowShape)
                    .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f), rowShape)
                    .clickable { viewModel.select(if (selected) null else index) }
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(12.dp).background(zoneColor(index), CircleShape))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = zone.displayName, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                        Text(
                            text = if (zone.objects.isEmpty()) {
                                stringResource(Res.string.zones_any_object)
                            } else {
                                zone.objects.joinToString(stringResource(Res.string.common_list_separator))
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    if (zone.name !in editor.savedZoneNames) {
                        Text(text = stringResource(Res.string.zones_new_badge), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
                if (selected) {
                    ZoneDetailsEditor(
                        zoneName = zone.name,
                        friendlyName = zone.friendlyName ?: "",
                        isPlaceholderName = zone.name !in editor.savedZoneNames && zone.isPlaceholderName,
                        objects = zone.objects,
                        trackedObjects = uiState.config?.trackedObjects.orEmpty(),
                        viewModel = viewModel,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ZoneDetailsEditor(
    zoneName: String,
    friendlyName: String,
    isPlaceholderName: Boolean,
    objects: List<String>,
    trackedObjects: List<String>,
    viewModel: DetectionZonesViewModel,
) {
    // A placeholder like "Zone 2" opens fully selected, so the first thing typed replaces it.
    var field by remember(zoneName) {
        mutableStateOf(TextFieldValue(friendlyName, selection = if (isPlaceholderName) TextRange(0, friendlyName.length) else TextRange(friendlyName.length)))
    }
    if (field.text != friendlyName) field = field.copy(text = friendlyName, selection = TextRange(friendlyName.length))
    val focus = remember { FocusRequester() }
    // A brand-new zone wants a name first: focus the field with the placeholder selected, so
    // the first keystroke replaces "Zone 2" (a tap into the field would only move the cursor).
    LaunchedEffect(zoneName) { if (isPlaceholderName) focus.requestFocus() }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = field,
            onValueChange = { value ->
                // The IME also reports selection and composition changes through here; only a
                // change to the text is a rename (and anything else would read as an edit).
                val renamed = value.text != field.text
                field = value
                if (renamed) viewModel.renameSelectedZone(value.text)
            },
            label = { Text(stringResource(Res.string.zones_name_label)) },
            supportingText = { Text(stringResource(Res.string.zones_frigate_key, zoneName)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
        Text(text = stringResource(Res.string.zones_counts_objects), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(label = stringResource(Res.string.zones_any_object), accent = null, selected = objects.isEmpty(), onClick = { viewModel.setSelectedZoneObjects(emptyList()) })
            trackedObjects.forEach { label ->
                Chip(label = label, accent = null, selected = label in objects, onClick = { viewModel.toggleSelectedZoneObject(label) })
            }
        }
    }
}

@Composable
private fun ErrorPanel(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = onRetry) { Text(stringResource(Res.string.common_retry)) }
    }
}

/** The editor's sentences say "zone" on the zones layer and "ignore area" on the other two; this picks which. */
private fun MaskLayer.sentenceRes(zones: StringResource, ignoreAreas: StringResource): StringResource =
    if (isIgnoreArea) ignoreAreas else zones

@Composable
private fun MaskLayer.sentence(zones: StringResource, ignoreAreas: StringResource): String = stringResource(sentenceRes(zones, ignoreAreas))

@Composable
private fun layerColor(layer: MaskLayer): Color = when (layer) {
    MaskLayer.ZONES -> MaterialTheme.colorScheme.primary
    MaskLayer.OBJECT_MASK -> MaterialTheme.colorScheme.error
    MaskLayer.MOTION_MASK -> MaterialTheme.colorScheme.tertiary
}

/** Ignore areas share their layer's colour; each zone gets its own from a fixed palette, by position. */
private fun shapeColor(shape: EditorShape, index: Int, layerAccent: Color): Color =
    if (shape.zone != null) zoneColor(index) else layerAccent

/** Distinct, saturated colours that stay readable over dark night-time frames. */
private val ZONE_PALETTE = listOf(
    Color(0xFF4FC3F7), // sky blue
    Color(0xFFFFD54F), // amber
    Color(0xFF81C784), // green
    Color(0xFFBA68C8), // purple
    Color(0xFFFF8A65), // coral
    Color(0xFF4DD0E1), // teal
    Color(0xFFF06292), // pink
    Color(0xFFAED581), // lime
)

private fun zoneColor(index: Int): Color = ZONE_PALETTE[index.mod(ZONE_PALETTE.size)]

/** The editor's drawing surface over the camera frame, for tests to place corners on. */
internal const val DETECTION_ZONES_CANVAS_TEST_TAG = "detection_zones_canvas"
