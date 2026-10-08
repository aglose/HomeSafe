package com.meticulouscreations.homesafe.weather.ui.radar

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.resolve
import com.meticulouscreations.homesafe.weather.RadarLoad
import com.meticulouscreations.homesafe.weather.data.MapTileSource
import com.meticulouscreations.homesafe.weather.domain.MapCamera
import com.meticulouscreations.homesafe.weather.domain.Place
import com.meticulouscreations.homesafe.weather.domain.RadarSource
import com.meticulouscreations.homesafe.weather.domain.RadarTimeline
import com.meticulouscreations.homesafe.weather.domain.WeatherFormat
import com.meticulouscreations.homesafe.weather.ui.WeatherCardShape
import com.meticulouscreations.homesafe.weather.ui.WeatherTheme
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.weather_radar_ago
import homesafe.shared.generated.resources.weather_radar_ahead
import homesafe.shared.generated.resources.weather_radar_credit_map
import homesafe.shared.generated.resources.weather_radar_credit_rainviewer
import homesafe.shared.generated.resources.weather_radar_credit_us
import homesafe.shared.generated.resources.weather_radar_forecast
import homesafe.shared.generated.resources.weather_radar_legend_extreme
import homesafe.shared.generated.resources.weather_radar_legend_heavy
import homesafe.shared.generated.resources.weather_radar_legend_light
import homesafe.shared.generated.resources.weather_radar_legend_moderate
import homesafe.shared.generated.resources.weather_radar_loading
import homesafe.shared.generated.resources.weather_radar_map_description
import homesafe.shared.generated.resources.weather_radar_now
import homesafe.shared.generated.resources.weather_radar_pause
import homesafe.shared.generated.resources.weather_radar_play
import homesafe.shared.generated.resources.weather_radar_recenter
import homesafe.shared.generated.resources.weather_radar_scrubber
import homesafe.shared.generated.resources.weather_radar_zoom_in
import homesafe.shared.generated.resources.weather_radar_zoom_out
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/** Frames a second while the loop plays: two hours of weather in about five seconds. */
private const val FRAMES_PER_SECOND = 3.4f

/** How long the loop rests on its last frame before starting over, so the eye knows where "latest" is. */
private const val HOLD_SECONDS = 1.3f

/** Where the map opens: a region a few hundred kilometres across, enough to see what's coming. */
internal const val RADAR_OPENING_ZOOM = 7.0

/**
 * The radar tab: a map the full height of the screen with the loop on it, and a pane of
 * controls at the foot — play, the time of the frame in view and how long ago (or ahead) it is,
 * a scrubber across the whole loop with "now" marked on it, and what the colours mean.
 *
 * It opens still, on the newest frame the radars have seen. [headline] is the forecast's own
 * line about rain arriving or leaving, when it has one.
 */
