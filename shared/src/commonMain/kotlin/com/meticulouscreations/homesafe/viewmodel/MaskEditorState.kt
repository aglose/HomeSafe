package com.meticulouscreations.homesafe.viewmodel

import androidx.compose.runtime.Immutable
import com.meticulouscreations.homesafe.domain.model.CameraDetectionConfig
import com.meticulouscreations.homesafe.domain.model.DetectionZone
import com.meticulouscreations.homesafe.domain.model.MaskLayer
import com.meticulouscreations.homesafe.domain.model.MaskPoint
import com.meticulouscreations.homesafe.domain.model.MaskPolygon

/** The zone-specific part of an [EditorShape]; null on the mask layers. */
@Immutable
data class ZoneInfo(
    /** Frigate's config key for the zone — fixed once saved, because events refer to it. */
    val name: String,
    val friendlyName: String?,
    /** Labels that count in this zone; empty means every tracked object. */
    val objects: List<String> = emptyList(),
) {
    val displayName: String get() = friendlyName?.takeIf { it.isNotBlank() } ?: name
}

/** One polygon on a layer, plus its zone details when the layer is [MaskLayer.ZONES]. */
@Immutable
data class EditorShape(val polygon: MaskPolygon, val zone: ZoneInfo? = null)

/**
 * The polygon editor's whole state, as a pure value: which layer is showing, that layer's
 * shapes, the one being drawn ([draft]) and the one selected for tweaking. Every edit is a
 * function from one state to the next, so the gesture handling in the UI stays thin and the
 * rules (when a draft can close, what a tap on empty space means, how zones get named) are
 * unit-testable.
 */
