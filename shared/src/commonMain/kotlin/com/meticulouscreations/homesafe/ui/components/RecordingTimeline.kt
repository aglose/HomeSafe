package com.meticulouscreations.homesafe.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.domain.model.RecordingSegment
import com.meticulouscreations.homesafe.ui.formatClockTime
import com.meticulouscreations.homesafe.ui.localUtcOffsetSeconds
import com.meticulouscreations.homesafe.ui.preview.FrigatePreview
import com.meticulouscreations.homesafe.ui.preview.previewNowEpochSeconds
import com.meticulouscreations.homesafe.ui.preview.previewRecordingSegments
import com.meticulouscreations.homesafe.viewmodel.TimelineSpan
import kotlin.math.floor

/**
 * A YouTube-live style DVR scrubber: a window of history ending at "now" (the right edge), with
 * recorded coverage drawn as bars whose height follows motion intensity, and a playhead the user
 * can drag or tap. Coverage left of the playhead is tinted as "played".
 */
@Composable
fun RecordingTimeline(
    segments: List<RecordingSegment>,
    span: TimelineSpan,
    nowEpochSeconds: Double,
    playheadEpochSeconds: Double?,
    scrubEpochSeconds: Double?,
    isLive: Boolean,
    onScrubStart: () -> Unit,
    onScrub: (epochSeconds: Double) -> Unit,
    onScrubEnd: () -> Unit,
    onSeek: (epochSeconds: Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val windowStart = nowEpochSeconds - span.seconds
    val frame by rememberUpdatedState(TimelineFrame(windowStart, span.seconds.toDouble()))
    val currentOnScrubStart by rememberUpdatedState(onScrubStart)
    val currentOnScrub by rememberUpdatedState(onScrub)
    val currentOnScrubEnd by rememberUpdatedState(onScrubEnd)
    val currentOnSeek by rememberUpdatedState(onSeek)

    val textMeasurer = rememberTextMeasurer()
    val colorScheme = MaterialTheme.colorScheme
    val labelStyle = MaterialTheme.typography.labelSmall
    val shape = RoundedCornerShape(20.dp)
    val indicatorEpoch = scrubEpochSeconds ?: playheadEpochSeconds ?: nowEpochSeconds
    val ticks = timelineTicks(windowStart, nowEpochSeconds, span.tickSeconds)
    val maxMotion = segments.maxOfOrNull { it.motion }?.coerceAtLeast(1) ?: 1

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(96.dp)
            .clip(shape)
            .background(colorScheme.surface)
            .border(1.dp, colorScheme.outlineVariant.copy(alpha = 0.1f), shape)
            .pointerInput(Unit) {
                detectTapGestures { offset -> currentOnSeek(frame.epochAt(offset.x, size.width)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        currentOnScrubStart()
                        currentOnScrub(frame.epochAt(offset.x, size.width))
                    },
                    onDragEnd = { currentOnScrubEnd() },
                    onDragCancel = { currentOnScrubEnd() },
                ) { change, _ ->
                    change.consume()
                    currentOnScrub(frame.epochAt(change.position.x, size.width))
                }
            },
    ) {
        val width = size.width
        val labelTop = 10.dp.toPx()
        val trackTop = 36.dp.toPx()
        val trackBottom = size.height - 16.dp.toPx()
        fun xFor(epoch: Double): Float = ((epoch - windowStart) / span.seconds * width).toFloat()

        drawLine(
            color = colorScheme.surfaceContainerHigh,
            start = Offset(0f, trackBottom),
            end = Offset(width, trackBottom),
            strokeWidth = 2.dp.toPx(),
        )

        ticks.forEach { tick ->
            val x = xFor(tick)
            drawLine(
                color = colorScheme.outlineVariant.copy(alpha = 0.25f),
                start = Offset(x, trackTop),
                end = Offset(x, trackBottom),
                strokeWidth = 1.dp.toPx(),
            )
            val layout = textMeasurer.measure(formatClockTime(tick), labelStyle)
            val labelX = (x - layout.size.width / 2f).coerceIn(4.dp.toPx(), (width - layout.size.width - 4.dp.toPx()).coerceAtLeast(0f))
            drawText(layout, color = colorScheme.onSurfaceVariant, topLeft = Offset(labelX, labelTop))
        }

        val coverage = CoverageGeometry(windowStart, nowEpochSeconds, span.seconds.toDouble(), trackTop, trackBottom, maxMotion)
        drawCoverage(segments, coverage, base = colorScheme.surfaceContainerHighest, heat = colorScheme.outline, objects = colorScheme.secondary.copy(alpha = 0.5f))

        val indicatorX = xFor(indicatorEpoch).coerceIn(0f, width)
        clipRect(right = indicatorX) {
            drawCoverage(segments, coverage, base = colorScheme.primaryContainer.copy(alpha = 0.55f), heat = colorScheme.primaryContainer, objects = colorScheme.secondary)
        }

        val playheadColor = if (isLive && scrubEpochSeconds == null) colorScheme.error else colorScheme.primary
        drawLine(
            color = playheadColor,
            start = Offset(indicatorX, trackTop - 6.dp.toPx()),
            end = Offset(indicatorX, trackBottom + 6.dp.toPx()),
            strokeWidth = 2.dp.toPx(),
        )
        drawCircle(color = playheadColor, radius = 6.dp.toPx(), center = Offset(indicatorX, trackBottom))

        if (scrubEpochSeconds != null) {
            drawScrubLabel(textMeasurer, formatClockTime(scrubEpochSeconds, withSeconds = true), labelStyle, indicatorX, labelTop, colorScheme.inverseSurface, colorScheme.inverseOnSurface)
        }
    }
}

