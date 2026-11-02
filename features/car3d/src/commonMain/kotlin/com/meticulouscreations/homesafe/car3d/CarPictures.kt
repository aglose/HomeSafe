package com.meticulouscreations.homesafe.car3d

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import homesafe.shared.generated.resources.Res
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap
import kotlin.math.floor
import kotlin.math.min

private val CarModel.fileKey: String get() = if (this == CarModel.BLUE_PERFORMANCE) "blue" else "red"

private val loadedPictures = HashMap<String, ImageBitmap>()

/** Pictures stay decoded once seen: a list of cars on a map asks for them on every pass. */
private suspend fun picture(name: String): ImageBitmap {
    loadedPictures[name]?.let { return it }
    return withContext(Dispatchers.IO) { Res.readBytes("files/car/$name").decodeToImageBitmap() }.also { loadedPictures[name] = it }
}

/** Null until it has loaded. A preview gets one frame, so there it is read on the spot. */
@Composable
private fun rememberPicture(name: String): ImageBitmap? {
    val previewed = if (LocalInspectionMode.current) remember(name) { runBlocking { picture(name) } } else null
    val loaded by produceState(previewed, name) { value = picture(name) }
    return loaded
}

/** The car from the viewer's opening angle, rendered ahead of time. */
@Composable
fun CarPoster(model: CarModel, modifier: Modifier = Modifier) {
    val poster = rememberPicture("poster_${model.fileKey}.webp")
    Box(modifier) {
        if (poster != null) {
            Image(poster, contentDescription = null, Modifier.matchParentSize(), contentScale = ContentScale.Fit, filterQuality = FilterQuality.High)
        }
    }
}

/**
 * The car as seen from above on a north-up map, turned to [headingDegrees] (clockwise from north),
 * from a sheet of frames drawn ahead of time by the same renderer: a map with a handful of cars
 * on it has no business holding GL contexts. Null when the car hasn't said: nose to the north.
 */
@Composable
fun CarMapMarker(model: CarModel, headingDegrees: Float?, modifier: Modifier = Modifier) {
    val sheet = rememberPicture("map_${model.fileKey}.webp")
    Canvas(modifier) {
        val image = sheet ?: return@Canvas
        drawMapFrame(image, headingDegrees ?: 0f)
    }
}

private fun DrawScope.drawMapFrame(image: ImageBitmap, headingDegrees: Float) {
    val cell = image.width / MAP_COLUMNS
    val turn = ((headingDegrees % 360f) + 360f) % 360f
    val frame = floor(turn / 360f * MAP_FRAMES + 0.5f).toInt() % MAP_FRAMES
    val side = min(size.width, size.height)
    val offset = IntOffset(((size.width - side) / 2).toInt(), ((size.height - side) / 2).toInt())
    drawImage(
        image,
        srcOffset = IntOffset(frame % MAP_COLUMNS * cell, frame / MAP_COLUMNS * cell),
        srcSize = IntSize(cell, cell),
        dstOffset = offset,
        dstSize = Size(side, side).let { IntSize(it.width.toInt(), it.height.toInt()) },
        filterQuality = FilterQuality.High,
    )
}

/** As tools/car-model/pack_pictures.py lays the sheet out. */
private const val MAP_FRAMES = 72
private const val MAP_COLUMNS = 9
