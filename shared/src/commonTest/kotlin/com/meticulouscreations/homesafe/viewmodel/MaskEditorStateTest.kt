package com.meticulouscreations.homesafe.viewmodel

import com.meticulouscreations.homesafe.domain.model.CameraDetectionConfig
import com.meticulouscreations.homesafe.domain.model.DetectionZone
import com.meticulouscreations.homesafe.domain.model.MaskLayer
import com.meticulouscreations.homesafe.domain.model.MaskPoint
import com.meticulouscreations.homesafe.domain.model.MaskPolygon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MaskEditorStateTest {

    private val square = MaskPolygon(listOf(MaskPoint(0.1, 0.1), MaskPoint(0.4, 0.1), MaskPoint(0.4, 0.4), MaskPoint(0.1, 0.4)))

    private fun withObjectMask(vararg polygons: MaskPolygon) = MaskEditorState().copy(
        shapes = MaskEditorState().shapes + (MaskLayer.OBJECT_MASK to polygons.map { EditorShape(it) }),
    )

    /** Explicit Add-then-tap, so corners can land inside an existing shape without selecting it. */
    private fun MaskEditorState.drawTriangle(): MaskEditorState =
        startDraft().tapAt(MaskPoint(0.5, 0.5)).tapAt(MaskPoint(0.7, 0.5)).tapAt(MaskPoint(0.7, 0.7)).finishDraft()

    @Test
    fun tappingEmptyCanvasStartsADraftAndKeepsAddingCorners() {
        val state = MaskEditorState().tapAt(MaskPoint(0.5, 0.5)).tapAt(MaskPoint(0.7, 0.5))
        assertEquals(listOf(MaskPoint(0.5, 0.5), MaskPoint(0.7, 0.5)), state.draft)
        assertFalse(state.canFinishDraft)
        assertFalse(state.isDirty)
    }

    @Test
    fun aDraftNeedsThreeCornersToBecomeAMask() {
        val two = MaskEditorState().tapAt(MaskPoint(0.5, 0.5)).tapAt(MaskPoint(0.7, 0.5))
        assertEquals(two, two.finishDraft())

        val finished = two.tapAt(MaskPoint(0.7, 0.7)).finishDraft()
        assertNull(finished.draft)
        assertEquals(1, finished.polygons.size)
        assertEquals(0, finished.selectedIndex)
        assertNull(finished.selectedShape?.zone, "masks carry no zone details")
        assertEquals(setOf(MaskLayer.OBJECT_MASK), finished.dirtyLayers)
    }

    @Test
    fun tappingInsideAMaskSelectsItAndTappingEmptySpaceDeselects() {
        val state = withObjectMask(square)
        val selected = state.tapAt(MaskPoint(0.2, 0.2))
        assertEquals(0, selected.selectedIndex)
        assertNull(selected.draft)

        val deselected = selected.tapAt(MaskPoint(0.8, 0.8))
        assertNull(deselected.selectedIndex)
        assertNull(deselected.draft, "a deselecting tap must not start a draft")
    }

    @Test
    fun cornerEditsMarkTheLayerDirty() {
        val moved = withObjectMask(square).moveVertex(0, 0, MaskPoint(-0.5, 2.0))
        assertEquals(MaskPoint(0.0, 1.0), moved.polygons[0].points[0], "clamped into the frame")
        assertTrue(moved.isDirty)

        val deleted = moved.select(0).deleteSelected()
        assertTrue(deleted.polygons.isEmpty())
        assertNull(deleted.selectedIndex)
        assertTrue(deleted.isDirty)
    }

    @Test
    fun undoRemovesTheLastCornerAndEmptyingTheDraftEndsIt() {
        val state = MaskEditorState().tapAt(MaskPoint(0.5, 0.5)).tapAt(MaskPoint(0.7, 0.5))
        assertEquals(listOf(MaskPoint(0.5, 0.5)), state.undoDraftPoint().draft)
        assertNull(state.undoDraftPoint().undoDraftPoint().draft)
    }

    @Test
    fun switchingLayersDropsTheDraftAndSelectionButKeepsEachLayersShapes() {
        val state = withObjectMask(square).select(0).startDraft().switchLayer(MaskLayer.MOTION_MASK)
        assertEquals(MaskLayer.MOTION_MASK, state.layer)
        assertNull(state.draft)
        assertNull(state.selectedIndex)
        assertTrue(state.polygons.isEmpty())
        assertEquals(listOf(square), state.masks(MaskLayer.OBJECT_MASK))
    }

    @Test
    fun loadingFromTheServerReplacesEverythingAndClearsDirtiness() {
        val edited = MaskEditorState().drawTriangle()
        val config = CameraDetectionConfig(
            "cam",
            640,
            360,
            objectMasks = emptyList(),
            motionMasks = listOf(square),
            zones = listOf(DetectionZone("driveway", "Driveway", square, listOf("car"))),
        )
        val loaded = edited.loadedFrom(config)
        assertFalse(loaded.isDirty)
        assertTrue(loaded.masks(MaskLayer.OBJECT_MASK).isEmpty())
        assertEquals(listOf(square), loaded.masks(MaskLayer.MOTION_MASK))
        assertEquals(config.zones, loaded.zones())
        assertEquals(setOf("driveway"), loaded.savedZoneNames)
        assertEquals(MaskLayer.OBJECT_MASK, loaded.layer, "the layer being viewed is kept")
    }

    @Test
    fun finishingADraftOnTheZonesLayerCreatesAPlaceholderNamedZone() {
        val state = MaskEditorState().switchLayer(MaskLayer.ZONES).drawTriangle()
        val zone = assertNotNull(state.selectedShape?.zone)
        assertEquals("zone_1", zone.name)
        assertEquals("Zone 1", zone.friendlyName)
        assertTrue(zone.objects.isEmpty(), "a new zone counts every object")
        assertEquals(setOf(MaskLayer.ZONES), state.dirtyLayers)

        val second = state.drawTriangle()
        assertEquals("zone_2", second.selectedShape?.zone?.name)
    }

    @Test
    fun renamingAnUnsavedZoneAlsoSetsItsConfigKeyFromTheName() {
        val state = MaskEditorState().switchLayer(MaskLayer.ZONES).drawTriangle().renameSelectedZone("Front lawn!")
        val zone = assertNotNull(state.selectedShape?.zone)
        assertEquals("Front lawn!", zone.friendlyName)
        assertEquals("front_lawn", zone.name)
        assertEquals(listOf(DetectionZone("front_lawn", "Front lawn!", zone.let { state.selectedShape!!.polygon })), state.zones())
    }

    @Test
    fun renamingASavedZoneKeepsItsConfigKey() {
        val config = CameraDetectionConfig("cam", 640, 360, emptyList(), emptyList(), zones = listOf(DetectionZone("driveway", "Driveway", square)))
        val state = MaskEditorState().loadedFrom(config).switchLayer(MaskLayer.ZONES).select(0).renameSelectedZone("Car port")
        val zone = assertNotNull(state.selectedShape?.zone)
        assertEquals("driveway", zone.name, "events already refer to this key")
        assertEquals("Car port", zone.friendlyName)
        assertTrue(state.isDirty)
    }

    @Test
    fun newZoneKeysNeverCollideWithExistingOnes() {
        val config = CameraDetectionConfig("cam", 640, 360, emptyList(), emptyList(), zones = listOf(DetectionZone("driveway", null, square)))
        val state = MaskEditorState().loadedFrom(config).switchLayer(MaskLayer.ZONES).drawTriangle().renameSelectedZone("Driveway")
        assertEquals("driveway_2", state.selectedShape?.zone?.name)
    }

    @Test
    fun zoneObjectFilterIsEditable() {
        val state = MaskEditorState().switchLayer(MaskLayer.ZONES).drawTriangle().setSelectedZoneObjects(listOf("car", "car", "person"))
        assertEquals(listOf("car", "person"), state.selectedShape?.zone?.objects)
        assertTrue(state.isDirty)
    }

    @Test
    fun aCornerCanBeAddedOnAnyEdgeIncludingTheClosingOne() {
        val state = withObjectMask(square).select(0)
        val onFirstEdge = state.insertVertex(0, 0, MaskPoint(0.25, 0.1))
        assertEquals(listOf(MaskPoint(0.1, 0.1), MaskPoint(0.25, 0.1), MaskPoint(0.4, 0.1), MaskPoint(0.4, 0.4), MaskPoint(0.1, 0.4)), onFirstEdge.polygons[0].points)
        assertEquals(1, onFirstEdge.selectedVertexIndex, "the new corner is selected so a drag can carry on with it")
        assertTrue(onFirstEdge.isDirty)

        val onClosingEdge = state.insertVertex(0, 3, MaskPoint(0.1, 0.25))
        assertEquals(MaskPoint(0.1, 0.25), onClosingEdge.polygons[0].points.last())
        assertEquals(4, onClosingEdge.selectedVertexIndex)
    }

    @Test
    fun aCornerCanBeRemovedButNeverBelowThree() {
        val state = withObjectMask(square).select(0).selectVertex(1).removeSelectedVertex()
        assertEquals(3, state.polygons[0].points.size)
        assertNull(state.selectedVertexIndex)
        assertTrue(state.isDirty)

        val floor = state.selectVertex(0)
        assertFalse(floor.canRemoveSelectedVertex)
        assertEquals(floor, floor.removeSelectedVertex())
    }

    @Test
    fun selectingACornerRequiresASelectedShapeAndClearsOnReselect() {
        assertNull(MaskEditorState().selectVertex(0).selectedVertexIndex)
        val state = withObjectMask(square).select(0).selectVertex(2)
        assertEquals(2, state.selectedVertexIndex)
        assertNull(state.select(0).selectedVertexIndex)
        assertNull(state.tapAt(MaskPoint(0.9, 0.9)).selectedVertexIndex, "tapping empty space clears both selections")
    }

    @Test
    fun slugsAreFrigateSafeZoneKeys() {
        assertEquals("front_lawn", DetectionZone.slug("Front lawn"))
        assertEquals("ron_judys_spot", DetectionZone.slug("  Ron & Judy's spot  "))
        assertEquals("zone", DetectionZone.slug("!!!"))
        assertTrue(DetectionZone.isValidName("side-yard_2"))
        assertFalse(DetectionZone.isValidName("side yard"))
    }
}
