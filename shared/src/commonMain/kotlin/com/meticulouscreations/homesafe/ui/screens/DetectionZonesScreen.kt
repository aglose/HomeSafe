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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.meticulouscreations.homesafe.domain.model.MaskLayer
import com.meticulouscreations.homesafe.domain.model.cameraDisplayName
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.domain.usecase.GetDetectionConfigUseCase
import com.meticulouscreations.homesafe.domain.usecase.SaveDetectionMasksUseCase
import com.meticulouscreations.homesafe.domain.usecase.SaveDetectionZonesUseCase
import com.meticulouscreations.homesafe.ui.components.EditorPolygon
import com.meticulouscreations.homesafe.ui.components.MaskPolygonEditor
import com.meticulouscreations.homesafe.viewmodel.DetectionZonesUiState
import com.meticulouscreations.homesafe.viewmodel.DetectionZonesViewModel
import com.meticulouscreations.homesafe.viewmodel.EditorShape
import com.meticulouscreations.homesafe.viewmodel.MaskEditorState

/**
 * Google Home-style "zones" editor for one camera: a still frame with polygon layers drawn over
 * it — two exclusion layers (object and motion masks) and one labelling layer (named zones).
 * Tap to place corners, tap the first corner (or Done) to close the shape, drag corners to
 * adjust, and Save pushes the changed layers to Frigate, which applies them immediately.
 */
@Composable
fun DetectionZonesScreen(
    cameraName: String,
    connectionRepository: ConnectionRepository,
    getDetectionConfigUseCase: GetDetectionConfigUseCase,
    saveDetectionMasksUseCase: SaveDetectionMasksUseCase,
    saveDetectionZonesUseCase: SaveDetectionZonesUseCase,
    onBack: () -> Unit,
) {
    val viewModel = viewModel(key = "zones:$cameraName") {
        DetectionZonesViewModel(
            cameraName = cameraName,
            connectionRepository = connectionRepository,
            getDetectionConfigUseCase = getDetectionConfigUseCase,
            saveDetectionMasksUseCase = saveDetectionMasksUseCase,
            saveDetectionZonesUseCase = saveDetectionZonesUseCase,
        )
    }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Header(cameraName = cameraName, uiState = uiState, onBack = onBack, onSave = viewModel::save)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = bottomNavClearance()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LayerPicker(editor = uiState.editor, onSelect = viewModel::switchLayer)

            when {
                uiState.isLoading -> Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
                uiState.loadError != null -> ErrorPanel(message = uiState.loadError.orEmpty(), onRetry = viewModel::load)
                else -> EditorCanvas(uiState = uiState, viewModel = viewModel)
            }

            if (!uiState.isLoading && uiState.loadError == null) {
                Toolbar(uiState = uiState, viewModel = viewModel)
                StatusLine(uiState = uiState)
                if (uiState.editor.layer == MaskLayer.ZONES) {
                    ZoneList(uiState = uiState, viewModel = viewModel)
                }
            }
        }
    }
}

@Composable
private fun Header(cameraName: String, uiState: DetectionZonesUiState, onBack: () -> Unit, onSave: () -> Unit) {
    // This header replaces the shell's top bar (hidden while a nested screen is up), so it
    // steps in from the status bar itself.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.primary)
        }
        Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Detection zones",
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
                Text("Save")
            }
        }
    }
}

@Composable
private fun LayerPicker(editor: MaskEditorState, onSelect: (MaskLayer) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MaskLayer.entries.forEach { layer ->
                val count = editor.shapes.getValue(layer).size
                val label = if (count > 0) "${layer.label} · $count" else layer.label
                Chip(label = label, accent = layerColor(layer), selected = layer == editor.layer, onClick = { onSelect(layer) })
            }
        }
        Text(
            text = editor.layer.description,
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
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = foreground)
    }
}

@Composable
private fun EditorCanvas(uiState: DetectionZonesUiState, viewModel: DetectionZonesViewModel) {
    val config = uiState.config ?: return
    val editor = uiState.editor
    val shape = RoundedCornerShape(20.dp)
    val layerAccent = layerColor(editor.layer)
    val polygons = editor.layerShapes.mapIndexed { index, item -> EditorPolygon(item.polygon, shapeColor(item, index, layerAccent), item.zone?.displayName) }
    // Only the frame is clipped to the rounded shape. The editor sits on top unclipped, so a
    // corner handle on the very edge of the frame draws whole, over the border, instead of
    // being sliced in half by the clip.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(config.detectWidth.toFloat() / config.detectHeight.coerceAtLeast(1)),
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
            draft = editor.draft,
            draftColor = if (editor.layer == MaskLayer.ZONES) zoneColor(editor.layerShapes.size) else layerAccent,
            onTap = viewModel::tapAt,
            onFinishDraft = viewModel::finishDraft,
            onMoveVertex = viewModel::moveVertex,
            onMoveDraftVertex = viewModel::moveDraftVertex,
            labelStyle = MaterialTheme.typography.labelSmall,
            modifier = Modifier.fillMaxSize(),
        )
        IconButton(onClick = viewModel::refreshSnapshot, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
            Icon(Icons.Filled.Refresh, contentDescription = "Refresh frame", tint = Color.White.copy(alpha = 0.85f))
        }
    }
}