/** Maps between timeline pixels and wall-clock time; captured by gesture handlers via [rememberUpdatedState]. */
private data class TimelineFrame(val windowStartEpochSeconds: Double, val spanSeconds: Double) {
    fun epochAt(x: Float, width: Int): Double =
        windowStartEpochSeconds + (x / width.coerceAtLeast(1)).coerceIn(0f, 1f) * spanSeconds
}

private class CoverageGeometry(
    val windowStart: Double,
    val windowEnd: Double,
    val spanSeconds: Double,
    val trackTop: Float,
    val trackBottom: Float,
    val maxMotion: Int,
)

private fun DrawScope.drawCoverage(
    segments: List<RecordingSegment>,
    geometry: CoverageGeometry,
    base: Color,
    heat: Color,
    objects: Color,
) {
    val width = size.width
    val trackHeight = geometry.trackBottom - geometry.trackTop
    val baseHeight = trackHeight * 0.22f
    val objectsStripHeight = 3.dp.toPx()
    fun xFor(epoch: Double): Float = ((epoch - geometry.windowStart) / geometry.spanSeconds * width).toFloat()

    segments.forEach { segment ->
        if (segment.endEpochSeconds < geometry.windowStart || segment.startEpochSeconds > geometry.windowEnd) return@forEach
        val x0 = xFor(segment.startEpochSeconds).coerceAtLeast(0f)
        val x1 = xFor(segment.endEpochSeconds).coerceAtMost(width)
        val segmentWidth = (x1 - x0).coerceAtLeast(1f)

        drawRect(base, topLeft = Offset(x0, geometry.trackBottom - baseHeight), size = Size(segmentWidth, baseHeight))

        if (segment.motion > 0) {
            val intensity = (segment.motion / geometry.maxMotion.toFloat()).coerceIn(0.15f, 1f)
            val heatHeight = trackHeight * intensity
            drawRect(
                color = heat.copy(alpha = 0.3f + 0.6f * intensity),
                topLeft = Offset(x0, geometry.trackBottom - heatHeight),
                size = Size(segmentWidth, heatHeight),
            )
        }
        if (segment.objects > 0) {
            drawRect(objects, topLeft = Offset(x0, geometry.trackTop - objectsStripHeight - 2.dp.toPx()), size = Size(segmentWidth, objectsStripHeight))
        }
    }
}

private fun DrawScope.drawScrubLabel(
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    text: String,
    style: TextStyle,
    centerX: Float,
    top: Float,
    background: Color,
    foreground: Color,
) {
    val layout = textMeasurer.measure(text, style)
    val paddingX = 8.dp.toPx()
    val paddingY = 3.dp.toPx()
    val boxWidth = layout.size.width + paddingX * 2
    val boxHeight = layout.size.height + paddingY * 2
    val left = (centerX - boxWidth / 2f).coerceIn(0f, (size.width - boxWidth).coerceAtLeast(0f))
    val boxTop = top - paddingY
    drawRoundRect(background, topLeft = Offset(left, boxTop), size = Size(boxWidth, boxHeight), cornerRadius = CornerRadius(boxHeight / 2f))
    drawText(layout, color = foreground, topLeft = Offset(left + paddingX, boxTop + paddingY))
}

/** Tick times inside the window, aligned to local-time multiples of [intervalSeconds]. */
private fun timelineTicks(windowStart: Double, windowEnd: Double, intervalSeconds: Long): List<Double> {
    val offset = localUtcOffsetSeconds(windowStart)
    var tick = floor((windowStart + offset) / intervalSeconds) * intervalSeconds - offset
    if (tick < windowStart) tick += intervalSeconds
    return buildList {
        while (tick <= windowEnd) {
            add(tick)
            tick += intervalSeconds
        }
    }
}

@Preview
@Composable
private fun RecordingTimelineLivePreview() {
    FrigatePreview {
        RecordingTimeline(
            segments = previewRecordingSegments,
            span = TimelineSpan.THREE_HOURS,
            nowEpochSeconds = previewNowEpochSeconds,
            playheadEpochSeconds = null,
            scrubEpochSeconds = null,
            isLive = true,
            onScrubStart = {},
            onScrub = {},
            onScrubEnd = {},
            onSeek = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview
@Composable
private fun RecordingTimelineScrubbingPreview() {
    FrigatePreview {
        RecordingTimeline(
            segments = previewRecordingSegments,
            span = TimelineSpan.THREE_HOURS,
            nowEpochSeconds = previewNowEpochSeconds,
            playheadEpochSeconds = previewNowEpochSeconds - 5_400,
            scrubEpochSeconds = previewNowEpochSeconds - 5_400,
            isLive = false,
            onScrubStart = {},
            onScrub = {},
            onScrubEnd = {},
            onSeek = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}