@Composable
internal fun RadarScreen(
    place: Place?,
    radar: RadarLoad,
    tiles: MapTileSource,
    utcOffsetSeconds: Int,
    nowEpochSeconds: Long,
    headline: UiText?,
    padding: PaddingValues,
    modifier: Modifier = Modifier,
    animated: Boolean = true,
) {
    val timeline = radar.timeline
    val map = remember(place?.id) {
        RadarMapState(MapCamera.at(place?.latitude ?: 39.5, place?.longitude ?: -98.35, if (place == null) 4.0 else RADAR_OPENING_ZOOM))
    }
    val position = remember(place?.id) { mutableFloatStateOf(0f) }
    var playing by remember(place?.id) { mutableStateOf(false) }
    var touched by remember(place?.id) { mutableStateOf(false) }
    val lastIndex = (timeline?.frames?.lastIndex ?: 0).coerceAtLeast(0)

    // A new loop arrives every few minutes: stay on "latest" unless the reader has gone elsewhere.
    LaunchedEffect(timeline) {
        if (timeline != null && (!touched || position.floatValue > lastIndex)) position.floatValue = timeline.latestObserved.toFloat()
    }
    LaunchedEffect(playing, timeline) {
        if (!playing || timeline == null || lastIndex == 0) return@LaunchedEffect
        var last = withInfiniteAnimationFrameNanos { it }
        var held = 0f
        while (true) {
            withInfiniteAnimationFrameNanos { now ->
                val dt = ((now - last) / 1e9f).coerceIn(0f, 0.1f)
                last = now
                if (position.floatValue >= lastIndex) {
                    held += dt
                    if (held >= HOLD_SECONDS) {
                        held = 0f
                        position.floatValue = 0f
                    }
                } else {
                    position.floatValue = (position.floatValue + dt * FRAMES_PER_SECOND).coerceAtMost(lastIndex.toFloat())
                }
            }
        }
    }

    val colors = WeatherTheme.colors
    val direction = LocalLayoutDirection.current
    val frameIndex by remember(timeline) { derivedStateOf { position.floatValue.roundToInt().coerceIn(0, lastIndex) } }
    val frame = timeline?.frames?.getOrNull(frameIndex)
    val timeLabel = frame?.let { WeatherFormat.clock(it.epochSeconds, utcOffsetSeconds).resolve() }
    val mapDescription = stringResource(Res.string.weather_radar_map_description, place?.name.orEmpty(), timeLabel.orEmpty())

    Box(modifier.fillMaxSize().testTag("weather_radar")) {
        RadarMap(
            state = map,
            timeline = timeline,
            position = { position.floatValue },
            tiles = tiles,
            modifier = Modifier.fillMaxSize().semantics { contentDescription = mapDescription },
            markerLatitude = place?.latitude,
            markerLongitude = place?.longitude,
            animated = animated,
        )
        // The top bar and the controls sit on the map itself: shade it where they are.
        Box(Modifier.fillMaxWidth().height(padding.calculateTopPadding() + 36.dp).background(Brush.verticalGradient(listOf(Color(0xE605080E), Color.Transparent))))

        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = padding.calculateEndPadding(direction) + 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MapButton(Icons.Filled.MyLocation, stringResource(Res.string.weather_radar_recenter)) {
                if (place != null) map.camera = MapCamera.at(place.latitude, place.longitude, RADAR_OPENING_ZOOM)
            }
            MapButton(Icons.Filled.Add, stringResource(Res.string.weather_radar_zoom_in)) { map.camera = map.camera.zoomed(2f, 0f, 0f, 256f) }
            MapButton(Icons.Filled.Remove, stringResource(Res.string.weather_radar_zoom_out)) { map.camera = map.camera.zoomed(0.5f, 0f, 0f, 256f) }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(start = padding.calculateStartPadding(direction) + 12.dp, end = padding.calculateEndPadding(direction) + 12.dp, bottom = padding.calculateBottomPadding())
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .clip(WeatherCardShape)
                .background(colors.cardSolid)
                .border(1.dp, colors.cardBorder, WeatherCardShape)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            if (headline != null) {
                Text(headline.resolve(), style = WeatherTheme.type.bodyStrong, color = colors.onSky)
                Spacer(Modifier.height(8.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val playLabel = stringResource(if (playing) Res.string.weather_radar_pause else Res.string.weather_radar_play)
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(colors.accent)
                        .clickable(enabled = timeline != null, role = Role.Button, onClickLabel = playLabel) {
                            touched = true
                            // From the end, play starts the loop over rather than sitting on the hold.
                            if (!playing && position.floatValue >= lastIndex) position.floatValue = 0f
                            playing = !playing
                        }
                        .testTag("weather_radar_play"),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = playLabel, tint = Color(0xFF06121F))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    when {
                        frame != null -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(timeLabel.orEmpty(), style = WeatherTheme.type.bodyStrong, color = colors.onSky)
                                if (frame.forecast) {
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        stringResource(Res.string.weather_radar_forecast).uppercase(),
                                        style = WeatherTheme.type.micro,
                                        color = Color(0xFF06121F),
                                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(colors.watch).padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                            Text(relativeTime(frame.epochSeconds, nowEpochSeconds), style = WeatherTheme.type.label, color = colors.onSkyMuted)
                        }

                        radar.error != null -> Text(radar.error.resolve(), style = WeatherTheme.type.label, color = colors.watch)

                        else -> Text(stringResource(Res.string.weather_radar_loading), style = WeatherTheme.type.label, color = colors.onSkyMuted)
                    }
                }
            }
            if (timeline != null && lastIndex > 0) {
                Spacer(Modifier.height(6.dp))
                RadarScrubber(
                    timeline = timeline,
                    position = { position.floatValue },
                    onScrub = { to ->
                        touched = true
                        playing = false
                        position.floatValue = to
                    },
                    description = stringResource(Res.string.weather_radar_scrubber),
                    stateDescription = timeLabel.orEmpty(),
                )
            }
            Spacer(Modifier.height(8.dp))
            RadarLegend()
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(
                    if (timeline?.source == RadarSource.RAINVIEWER) Res.string.weather_radar_credit_rainviewer else Res.string.weather_radar_credit_us,
                    stringResource(Res.string.weather_radar_credit_map),
                ),
                style = WeatherTheme.type.micro.copy(letterSpacing = WeatherTheme.type.label.letterSpacing),
                color = colors.onSkyFaint,
            )
        }
    }
}

@Composable
private fun relativeTime(epochSeconds: Long, nowEpochSeconds: Long): String {
    val minutes = ((epochSeconds - nowEpochSeconds) / 60.0).roundToInt()
    return when {
        minutes in -3..3 -> stringResource(Res.string.weather_radar_now)
        minutes < 0 -> stringResource(Res.string.weather_radar_ago, -minutes)
        else -> stringResource(Res.string.weather_radar_ahead, minutes)
    }
}

@Composable
private fun MapButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    val colors = WeatherTheme.colors
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(colors.cardSolid)
            .border(1.dp, colors.cardBorder, CircleShape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = colors.onSky, modifier = Modifier.size(20.dp))
    }
}

/**
 * The loop as a track: a tick for every frame, the frames the radars saw drawn solid and the
 * forecast ones dashed, a taller mark at the newest observation ("now"), and a thumb where the
 * loop is. Tap or drag anywhere on it; it clicks under the finger at each frame.
 */
