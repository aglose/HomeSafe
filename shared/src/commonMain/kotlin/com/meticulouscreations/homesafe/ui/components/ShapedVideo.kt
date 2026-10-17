package com.meticulouscreations.homesafe.ui.components

import androidx.compose.foundation.layout.Box
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
 * frame's size: [player] is handed a callback for them while the shape of [streamUrl] is still
 * unknown, and null once it is known, which stops the measuring. Until then the video stretches.
 * Shapes are remembered for the process ([MeasuredVideoShapes]), so the card, its quick look and
 * the camera's page all agree, and only the first of them ever waits. A null [streamUrl] measures
 * nothing: a picture that is only ever stretched (and maybe turned) needs no shape.
 *
 * [turnDegrees] is the turn the player draws the picture at (pass the same to it). A quarter turn
 * stands the picture on its side, so its box takes the inverse shape: a 16:9 camera turned
 * upright fits its card as 9:16, with bars, even a Frigate one (see [pictureBox]).
 */
@Composable
fun ShapedVideo(
    streamUrl: String?,
    fit: VideoFit?,
    modifier: Modifier = Modifier,
    turnDegrees: Float = 0f,
    player: @Composable (modifier: Modifier, onStreamStats: ((LiveStreamStats?) -> Unit)?) -> Unit,
) {
    val aspect = streamUrl?.let { MeasuredVideoShapes[it] }
    val measure: ((LiveStreamStats?) -> Unit)? = if (fit != null && streamUrl != null && aspect == null) {
        { stats -> MeasuredVideoShapes.record(streamUrl, stats?.width, stats?.height) }
    } else {
        null
    }
    val onItsSide = (turnDegrees / 90f).roundToInt() % 2 != 0
    Box(modifier = modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        player(Modifier.pictureBox(aspect.takeIf { fit != null }, fit, onItsSide), measure)
    }
}

/**
 * The box the picture is shown in, centred in this one. [aspect] is the picture's own shape
 * (null: the shape of this box, which is what a stretched picture is taken to be); stood
 * [onItsSide] by a quarter turn, it shows at the inverse shape. Whole ([VideoFit.Fit]) it fits
 * inside with bars, filling ([VideoFit.Fill]) it covers this box and is cropped. A null [fit] is
 * the stretch: this whole box, unless the picture is on its side, when it fits like [VideoFit.Fit],
 * as a 16:9 camera turned upright can't fill a 16:9 card without being squashed.
 */
private fun Modifier.pictureBox(aspect: Float?, fit: VideoFit?, onItsSide: Boolean): Modifier = layout { measurable, constraints ->
    val boxWidth = constraints.maxWidth
    val boxHeight = constraints.maxHeight
    val picture = aspect ?: (boxWidth.toFloat() / boxHeight)
    val shown = if (onItsSide) 1f / picture else picture
    val wider = boxWidth.toFloat() / boxHeight > shown
    val (width, height) = when {
        fit == null && !onItsSide -> boxWidth to boxHeight

        // Whole: limited by the box's tighter side. Filling: by its looser one, spilling over the other.
        (fit == VideoFit.Fill) == wider -> boxWidth to (boxWidth / shown).roundToInt()

        else -> (boxHeight * shown).roundToInt() to boxHeight
    }
    val placeable = measurable.measure(Constraints.fixed(width, height))
    layout(boxWidth, boxHeight) { placeable.place((boxWidth - width) / 2, (boxHeight - height) / 2) }
}

/**
 * The shapes (width over height) of the streams [ShapedVideo] has measured, for the life of the
 * process. Snapshot state, so everything showing a stream reshapes as soon as any of them learns it.
 *
 * Kept by the stream itself: its go2rtc address and name, the live URL up to its first `&`
 * (`http://<host>:1984/api/stream.m3u8?src=<name>`), so asking for sound doesn't change it. A
 * camera of the same name on another server is another stream, and a different shape; moving
 * between the LAN and Tailscale measures again once.
 */
object MeasuredVideoShapes {
    private val aspects = mutableStateMapOf<String, Float>()

    operator fun get(streamUrl: String): Float? = aspects[key(streamUrl)]

    fun record(streamUrl: String, width: Int?, height: Int?) {
        if (width == null || height == null || width <= 0 || height <= 0) return
        aspects[key(streamUrl)] = width.toFloat() / height
    }

    private fun key(streamUrl: String) = streamUrl.substringBefore('&')
}
