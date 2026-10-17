package com.meticulouscreations.homesafe.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The size a picture is drawn at, before it is turned by [degrees] about the centre of a
 * [boxWidth] x [boxHeight] box, so that once turned it covers the box exactly.
 *
 * Whole quarter turns swap the sides: a picture turned 90° into a 16:9 box is drawn 9:16 and
 * stood on its side. Whatever is left over (the fine tilt, within ±44°) is covered by drawing the
 * picture a little larger, just enough that no corner of the box is left empty; what spills over
 * the box's edges is cut off by whoever draws it. Upright, the picture is the box.
 */
fun turnedPictureSize(boxWidth: Float, boxHeight: Float, degrees: Float): Pair<Float, Float> {
    val quarterTurns = quarterTurnsIn(degrees)
    val tilt = abs(degrees - quarterTurns * 90f) * PI.toFloat() / 180f
    val (width, height) = if (quarterTurns % 2 != 0) boxHeight to boxWidth else boxWidth to boxHeight
    if (tilt == 0f || width <= 0f || height <= 0f) return width to height
    val scale = cos(tilt) + max(width / height, height / width) * sin(tilt)
    return width * scale to height * scale
}

/**
 * The whole quarter turns in a turn of [degrees], the rest being the tilt. The nearest: the tilt
 * never reaches 45° (see `CameraOrientation.MAX_TILT_DEGREES`), so there is never a tie.
 */
fun quarterTurnsIn(degrees: Float): Int = (degrees / 90f).roundToInt()

/** Whether a turn of [degrees] stands the picture on its side, so it shows at its inverse shape. */
fun isOnItsSide(degrees: Float): Boolean = quarterTurnsIn(degrees) % 2 != 0

/**
 * Draws this content turned by [degrees] inside the space it is given (see [turnedPictureSize]):
 * laid out at the turned picture's size, centred, and rotated. The parent clips what spills over.
 * Nothing changes while [degrees] is 0.
 *
 * Only for content Compose draws itself, or an Android view inside it (both players there are
 * TextureViews, which turn with their layer). A UIKit view under Compose on iOS ignores Compose's
 * layer transforms, so the iOS player turns its own layers natively, from the same size.
 */
fun Modifier.turned(degrees: Float): Modifier =
    if (degrees == 0f) {
        this
    } else {
        layout { measurable, constraints ->
            val boxWidth = constraints.maxWidth
            val boxHeight = constraints.maxHeight
            val (width, height) = turnedPictureSize(boxWidth.toFloat(), boxHeight.toFloat(), degrees)
            val placeable = measurable.measure(Constraints.fixed(width.roundToInt(), height.roundToInt()))
            layout(boxWidth, boxHeight) { placeable.place((boxWidth - placeable.width) / 2, (boxHeight - placeable.height) / 2) }
        }.graphicsLayer { rotationZ = degrees }
    }