@Composable
private fun RadarScrubber(
    timeline: RadarTimeline,
    position: () -> Float,
    onScrub: (Float) -> Unit,
    description: String,
    stateDescription: String,
    modifier: Modifier = Modifier,
) {
    val colors = WeatherTheme.colors
    val haptics = LocalHapticFeedback.current
    val last = timeline.frames.lastIndex.coerceAtLeast(1)
    val now = timeline.latestObserved
    val scrub by rememberUpdatedState(onScrub)
    var lastTick by remember { mutableStateOf(-1) }
    fun moveTo(x: Float, width: Float, inset: Float, settle: Boolean) {
        val at = ((x - inset) / (width - 2 * inset).coerceAtLeast(1f) * last).coerceIn(0f, last.toFloat())
        val frame = at.roundToInt()
        if (frame != lastTick) {
            lastTick = frame
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        }
        scrub(if (settle) frame.toFloat() else at)
    }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .testTag("weather_radar_scrubber")
            .semantics {
                contentDescription = description
                this.stateDescription = stateDescription
                progressBarRangeInfo = ProgressBarRangeInfo(position(), 0f..last.toFloat(), (last - 1).coerceAtLeast(0))
                setProgress { to ->
                    scrub(to.roundToInt().coerceIn(0, last).toFloat())
                    true
                }
            }
            .pointerInput(last) {
                val inset = 10.dp.toPx()
                detectTapGestures { at -> moveTo(at.x, size.width.toFloat(), inset, settle = true) }
            }
            .pointerInput(last) {
                val inset = 10.dp.toPx()
                var x = 0f
                detectHorizontalDragGestures(
                    onDragStart = { at ->
                        x = at.x
                        moveTo(x, size.width.toFloat(), inset, settle = false)
                    },
                    onDragEnd = { moveTo(x, size.width.toFloat(), inset, settle = true) },
                    onDragCancel = { moveTo(x, size.width.toFloat(), inset, settle = true) },
                ) { change, amount ->
                    change.consume()
                    x += amount
                    moveTo(x, size.width.toFloat(), inset, settle = false)
                }
            },
    ) {
        val inset = 10.dp.toPx()
        val y = size.height * 0.56f
        val span = size.width - 2 * inset
        fun xOf(index: Float) = inset + span * index / last
        val stroke = 3.dp.toPx()
        drawLine(colors.onSkyFaint, Offset(xOf(0f), y), Offset(xOf(now.toFloat()), y), stroke, StrokeCap.Round)
        if (now < last) {
            drawLine(colors.watch.copy(alpha = 0.75f), Offset(xOf(now.toFloat()), y), Offset(xOf(last.toFloat()), y), stroke, StrokeCap.Round, PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 6.dp.toPx())))
        }
        val at = position().coerceIn(0f, last.toFloat())
        drawLine(colors.accent, Offset(xOf(0f), y), Offset(xOf(minOf(at, now.toFloat())), y), stroke, StrokeCap.Round)
        for (i in 0..last) {
            val tall = i == now
            drawLine(
                if (tall) colors.onSky else colors.onSkyFaint,
                Offset(xOf(i.toFloat()), y - (if (tall) 13 else 6).dp.toPx()),
                Offset(xOf(i.toFloat()), y - 3.dp.toPx()),
                (if (tall) 2f else 1f).dp.toPx(),
            )
        }
        drawCircle(Color(0x55000000), 10.dp.toPx(), Offset(xOf(at), y))
        drawCircle(colors.onSky, 8.dp.toPx(), Offset(xOf(at), y))
        drawCircle(if (at > now) colors.watch else colors.accent, 5.dp.toPx(), Offset(xOf(at), y))
    }
}

/** The colours the radar shader draws rain in, lightest to heaviest: the same stops, for the legend. */
internal val RadarRamp = listOf(
    Color(0xFF9ECCF5),
    Color(0xFF428FF5),
    Color(0xFF1AC2A8),
    Color(0xFFFAE64D),
    Color(0xFFFF8F24),
    Color(0xFFF02938),
    Color(0xFFDB4DF5),
    Color(0xFFFFF0FF),
)

/** What the colours mean, in words as well as colour: light to extreme. */
@Composable
private fun RadarLegend(modifier: Modifier = Modifier) {
    val colors = WeatherTheme.colors
    Column(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(6.dp)) {
            drawRoundRect(Brush.horizontalGradient(RadarRamp), size = Size(size.width, size.height), cornerRadius = CornerRadius(size.height / 2))
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(
                Res.string.weather_radar_legend_light,
                Res.string.weather_radar_legend_moderate,
                Res.string.weather_radar_legend_heavy,
                Res.string.weather_radar_legend_extreme,
            ).forEach { label ->
                Text(stringResource(label), style = WeatherTheme.type.micro.copy(letterSpacing = WeatherTheme.type.label.letterSpacing), color = colors.onSkyMuted)
            }
        }
    }
}