@Immutable
data class MaskEditorState(
    val layer: MaskLayer = MaskLayer.OBJECT_MASK,
    val shapes: Map<MaskLayer, List<EditorShape>> = MaskLayer.entries.associateWith { emptyList() },
    /** Corners placed so far for a polygon still being drawn; null when not drawing. */
    val draft: List<MaskPoint>? = null,
    /** Index into the current layer's shapes, or null. */
    val selectedIndex: Int? = null,
    /** A corner of the selected shape singled out for removal, or null. */
    val selectedVertexIndex: Int? = null,
    /** Layers whose shapes differ from what the server last reported. */
    val dirtyLayers: Set<MaskLayer> = emptySet(),
    /** Zone keys the server already knows; renaming one of these changes only its friendly name. */
    val savedZoneNames: Set<String> = emptySet(),
) {
    val layerShapes: List<EditorShape> get() = shapes.getValue(layer)
    val polygons: List<MaskPolygon> get() = layerShapes.map { it.polygon }
    val selectedShape: EditorShape? get() = selectedIndex?.let { layerShapes.getOrNull(it) }

    /** Whether the selected corner can go: a polygon keeps at least three. */
    val canRemoveSelectedVertex: Boolean
        get() = selectedVertexIndex != null && (selectedShape?.polygon?.points?.size ?: 0) > MaskPolygon.MIN_POINTS
    val isDrawing: Boolean get() = draft != null
    val canFinishDraft: Boolean get() = (draft?.size ?: 0) >= MaskPolygon.MIN_POINTS
    val isDirty: Boolean get() = dirtyLayers.isNotEmpty()

    fun masks(of: MaskLayer): List<MaskPolygon> = shapes.getValue(of).map { it.polygon }

    fun zones(): List<DetectionZone> = shapes.getValue(MaskLayer.ZONES).mapNotNull { shape ->
        shape.zone?.let { DetectionZone(it.name, it.friendlyName, shape.polygon, it.objects) }
    }

    fun switchLayer(newLayer: MaskLayer): MaskEditorState =
        if (newLayer == layer) this else copy(layer = newLayer, draft = null, selectedIndex = null, selectedVertexIndex = null)

    /**
     * A tap on empty canvas: deselects if something was selected, otherwise places a corner
     * (starting a new polygon if none is in progress). A tap inside an existing shape while not
     * drawing selects it instead.
     */
    fun tapAt(point: MaskPoint): MaskEditorState {
        val p = point.clamped()
        if (draft != null) return copy(draft = draft + p)
        val hit = polygons.indexOfLast { it.contains(p) }
        return when {
            hit >= 0 -> copy(selectedIndex = hit, selectedVertexIndex = if (hit == selectedIndex) selectedVertexIndex else null)
            selectedIndex != null -> copy(selectedIndex = null, selectedVertexIndex = null)
            else -> copy(draft = listOf(p))
        }
    }

    fun startDraft(): MaskEditorState = copy(draft = emptyList(), selectedIndex = null, selectedVertexIndex = null)

    fun undoDraftPoint(): MaskEditorState = when {
        draft == null -> this
        draft.size <= 1 -> copy(draft = null)
        else -> copy(draft = draft.dropLast(1))
    }

    fun cancelDraft(): MaskEditorState = copy(draft = null)

    /**
     * Closes the draft into a real shape on the current layer, selecting it. On the zones layer
     * the new zone gets a placeholder name ("Zone 3" / `zone_3`) that the user then renames. No-op
     * with fewer than three corners.
     */
    fun finishDraft(): MaskEditorState {
        val points = draft ?: return this
        if (points.size < MaskPolygon.MIN_POINTS) return this
        val zone = if (layer == MaskLayer.ZONES) placeholderZone() else null
        val updated = layerShapes + EditorShape(MaskPolygon(points), zone)
        return copy(
            shapes = shapes + (layer to updated),
            draft = null,
            selectedIndex = updated.lastIndex,
            selectedVertexIndex = null,
            dirtyLayers = dirtyLayers + layer,
        )
    }

    fun select(index: Int?): MaskEditorState =
        copy(selectedIndex = index?.takeIf { it in layerShapes.indices }, selectedVertexIndex = null)

    /** Singles out one corner of the selected shape (for removal); null clears it. */
    fun selectVertex(vertexIndex: Int?): MaskEditorState {
        val points = selectedShape?.polygon?.points ?: return copy(selectedVertexIndex = null)
        return copy(selectedVertexIndex = vertexIndex?.takeIf { it in points.indices })
    }

    /**
     * Adds a corner to [shapeIndex] on the edge that starts at corner [edgeIndex] (the edge from
     * the last corner back to the first is edge `lastIndex`), and selects the new corner so a
     * drag can carry straight on with it. This is how a saved shape grows past its first
     * outline: tap or drag the dot in the middle of an edge.
     */
    fun insertVertex(shapeIndex: Int, edgeIndex: Int, at: MaskPoint): MaskEditorState {
        val shape = layerShapes.getOrNull(shapeIndex) ?: return this
        val points = shape.polygon.points
        if (edgeIndex !in points.indices) return this
        val inserted = points.toMutableList().also { it.add(edgeIndex + 1, at.clamped()) }
        return replaceShape(shapeIndex, shape.copy(polygon = shape.polygon.copy(points = inserted)))
            .copy(selectedIndex = shapeIndex, selectedVertexIndex = edgeIndex + 1)
    }

    /** Removes the selected corner, as long as the shape keeps three. */
    fun removeSelectedVertex(): MaskEditorState {
        val shapeIndex = selectedIndex ?: return this
        val vertexIndex = selectedVertexIndex ?: return this
        val shape = layerShapes.getOrNull(shapeIndex) ?: return this
        val points = shape.polygon.points
        if (vertexIndex !in points.indices || points.size <= MaskPolygon.MIN_POINTS) return this
        val trimmed = points.toMutableList().also { it.removeAt(vertexIndex) }
        return replaceShape(shapeIndex, shape.copy(polygon = shape.polygon.copy(points = trimmed))).copy(selectedVertexIndex = null)
    }

    fun moveVertex(shapeIndex: Int, vertexIndex: Int, to: MaskPoint): MaskEditorState {
        val shape = layerShapes.getOrNull(shapeIndex) ?: return this
        if (vertexIndex !in shape.polygon.points.indices) return this
        val moved = shape.polygon.copy(points = shape.polygon.points.toMutableList().also { it[vertexIndex] = to.clamped() })
        return replaceShape(shapeIndex, shape.copy(polygon = moved)).copy(selectedIndex = shapeIndex, selectedVertexIndex = vertexIndex)
    }

    fun moveDraftVertex(vertexIndex: Int, to: MaskPoint): MaskEditorState {
        val points = draft ?: return this
        if (vertexIndex !in points.indices) return this
        return copy(draft = points.toMutableList().also { it[vertexIndex] = to.clamped() })
    }

    fun deleteSelected(): MaskEditorState {
        val index = selectedIndex ?: return this
        if (index !in layerShapes.indices) return copy(selectedIndex = null)
        val updated = layerShapes.toMutableList().also { it.removeAt(index) }
        return copy(shapes = shapes + (layer to updated), selectedIndex = null, selectedVertexIndex = null, dirtyLayers = dirtyLayers + layer)
    }

    /**
     * Renames the selected zone. A zone the server doesn't know yet also gets its config key
     * from the new name ("Front lawn" -> `front_lawn`), kept unique on the layer; a saved zone
     * keeps its key so Frigate's event history for it stays intact.
     */
    fun renameSelectedZone(friendlyName: String): MaskEditorState {
        val index = selectedIndex ?: return this
        val shape = layerShapes.getOrNull(index) ?: return this
        val zone = shape.zone ?: return this
        val name = if (zone.name in savedZoneNames) {
            zone.name
        } else {
            uniqueZoneName(DetectionZone.slug(friendlyName), excludingIndex = index)
        }
        return replaceShape(index, shape.copy(zone = zone.copy(name = name, friendlyName = friendlyName)))
    }

    fun setSelectedZoneObjects(objects: List<String>): MaskEditorState {
        val index = selectedIndex ?: return this
        val shape = layerShapes.getOrNull(index) ?: return this
        val zone = shape.zone ?: return this
        return replaceShape(index, shape.copy(zone = zone.copy(objects = objects.distinct())))
    }

    /** Adopts what the server reports, keeping the current layer; edits in progress are discarded. */
    fun loadedFrom(config: CameraDetectionConfig): MaskEditorState = copy(
        shapes = mapOf(
            MaskLayer.OBJECT_MASK to config.objectMasks.map { EditorShape(it) },
            MaskLayer.MOTION_MASK to config.motionMasks.map { EditorShape(it) },
            MaskLayer.ZONES to config.zones.map { EditorShape(it.polygon, ZoneInfo(it.name, it.friendlyName, it.objects)) },
        ),
        draft = null,
        selectedIndex = null,
        selectedVertexIndex = null,
        dirtyLayers = emptySet(),
        savedZoneNames = config.zones.map { it.name }.toSet(),
    )

    private fun replaceShape(index: Int, shape: EditorShape): MaskEditorState {
        val updated = layerShapes.toMutableList().also { it[index] = shape }
        return copy(shapes = shapes + (layer to updated), dirtyLayers = dirtyLayers + layer)
    }

    private fun placeholderZone(): ZoneInfo {
        var n = shapes.getValue(MaskLayer.ZONES).size + 1
        while (shapes.getValue(MaskLayer.ZONES).any { it.zone?.name == "zone_$n" }) n++
        return ZoneInfo(name = "zone_$n", friendlyName = "Zone $n")
    }

    private fun uniqueZoneName(base: String, excludingIndex: Int): String {
        val taken = shapes.getValue(MaskLayer.ZONES)
            .filterIndexed { i, _ -> i != excludingIndex }
            .mapNotNull { it.zone?.name }
            .toSet() + savedZoneNames
        if (base !in taken) return base
        var n = 2
        while ("${base}_$n" in taken) n++
        return "${base}_$n"
    }
}
