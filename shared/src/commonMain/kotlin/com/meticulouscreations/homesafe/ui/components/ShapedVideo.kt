package com.meticulouscreations.homesafe.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import com.meticulouscreations.homesafe.domain.model.VideoFit
import kotlin.math.roundToInt

/**
 * A live player laid out at its video's own shape inside this box, rather than stretched to it.
 *
 * Every player stretches its picture to whatever box it is given, which is right for the Frigate
 * cameras: their cards are 16:9 and so is what they watch, even where a stream is stored
 * anamorphic (the Amcrest's 704x480 sub stream). A Google Nest camera's picture is whatever shape
 * the model makes it: the doorbell's is tall (960x1280), the Hub Max's 4:3. So with a [fit], the
 * player gets a box of the video's shape instead: inside this one with bars ([VideoFit.Fit]), or
 * over it, cropped ([VideoFit.Fill]). A null [fit] is the stretch everything else has.
 *
 * The shape comes from the player's own stream statistics, which every engine reports with the
 * frame's size: [player] is handed a callback for them while the shape for [playerKey] is still
 * unknown, and null once it is known, which stops the measuring. Until then the video stretches.
 * Shapes are remembered for the process ([MeasuredVideoShapes]), so the card, its quick look and
 * the camera's page all agree, and only the first of them ever waits.
 */
@Composable
fun ShapedVideo(
    playerKey: String,
    fit: VideoFit?,
    modifier: Modifier = Modifier,
    player: @Composable (modifier: Modifier, onStreamStats: ((LiveStreamStats?) -> Unit)?) -> Unit,
) {
    val aspect = MeasuredVideoShapes[playerKey]
    val measure: ((LiveStreamStats?) -> Unit)? = if (fit != null && aspect == null) {
        { stats -> MeasuredVideoShapes.record(playerKey, stats?.width, stats?.height) }
    } else {
        null
    }
    Box(modifier = modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        val shaped = when {
            fit == null || aspect == null -> Modifier.fillMaxSize()
            fit == VideoFit.Fit -> Modifier.aspectRatio(aspect)
            else -> Modifier.coverAtAspect(aspect)
        }
        player(shaped, measure)
    }
}

/** The video's size where it covers this box at [aspect], centred, overflowing on one axis; the parent clips the overflow. */
private fun Modifier.coverAtAspect(aspect: Float): Modifier = layout { measurable, constraints ->
    val boxWidth = constraints.maxWidth
    val boxHeight = constraints.maxHeight
    val (width, height) = if (boxWidth.toFloat() / boxHeight > aspect) {
        boxWidth to (boxWidth / aspect).roundToInt()
    } else {
        (boxHeight * aspect).roundToInt() to boxHeight
    }
    val placeable = measurable.measure(Constraints.fixed(width, height))
    layout(boxWidth, boxHeight) { placeable.place((boxWidth - width) / 2, (boxHeight - height) / 2) }
}

/**
 * The shapes (width over height) of the streams [ShapedVideo] has measured, by player key, for
 * the life of the process. Snapshot state, so everything showing a stream reshapes as soon as any
 * of them learns it.
 */
object MeasuredVideoShapes {
    private val aspects = mutableStateMapOf<String, Float>()

    operator fun get(playerKey: String): Float? = aspects[playerKey]

    fun record(playerKey: String, width: Int?, height: Int?) {
        if (width == null || height == null || width <= 0 || height <= 0) return
        aspects[playerKey] = width.toFloat() / height
    }
}
