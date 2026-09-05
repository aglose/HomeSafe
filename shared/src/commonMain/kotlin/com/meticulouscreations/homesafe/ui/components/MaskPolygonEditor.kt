package com.meticulouscreations.homesafe.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.domain.model.MaskPoint
import com.meticulouscreations.homesafe.domain.model.MaskPolygon

/** One polygon to draw: its shape, its colour, and (for zones) the name to write in the middle. */
data class EditorPolygon(val polygon: MaskPolygon, val color: Color, val label: String? = null)

/**
 * The transparent drawing layer of the detection-zones editor, sized to sit exactly over the
 * camera frame beneath it (so a pixel maps to a relative coordinate by plain division). Draws
 * the layer's polygons, the selected one with draggable corner handles, and the polygon being
 * drawn as an open polyline whose first corner is the "tap here to close" target.
 *
 * Gestures are translated into editor intents; the rules for what a tap means live in
 * [com.meticulouscreations.homesafe.viewmodel.MaskEditorState].
 */
@Composable
fun MaskPolygonEditor(
    polygons: List<EditorPolygon>,
    selectedIndex: Int?,
    draft: List<MaskPoint>?,
    draftColor: Color,
    onTap: (MaskPoint) -> Unit,
    onFinishDraft: () -> Unit,
    onMoveVertex: (polygonIndex: Int, vertexIndex: Int, to: MaskPoint) -> Unit,
    onMoveDraftVertex: (vertexIndex: Int, to: MaskPoint) -> Unit,
    modifier: Modifier = Modifier,
    labelStyle: TextStyle = TextStyle.Default,
) {
    val currentPolygons by rememberUpdatedState(polygons)
    val currentSelected by rememberUpdatedState(selectedIndex)
    val currentDraft by rememberUpdatedState(draft)
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnFinishDraft by rememberUpdatedState(onFinishDraft)
    val currentOnMoveVertex by rememberUpdatedState(onMoveVertex)
    val currentOnMoveDraftVertex by rememberUpdatedState(onMoveDraftVertex)
    val textMeasurer = rememberTextMeasurer()

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                val closeRadius = CLOSE_RADIUS.toPx()
                detectTapGestures { offset ->
                    val d = currentDraft
                    val first = d?.firstOrNull()
                    if (first != null && d.size >= MaskPolygon.MIN_POINTS && (first.toOffset(size) - offset).getDistance() <= closeRadius) {
                        currentOnFinishDraft()
                    } else {
                        currentOnTap(offset.toMaskPoint(size))
                    }
                }
            }
            .pointerInput(Unit) {
                val grabRadius = GRAB_RADIUS.toPx()
                var target: DragTarget? = null
                detectDragGestures(
                    onDragStart = { offset ->
                        target = findDragTarget(offset, size, grabRadius, currentDraft, currentPolygons.map { it.polygon }, currentSelected)
                    },
                    onDragEnd = { target = null },
                    onDragCancel = { target = null },
                ) { change, _ ->
                    val t = target ?: return@detectDragGestures
                    change.consume()
                    val point = change.position.toMaskPoint(size)
                    when (t) {
                        is DragTarget.DraftVertex -> currentOnMoveDraftVertex(t.vertexIndex, point)
                        is DragTarget.PolygonVertex -> currentOnMoveVertex(t.polygonIndex, t.vertexIndex, point)
                    }
                }
            },
    ) {
        val canvasSize = IntSize(size.width.toInt(), size.height.toInt())
        // Fills and outlines first, then labels, then handles: a corner handle must never sit
        // under another polygon's fill or its own outline, or it stops reading as grabbable.
        polygons.forEachIndexed { index, item ->
            val selected = index == selectedIndex
            drawPolygon(item.polygon.points, canvasSize, item.color, fillAlpha = if (selected) 0.45f else 0.3f, strokeWidth = if (selected) 3.dp.toPx() else 2.dp.toPx())
        }
        polygons.forEach { item ->
            val label = item.label ?: return@forEach
            val layout = textMeasurer.measure(label, labelStyle)
            val center = item.polygon.centroid.toOffset(canvasSize)
            val padX = 6.dp.toPx()
            val padY = 3.dp.toPx()
            val topLeft = Offset(
                (center.x - layout.size.width / 2f).coerceIn(padX, (size.width - layout.size.width - padX).coerceAtLeast(padX)),
                (center.y - layout.size.height / 2f).coerceIn(padY, (size.height - layout.size.height - padY).coerceAtLeast(padY)),
            )
            drawRoundRect(
                color = Color.Black.copy(alpha = 0.55f),
                topLeft = Offset(topLeft.x - padX, topLeft.y - padY),
                size = Size(layout.size.width + padX * 2, layout.size.height + padY * 2),
                cornerRadius = CornerRadius(6.dp.toPx()),
            )
            drawText(layout, color = Color.White, topLeft = topLeft)
        }
        selectedIndex?.let { polygons.getOrNull(it) }?.let { item ->
            item.polygon.points.forEach { drawHandle(it.toOffset(canvasSize), item.color, HANDLE_RADIUS.toPx()) }
        }
        draft?.let { points ->
            if (points.isEmpty()) return@let
            val path = Path().apply {
                val start = points.first().toOffset(canvasSize)
                moveTo(start.x, start.y)
                points.drop(1).forEach { p -> val o = p.toOffset(canvasSize); lineTo(o.x, o.y) }
            }
            drawPath(path, color = draftColor, style = Stroke(width = 2.dp.toPx()))
            if (points.size >= 2) {
                drawLine(
                    color = draftColor.copy(alpha = 0.6f),
                    start = points.last().toOffset(canvasSize),
                    end = points.first().toOffset(canvasSize),
                    strokeWidth = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx())),
                )
            }
            points.drop(1).forEach { drawHandle(it.toOffset(canvasSize), draftColor, HANDLE_RADIUS.toPx()) }
            // The first corner is the close target, drawn larger once the shape can close.
            val closable = points.size >= MaskPolygon.MIN_POINTS
            drawHandle(points.first().toOffset(canvasSize), draftColor, if (closable) CLOSE_RADIUS.toPx() * 0.6f else HANDLE_RADIUS.toPx())
        }
    }
}