@Composable
private fun Toolbar(uiState: DetectionZonesUiState, viewModel: DetectionZonesViewModel) {
    val editor = uiState.editor
    val noun = if (editor.layer == MaskLayer.ZONES) "zone" else "mask"
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (editor.isDrawing) {
            OutlinedButton(onClick = viewModel::undoDraftPoint, enabled = !editor.draft.isNullOrEmpty()) { Text("Undo") }
            OutlinedButton(onClick = viewModel::cancelDraft) { Text("Cancel") }
            Button(onClick = viewModel::finishDraft, enabled = editor.canFinishDraft) { Text("Done") }
        } else {
            Button(onClick = viewModel::startDraft, enabled = !uiState.isSaving) { Text("Add $noun") }
            OutlinedButton(onClick = viewModel::deleteSelected, enabled = editor.selectedIndex != null && !uiState.isSaving) { Text("Delete") }
            if (editor.isDirty) {
                TextButton(onClick = viewModel::discardChanges, enabled = !uiState.isSaving) { Text("Discard") }
            }
        }
    }
}

@Composable
private fun StatusLine(uiState: DetectionZonesUiState) {
    val editor = uiState.editor
    val noun = if (editor.layer == MaskLayer.ZONES) "zone" else "mask"
    val (text, color) = when {
        uiState.saveError != null -> "Couldn't save: ${uiState.saveError}" to MaterialTheme.colorScheme.error
        uiState.isSaving -> "Saving to Frigate…" to MaterialTheme.colorScheme.onSurfaceVariant
        uiState.justSaved -> "Saved. Frigate is using the new ${noun}s now." to MaterialTheme.colorScheme.secondary
        editor.isDrawing && !editor.canFinishDraft -> "Tap the frame to place corners (at least three)." to MaterialTheme.colorScheme.onSurfaceVariant
        editor.isDrawing -> "Keep adding corners, or tap the first one (or Done) to close the shape." to MaterialTheme.colorScheme.onSurfaceVariant
        editor.selectedIndex != null && editor.layer == MaskLayer.ZONES -> "Name the zone below, pick which objects count in it, or drag a corner." to MaterialTheme.colorScheme.onSurfaceVariant
        editor.selectedIndex != null -> "Drag a corner to adjust, or Delete to remove this mask." to MaterialTheme.colorScheme.onSurfaceVariant
        editor.isDirty -> "Unsaved changes — Save to apply them on the server." to MaterialTheme.colorScheme.onSurfaceVariant
        editor.polygons.isEmpty() && editor.layer == MaskLayer.ZONES -> "No zones yet. Tap the frame or Add zone to outline an area like the driveway." to MaterialTheme.colorScheme.onSurfaceVariant
        editor.polygons.isEmpty() -> "No ${editor.layer.label.lowercase()} masks yet. Tap the frame or Add mask to draw one." to MaterialTheme.colorScheme.onSurfaceVariant
        else -> "Tap a $noun to select it, or tap empty space to start a new one." to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = color)
}

/** The zones on this camera as rows, with the selected one expanded into its name and object filter. */
@Composable
private fun ZoneList(uiState: DetectionZonesUiState, viewModel: DetectionZonesViewModel) {
    val editor = uiState.editor
    if (editor.layerShapes.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        editor.layerShapes.forEachIndexed { index, item ->
            val zone = item.zone ?: return@forEachIndexed
            val selected = index == editor.selectedIndex
            val rowShape = RoundedCornerShape(16.dp)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
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
                            text = if (zone.objects.isEmpty()) "Any object" else zone.objects.joinToString(", "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                if (selected) {
                    ZoneDetailsEditor(zoneName = zone.name, friendlyName = zone.friendlyName ?: "", objects = zone.objects, trackedObjects = uiState.config?.trackedObjects.orEmpty(), viewModel = viewModel)
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
    objects: List<String>,
    trackedObjects: List<String>,
    viewModel: DetectionZonesViewModel,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = friendlyName,
            onValueChange = viewModel::renameSelectedZone,
            label = { Text("Name") },
            supportingText = { Text("Frigate key: $zoneName") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(text = "Counts these objects", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(label = "Any object", accent = null, selected = objects.isEmpty(), onClick = { viewModel.setSelectedZoneObjects(emptyList()) })
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
        Text(text = "Couldn't load detection zones: $message", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun layerColor(layer: MaskLayer): Color = when (layer) {
    MaskLayer.OBJECT_MASK -> MaterialTheme.colorScheme.error
    MaskLayer.MOTION_MASK -> MaterialTheme.colorScheme.tertiary
    MaskLayer.ZONES -> MaterialTheme.colorScheme.primary
}

/** Masks share their layer's colour; each zone gets its own from a fixed palette, by position. */
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
