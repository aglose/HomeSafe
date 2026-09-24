package com.meticulouscreations.homesafe.uitest

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.domain.model.MaskLayer
import com.meticulouscreations.homesafe.domain.model.MaskPoint
import com.meticulouscreations.homesafe.domain.model.MaskPolygon
import com.meticulouscreations.homesafe.ui.components.EditorPolygon
import com.meticulouscreations.homesafe.ui.components.MaskPolygonEditor
import com.meticulouscreations.homesafe.ui.preview.FrigatePreview
import com.meticulouscreations.homesafe.viewmodel.EditorShape
import com.meticulouscreations.homesafe.viewmodel.MaskEditorState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The detection-zones drawing layer, touched for real. It is wired to a [MaskEditorState] the
 * way DetectionZonesScreen wires it to its view model — every gesture becomes an editor intent,
 * and the new state is drawn back — so these check that each gesture reaches the intent it is
 * meant to: a tap places a corner or closes the shape, singles out a corner or grows the shape by
 * one, selects or lets go; a drag moves a corner, including one that didn't exist until the drag
 * began. The rules for what each intent does are MaskEditorStateTest's.
 *
 * The layer is a fixed 300 x 200dp, inside a [Box] so [FrigatePreview]'s Surface can't stretch
 * it; a point is aimed at by its fractions of that, which is also how the editor reads a touch
 * back. Every handle is at least 60dp from its neighbours, well clear of the 24dp grab radius.
 *
 * Nothing animates, so the clock is left to run. Drags are slow — a second end to end — so the
 * finger has barely left the handle when it crosses touch slop, on any platform's slop.
 */
@OptIn(ExperimentalTestApi::class)
class MaskPolygonEditorUiTest {

    /** A square in the middle of the frame: corners at 0.2 and 0.8, edge dots halfway along. */
    private val square = MaskPolygon(
        listOf(MaskPoint(0.2, 0.2), MaskPoint(0.8, 0.2), MaskPoint(0.8, 0.8), MaskPoint(0.2, 0.8)),
    )

    private fun withSquare(selected: Boolean) = MaskEditorState(
        shapes = MaskLayer.entries.associateWith { layer ->
            if (layer == MaskLayer.OBJECT_MASK) listOf(EditorShape(square)) else emptyList()
        },
        selectedIndex = if (selected) 0 else null,
    )

    private fun ComposeUiTest.setUpEditor(initial: MaskEditorState): State<MaskEditorState> {
        val editor = mutableStateOf(initial)
        setContent {
            FrigatePreview {
                Box {
                    val state = editor.value
                    MaskPolygonEditor(
                        polygons = state.layerShapes.map { EditorPolygon(it.polygon, Color.Red) },
                        selectedIndex = state.selectedIndex,
                        selectedVertexIndex = state.selectedVertexIndex,
                        draft = state.draft,
                        draftColor = Color.Yellow,
                        onTap = { editor.value = editor.value.tapAt(it) },
                        onFinishDraft = { editor.value = editor.value.finishDraft() },
                        onSelectVertex = { editor.value = editor.value.selectVertex(it) },
                        onInsertVertex = { shape, edge, at -> editor.value = editor.value.insertVertex(shape, edge, at) },
                        onMoveVertex = { shape, vertex, to -> editor.value = editor.value.moveVertex(shape, vertex, to) },
                        onMoveDraftVertex = { vertex, to -> editor.value = editor.value.moveDraftVertex(vertex, to) },
                        modifier = Modifier.size(300.dp, 200.dp).testTag(EDITOR_TAG),
                    )
                }
            }
        }
        return editor
    }

    /** Where [point] is on the layer, in this scope's pixels. */
    private fun TouchInjectionScope.spot(point: MaskPoint): Offset =
        Offset((point.x * width).toFloat(), (point.y * height).toFloat())

    private fun ComposeUiTest.tap(point: MaskPoint) {
        onNodeWithTag(EDITOR_TAG).performTouchInput { click(spot(point)) }
    }

    private fun ComposeUiTest.drag(from: MaskPoint, to: MaskPoint) {
        onNodeWithTag(EDITOR_TAG).performTouchInput { swipe(spot(from), spot(to), durationMillis = 1_000) }
    }

    private fun assertNear(expected: MaskPoint, actual: MaskPoint?, message: String? = null) {
        val point = assertNotNull(actual, message)
        assertEquals(expected.x, point.x, TOLERANCE, message)
        assertEquals(expected.y, point.y, TOLERANCE, message)
    }

    @Test
    fun tapsOnAnEmptyFramePlaceTheCornersOfANewShape() = runComposeUiTest {
        val editor = setUpEditor(MaskEditorState())

        tap(MaskPoint(0.2, 0.3))
        tap(MaskPoint(0.7, 0.3))
        tap(MaskPoint(0.5, 0.8))

        val draft = assertNotNull(editor.value.draft, "the first tap should have started a shape")
        assertEquals(3, draft.size)
        assertNear(MaskPoint(0.2, 0.3), draft[0])
        assertNear(MaskPoint(0.7, 0.3), draft[1])
        assertNear(MaskPoint(0.5, 0.8), draft[2])
    }

