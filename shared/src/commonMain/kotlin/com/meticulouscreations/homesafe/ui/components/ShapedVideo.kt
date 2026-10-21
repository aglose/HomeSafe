package com.meticulouscreations.homesafe.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import com.meticulouscreations.homesafe.domain.model.CameraCrop
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
 * [crop] is how much of the picture shows (see [CameraCrop]), in fractions of the picture's box,
 * so it frames this box and any other alike; read while placing, so a moving crop doesn't
 * recompose. [clip] false lets a cropped picture spill past this box (the framing editor shows
 * what its frame cuts off).
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
    crop: () -> CameraCrop = { CameraCrop.WHOLE },
    clip: Boolean = true,
    player: @Composable (modifier: Modifier, onStreamStats: ((LiveStreamStats?) -> Unit)?) -> Unit,
) {
    val aspect = streamUrl?.let { MeasuredVideoShapes[it] }
    val measure: ((LiveStreamStats?) -> Unit)? = if (fit != null && streamUrl != null && aspect == null) {
        { stats -> MeasuredVideoShapes.record(streamUrl, stats?.width, stats?.height) }
    } else {
        null
    }
    val onItsSide = isOnItsSide(turnDegrees)
    Box(modifier = if (clip) modifier.clipToBounds() else modifier, contentAlignment = Alignment.Center) {
        player(Modifier.pictureBox(aspect.takeIf { fit != null }, fit, onItsSide, crop), measure)
    }
}

/**
 * The size of the picture's box in a [boxWidth] x [boxHeight] box (see [pictureBox]): [aspect] is
 * the picture's own shape (null: the box's), [fit] how it sits (null: stretched), stood
 * [onItsSide] by a quarter turn.
 */
fun pictureBoxSize(boxWidth: Int, boxHeight: Int, aspect: Float?, fit: VideoFit?, onItsSide: Boolean): IntSize {
    val picture = aspect ?: (boxWidth.toFloat() / boxHeight)
    val shown = if (onItsSide) 1f / picture else picture
    val wider = boxWidth.toFloat() / boxHeight > shown
    return when {
        fit == null && !onItsSide -> IntSize(boxWidth, boxHeight)

        // Whole: limited by the box's tighter side. Filling: by its looser one, spilling over the other.
        (fit == VideoFit.Fill) == wider -> IntSize(boxWidth, (boxWidth / shown).roundToInt())

        else -> IntSize((boxHeight * shown).roundToInt(), boxHeight)
    }
}

/**
 * [crop] drawn by zooming [ShapedVideo]'s whole box rather than the picture inside it: the box
 * [ZoomRest.scale] times larger about its middle and shifted by [ZoomRest.shift]. The same view as
 * passing [crop] to [ShapedVideo], but with the rest of the picture still there around it, for a
 * camera's page, where a pinch reaches past the crop to the whole picture.
 *
 * [boxAspect] is the shape of [ShapedVideo]'s box; [aspect], [fit] and [onItsSide] are the
 * picture's, as [ShapedVideo] is given them (its measured shape, how it sits, and whether a
 * quarter turn has stood it on its side).
 */
fun cropAsZoomRest(crop: CameraCrop, boxAspect: Float, aspect: Float?, fit: VideoFit?, onItsSide: Boolean): ZoomRest {
    if (crop.isWhole || boxAspect <= 0f) return ZoomRest.NONE
    // Any box of the right shape: the answer is in fractions of it. Large, so rounding the
    // picture's box to whole pixels moves nothing.
    val boxWidth = NOMINAL_BOX_WIDTH
    val boxHeight = (boxWidth / boxAspect).roundToInt()
    val (width, height) = pictureBoxSize(boxWidth, boxHeight, aspect.takeIf { fit != null }, fit, onItsSide)
    // As pictureBox places it: the crop kept within this box's reach of the picture, then the
    // picture's box scaled about its middle, which is this box's, and slid.
    val shown = crop.clamped(boxWidth.toFloat() / width, boxHeight.toFloat() / height)
    return ZoomRest(
        scale = shown.zoom,
        shift = Offset(
            x = (0.5f - shown.centerX) * shown.zoom * width / boxWidth,
            y = (0.5f - shown.centerY) * shown.zoom * height / boxHeight,
        ),
    )
}

private const val NOMINAL_BOX_WIDTH = 16_000

/**
 * The box the picture is shown in, centred in this one. [aspect] is the picture's own shape
 * (null: the shape of this box, which is what a stretched picture is taken to be); stood
 * [onItsSide] by a quarter turn, it shows at the inverse shape. Whole ([VideoFit.Fit]) it fits
 * inside with bars, filling ([VideoFit.Fill]) it covers this box and is cropped. A null [fit] is
 * the stretch: this whole box, unless the picture is on its side, when it fits like [VideoFit.Fit],
 * as a 16:9 camera turned upright can't fill a 16:9 card without being squashed.
 */
private fun Modifier.pictureBox(aspect: Float?, fit: VideoFit?, onItsSide: Boolean, crop: () -> CameraCrop): Modifier = layout { measurable, constraints ->
    val boxWidth = constraints.maxWidth
    val boxHeight = constraints.maxHeight
    val (width, height) = pictureBoxSize(boxWidth, boxHeight, aspect, fit, onItsSide)
    val placeable = measurable.measure(Constraints.fixed(width, height))
    layout(boxWidth, boxHeight) {
        // The crop, read here so that a finger moving it only places the picture again. Kept
        // within this box's reach of the picture (see CameraCrop's spans): no bar beyond what
        // fitting the picture shows.
        placeable.placeWithLayer((boxWidth - width) / 2, (boxHeight - height) / 2) {
            val shown = crop().clamped(boxWidth.toFloat() / width, boxHeight.toFloat() / height)
            if (!shown.isWhole) {
                scaleX = shown.zoom
                scaleY = shown.zoom
                translationX = (0.5f - shown.centerX) * shown.zoom * width
                translationY = (0.5f - shown.centerY) * shown.zoom * height
            }
        }
    }
}

/**
 * The shapes (width over height) of the streams [ShapedVideo] has measured, for the life of the
 * process. Snapshot state, so everything showing a stream reshapes as soon as any of them learns it.
 *
 * Kept by the stream itself: its go2rtc address and name, the live URL up to its first `&`
 * (`http://<host>:1984/api/stream.m3u8?src=<name>`), so asking for sound doesn't change it, and
 * without a `_sub` at the end: a Nest camera's smaller stream for the cards is the same picture
 * scaled down (see `nestStreams`), so the camera's page knows the shape before its own stream
 * has a frame. A camera of the same name on another server is another stream, and a different
 * shape; moving between the LAN and Tailscale measures again once.
 */
object MeasuredVideoShapes {
    private val aspects = mutableStateMapOf<String, Float>()

    operator fun get(streamUrl: String): Float? = aspects[key(streamUrl)]

    fun record(streamUrl: String, width: Int?, height: Int?) {
        if (width == null || height == null || width <= 0 || height <= 0) return
        aspects[key(streamUrl)] = width.toFloat() / height
    }

    private fun key(streamUrl: String) = streamUrl.substringBefore('&').removeSuffix("_sub")
}