private sealed interface DragTarget {
    data class DraftVertex(val vertexIndex: Int) : DragTarget
    data class PolygonVertex(val polygonIndex: Int, val vertexIndex: Int) : DragTarget
}

/**
 * Which corner, if any, a drag starting at [offset] grabs: a draft corner while drawing, else a
 * corner of the selected polygon, else the nearest corner of any polygon (which selects it).
 */
private fun findDragTarget(
    offset: Offset,
    size: IntSize,
    grabRadius: Float,
    draft: List<MaskPoint>?,
    polygons: List<MaskPolygon>,
    selectedIndex: Int?,
): DragTarget? {
    fun nearest(points: List<MaskPoint>): Int? = points
        .withIndex()
        .map { (i, p) -> i to (p.toOffset(size) - offset).getDistance() }
        .filter { it.second <= grabRadius }
        .minByOrNull { it.second }
        ?.first

    if (draft != null) return nearest(draft)?.let { DragTarget.DraftVertex(it) }
    selectedIndex?.let { s -> polygons.getOrNull(s)?.let { poly -> nearest(poly.points)?.let { return DragTarget.PolygonVertex(s, it) } } }
    polygons.forEachIndexed { index, polygon ->
        nearest(polygon.points)?.let { return DragTarget.PolygonVertex(index, it) }
    }
    return null
}

private fun DrawScope.drawPolygon(points: List<MaskPoint>, size: IntSize, color: Color, fillAlpha: Float, strokeWidth: Float) {
    if (points.isEmpty()) return
    val path = Path().apply {
        val start = points.first().toOffset(size)
        moveTo(start.x, start.y)
        points.drop(1).forEach { p -> val o = p.toOffset(size); lineTo(o.x, o.y) }
        close()
    }
    drawPath(path, color = color.copy(alpha = fillAlpha))
    drawPath(path, color = color, style = Stroke(width = strokeWidth))
}

private fun DrawScope.drawHandle(center: Offset, color: Color, radius: Float) {
    // A dark halo separates the handle from whatever is under it — a bright frame, the
    // polygon's own outline, or the frame's border when the corner sits right on the edge.
    drawCircle(color = Color.Black.copy(alpha = 0.35f), radius = radius + 2.dp.toPx(), center = center)
    drawCircle(color = Color.White, radius = radius, center = center)
    drawCircle(color = color, radius = radius, center = center, style = Stroke(width = 2.dp.toPx()))
}

private fun MaskPoint.toOffset(size: IntSize): Offset = Offset((x * size.width).toFloat(), (y * size.height).toFloat())

private fun Offset.toMaskPoint(size: IntSize): MaskPoint =
    MaskPoint(x = (x / size.width.coerceAtLeast(1)).toDouble(), y = (y / size.height.coerceAtLeast(1)).toDouble()).clamped()

private val HANDLE_RADIUS = 7.dp
private val GRAB_RADIUS = 24.dp
private val CLOSE_RADIUS = 28.dp