    @Test
    fun tappingTheFirstCornerAgainClosesTheShape() = runComposeUiTest {
        val editor = setUpEditor(MaskEditorState(draft = listOf(MaskPoint(0.2, 0.3), MaskPoint(0.7, 0.3), MaskPoint(0.5, 0.8))))

        tap(MaskPoint(0.2, 0.3))

        assertNull(editor.value.draft, "closing should have ended the drawing")
        assertEquals(1, editor.value.layerShapes.size)
        assertEquals(3, editor.value.layerShapes.single().polygon.points.size, "closing is not another corner")
        assertEquals(0, editor.value.selectedIndex, "the new shape comes up selected")
    }

    @Test
    fun beforeThereAreThreeCornersTheFirstIsJustAnotherSpot() = runComposeUiTest {
        val editor = setUpEditor(MaskEditorState(draft = listOf(MaskPoint(0.2, 0.3), MaskPoint(0.7, 0.3))))

        tap(MaskPoint(0.2, 0.3))

        assertEquals(3, editor.value.draft?.size, "two corners can't close, so the tap places a third")
        assertEquals(0, editor.value.layerShapes.size)
    }

    @Test
    fun aTapInsideAShapeSelectsIt() = runComposeUiTest {
        val editor = setUpEditor(withSquare(selected = false))

        tap(MaskPoint(0.5, 0.5))

        assertEquals(0, editor.value.selectedIndex)
        assertNull(editor.value.draft, "a tap on a shape is not the start of a new one")
    }

    @Test
    fun aTapOnEmptyFrameLetsGoOfTheSelectedShape() = runComposeUiTest {
        val editor = setUpEditor(withSquare(selected = true))

        tap(MaskPoint(0.05, 0.05))

        assertNull(editor.value.selectedIndex)
        assertNull(editor.value.draft, "letting go is not the start of a new shape")
    }

    @Test
    fun aTapOnACornerOfTheSelectedShapeSinglesItOut() = runComposeUiTest {
        val editor = setUpEditor(withSquare(selected = true))

        tap(MaskPoint(0.8, 0.8))

        assertEquals(2, editor.value.selectedVertexIndex)
        assertEquals(4, editor.value.layerShapes.single().polygon.points.size)
    }

    @Test
    fun aTapOnAnEdgeDotAddsACornerThere() = runComposeUiTest {
        val editor = setUpEditor(withSquare(selected = true))

        // The dot halfway down the right-hand edge, which runs from corner 1 to corner 2.
        tap(MaskPoint(0.8, 0.5))

        val points = editor.value.layerShapes.single().polygon.points
        assertEquals(5, points.size)
        assertEquals(MaskPoint(0.8, 0.5), points[2], "the corner goes exactly on the dot, not where the finger was")
        assertEquals(2, editor.value.selectedVertexIndex, "the new corner comes up singled out")
    }

    @Test
    fun draggingACornerMovesIt() = runComposeUiTest {
        val editor = setUpEditor(withSquare(selected = true))

        drag(from = MaskPoint(0.8, 0.8), to = MaskPoint(0.9, 0.9))

        val points = editor.value.layerShapes.single().polygon.points
        assertEquals(4, points.size)
        assertNear(MaskPoint(0.9, 0.9), points[2], "the dragged corner should be under the finger")
        assertEquals(MaskPoint(0.2, 0.2), points[0], "the other corners stay put")
    }

    @Test
    fun draggingAnEdgeDotPullsOutANewCorner() = runComposeUiTest {
        val editor = setUpEditor(withSquare(selected = true))

        drag(from = MaskPoint(0.8, 0.5), to = MaskPoint(0.95, 0.5))

        val points = editor.value.layerShapes.single().polygon.points
        assertEquals(5, points.size)
        assertNear(MaskPoint(0.95, 0.5), points[2], "the new corner should have followed the finger")
        assertEquals(MaskPoint(0.8, 0.8), points[3], "the edge's far corner stays put")
    }

    @Test
    fun draggingACornerOfTheShapeBeingDrawnMovesIt() = runComposeUiTest {
        val editor = setUpEditor(MaskEditorState(draft = listOf(MaskPoint(0.2, 0.3), MaskPoint(0.7, 0.3), MaskPoint(0.5, 0.8))))

        drag(from = MaskPoint(0.7, 0.3), to = MaskPoint(0.8, 0.4))

        val draft = assertNotNull(editor.value.draft, "moving a corner doesn't end the drawing")
        assertEquals(3, draft.size)
        assertNear(MaskPoint(0.8, 0.4), draft[1])
    }

    private companion object {
        const val EDITOR_TAG = "mask-editor"

        /** A touch lands on a whole pixel, give or take; a hundredth of the frame is a few of them. */
        const val TOLERANCE = 0.01
    }
}
