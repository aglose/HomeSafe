package com.meticulouscreations.homesafe.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.meticulouscreations.homesafe.domain.model.ClipRange
import com.meticulouscreations.homesafe.domain.model.ClipWindow
import com.meticulouscreations.homesafe.domain.model.MomentCategory
import com.meticulouscreations.homesafe.ui.formatClockTime
import com.meticulouscreations.homesafe.ui.theme.FrigateExtraColors
import com.meticulouscreations.homesafe.ui.theme.LocalFrigateExtraColors
import com.meticulouscreations.homesafe.viewmodel.ClipMoment
import com.meticulouscreations.homesafe.viewmodel.RecordedSpans
import com.meticulouscreations.homesafe.viewmodel.TrimHandle
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The clip editor's filmstrip, after the one in Google Photos' video editor: frames from the
 * recording laid along the strip, the selection framed in the accent colour with a grip at each
 * end, everything outside it dimmed, and a white playhead riding through it.
 *
 * What a finger does depends on where it lands:
 *  - **On a grip** it takes hold straight away (no slop to cross, so a trim never feels sticky):
 *    playback pauses, the preview shows the frame under the grip, a bubble above says exactly
 *    what time that is, and a tick is felt for every whole second the clip grows or shrinks.
 *    Held against either end of the strip, the strip scrolls under it, faster the deeper the
 *    finger is pushed, so a clip can run well past what's on screen.
 *  - **Inside the selection** it drags the playhead through the clip, frame-previewing as it goes.
 *  - **Outside the selection** a drag scrolls the strip; a tap pulls the nearer grip to that spot.
 *  - **Two fingers** pinch the strip in or out around the point between them (finer or coarser trimming).
 *
 * Hitting a limit — too short, too long, the end of what was recorded — is felt as a bump.
 *
 * Recording gaps are shaded rather than filled with a frame that doesn't exist.
 *
 * [moments] — what was detected in reach — run as coloured spans in a lane under the strip, and a
 * held grip is magnetic to their edges: dragged within a few pixels of where someone walked in or
 * out of frame, it clicks onto that instant with a tick, so a clip can start exactly on the event.
 *
 * None of that is reachable without a finger, so the strip is also one focusable accessibility
 * node that reads out the selection and offers every gesture as an action (see
 * [clipTrimmerSemantics]): each grip a second either way, the playhead a second either way, and
 * the strip zoomed or scrolled. That's what a screen reader, Switch Access or a keyboard uses.
 */
@Composable
fun ClipTrimmer(
    window: ClipWindow,
    range: ClipRange,
    playheadEpochSeconds: Double?,
    recorded: RecordedSpans,
    activeHandle: TrimHandle?,
    isScrubbing: Boolean,
    thumbnailUrl: (epochSeconds: Double) -> String?,
    onTrimStart: (TrimHandle) -> Unit,
    onTrim: (TrimHandle, Double) -> Boolean,
    onTrimEnd: () -> Unit,
    onScrubStart: () -> Unit,
    onScrub: (Double) -> Unit,
    onScrubEnd: () -> Unit,
    onTap: (Double) -> Boolean,
    onPan: (Double) -> Unit,
    onZoom: (factor: Double, focusEpochSeconds: Double) -> Unit,
    modifier: Modifier = Modifier,
    moments: List<ClipMoment> = emptyList(),
) {
    val haptic = LocalHapticFeedback.current
    val accent = MaterialTheme.colorScheme.primary
    val onAccent = MaterialTheme.colorScheme.onPrimary

    // A tick for every whole second the clip changes length while a grip is held.
    val wholeSeconds = range.durationSeconds.toLong()
    LaunchedEffect(wholeSeconds) {
        if (activeHandle != null) haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }

    // The held grip swells a little, like a thing picked up.
    val startGrip by animateFloatAsState(if (activeHandle == TrimHandle.START) 1f else 0f, spring(dampingRatio = 0.6f, stiffness = 600f))
    val endGrip by animateFloatAsState(if (activeHandle == TrimHandle.END) 1f else 0f, spring(dampingRatio = 0.6f, stiffness = 600f))

    // What the bubble points at: the held grip, or the playhead while it's dragged. Remembered
    // past the end of the gesture so the bubble still has something to say while it fades.
    val bubbleEpoch = when (activeHandle) {
        TrimHandle.START -> range.startEpochSeconds
        TrimHandle.END -> range.endEpochSeconds
        null -> if (isScrubbing) playheadEpochSeconds else null
    }
    val bubbleMemory = remember { BubbleMemory(range.startEpochSeconds) }
    if (bubbleEpoch != null) bubbleMemory.epochSeconds = bubbleEpoch
    val lastBubbleEpoch = bubbleMemory.epochSeconds

    Column(modifier = modifier) {
        TimeBubbleLane(
            visible = bubbleEpoch != null,
            fraction = window.fractionOf(lastBubbleEpoch).toFloat().coerceIn(0f, 1f),
            text = formatClockTime(lastBubbleEpoch, withSeconds = true),
        )

        val latestWindow by rememberUpdatedState(window)
        val latestRange by rememberUpdatedState(range)
        val latestOnTrim by rememberUpdatedState(onTrim)
        val latestOnPan by rememberUpdatedState(onPan)
        val latestOnTrimStart by rememberUpdatedState(onTrimStart)
        val latestOnTrimEnd by rememberUpdatedState(onTrimEnd)
        val latestOnScrubStart by rememberUpdatedState(onScrubStart)
        val latestOnScrub by rememberUpdatedState(onScrub)
        val latestOnScrubEnd by rememberUpdatedState(onScrubEnd)
        val latestOnTap by rememberUpdatedState(onTap)
        val latestOnZoom by rememberUpdatedState(onZoom)
        val latestMoments by rememberUpdatedState(moments)

        // Edge auto-scroll: set by the gesture while a grip is held near an end of the strip
        // (-1..1, how deep and which way), and run once a frame for as long as it's non-zero.
        var edgePush by remember { mutableFloatStateOf(0f) }
        var pushHandle by remember { mutableStateOf<TrimHandle?>(null) }
        var pushFraction by remember { mutableFloatStateOf(0f) }
        LaunchedEffect(Unit) {
            snapshotFlow { edgePush != 0f }.collectLatest { pushing ->
                if (!pushing) return@collectLatest
                var last = withFrameNanos { it }
                while (true) {
                    val now = withFrameNanos { it }
                    val dt = (now - last) / 1_000_000_000.0
                    last = now
                    val handle = pushHandle ?: continue
                    latestOnPan(edgePush * latestWindow.durationSeconds * AUTO_SCROLL_WINDOWS_PER_SECOND * dt)
                    latestOnTrim(handle, latestWindow.epochAt(pushFraction.toDouble()))
                }
            }
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(STRIP_HEIGHT)
                .clipTrimmerSemantics(
                    window = window,
                    range = range,
                    playheadEpochSeconds = playheadEpochSeconds,
                    onTrimStart = onTrimStart,
                    onTrim = onTrim,
                    onTrimEnd = onTrimEnd,
                    onScrubStart = onScrubStart,
                    onScrub = onScrub,
                    onScrubEnd = onScrubEnd,
                    onPan = onPan,
                    onZoom = onZoom,
                )
                .focusable()
                .pointerInput(Unit) {
                    val hitSlop = HANDLE_HIT_SLOP.toPx()
                    val edgeZone = EDGE_ZONE.toPx()
                    val snapReach = MOMENT_SNAP_REACH.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val width = size.width.toFloat().coerceAtLeast(1f)
                        fun epochAt(x: Float): Double = latestWindow.epochAt((x / width).toDouble())
                        val startX = (latestWindow.fractionOf(latestRange.startEpochSeconds) * width).toFloat()
                        val endX = (latestWindow.fractionOf(latestRange.endEpochSeconds) * width).toFloat()
                        val x0 = down.position.x
                        val nearStart = abs(x0 - startX) <= hitSlop
                        val nearEnd = abs(x0 - endX) <= hitSlop
                        val handle = when {
                            nearStart && nearEnd -> if (x0 < (startX + endX) / 2) TrimHandle.START else TrimHandle.END
                            nearStart -> TrimHandle.START
                            nearEnd -> TrimHandle.END
                            else -> null
                        }
                        val inside = x0 in startX..endX

                        var dragging = handle != null
                        var zooming = false
                        var limited = false
                        var snappedTo: Double? = null
                        var lastX = x0
                        var previousSpan = 0f
                        if (handle != null) {
                            down.consume()
                            haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                            latestOnTrimStart(handle)
                        }

                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (!dragging && pressed.size >= 2) zooming = true
                            if (zooming) {
                                if (pressed.size >= 2) {
                                    val a = pressed[0].position.x
                                    val b = pressed[1].position.x
                                    val span = abs(a - b).coerceAtLeast(1f)
                                    if (previousSpan > 0f) latestOnZoom((span / previousSpan).toDouble(), epochAt((a + b) / 2))
                                    previousSpan = span
                                } else {
                                    previousSpan = 0f
                                }
                                event.changes.forEach { it.consume() }
                                continue
                            }

                            val change = event.changes.firstOrNull { it.id == down.id } ?: pressed.first()
                            val x = change.position.x
                            if (!dragging && abs(x - x0) > viewConfiguration.touchSlop) {
                                dragging = true
                                if (inside) latestOnScrubStart()
                            }
                            if (!dragging) continue
                            change.consume()
                            when {
                                handle != null -> {
                                    // Magnetic detection edges: within reach of one, the grip
                                    // takes that exact instant (and says so with a tick once).
                                    val snap = nearestMomentEdge(latestMoments, latestWindow, x, width, snapReach)
                                    if (snap != null && snap != snappedTo) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                    snappedTo = snap
                                    val nowLimited = latestOnTrim(handle, snap ?: epochAt(x))
                                    if (nowLimited && !limited) haptic.performHapticFeedback(HapticFeedbackType.Reject)
                                    limited = nowLimited
                                    pushHandle = handle
                                    pushFraction = (x / width).coerceIn(0f, 1f)
                                    edgePush = when {
                                        x < edgeZone -> -(1f - x / edgeZone).coerceIn(0f, 1f)
                                        x > width - edgeZone -> ((x - (width - edgeZone)) / edgeZone).coerceIn(0f, 1f)
                                        else -> 0f
                                    }
                                }

                                inside -> latestOnScrub(epochAt(x))

                                else -> latestOnPan(-((x - lastX) / width) * latestWindow.durationSeconds)
                            }
                            lastX = x
                        }

                        edgePush = 0f
                        pushHandle = null
                        when {
                            handle != null -> {
                                latestOnTrimEnd()
                                haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                            }

                            zooming -> Unit

                            dragging && inside -> latestOnScrubEnd()

                            !dragging -> {
                                val tapLimited = latestOnTap(epochAt(x0))
                                haptic.performHapticFeedback(if (tapLimited) HapticFeedbackType.Reject else HapticFeedbackType.SegmentTick)
                            }
                        }
                    }
                },
        ) {
            val widthPx = constraints.maxWidth.toFloat()
            FilmstripFrames(
                window = window,
                recorded = recorded,
                widthPx = widthPx,
                thumbnailUrl = thumbnailUrl,
            )
            Canvas(modifier = Modifier.matchParentSize()) {
                drawRecordingGaps(window, recorded)
                drawSelection(
                    window = window,
                    range = range,
                    accent = accent,
                    grip = onAccent,
                    startGrip = startGrip,
                    endGrip = endGrip,
                )
                val playhead = playheadEpochSeconds
                if (playhead != null && activeHandle == null && playhead in range) {
                    drawPlayhead(window.fractionOf(playhead).toFloat() * size.width)
                }
            }
        }

        if (moments.isNotEmpty()) {
            val extra = LocalFrigateExtraColors.current
            val other = MaterialTheme.colorScheme.onSurfaceVariant
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = MOMENT_LANE_GAP)
                    .height(MOMENT_LANE_HEIGHT),
            ) {
                drawMomentSpans(window, range, moments) { category -> clipMomentColor(extra, category, other) }
            }
        }
    }
}

/** A detection's colour, the same one the camera page's timeline dots use; [other] for a label outside the three filters. */
internal fun clipMomentColor(extra: FrigateExtraColors, category: MomentCategory, other: Color): Color = when (category) {
    MomentCategory.PEOPLE -> extra.peopleMarker
    MomentCategory.VEHICLES -> extra.vehiclesMarker
    MomentCategory.ANIMALS -> extra.animalsMarker
    MomentCategory.ALL -> other
}

/**
 * The detection edge (a start or an end) nearest [x] on a strip [width] wide, as an instant, if
 * one is within [reach] pixels; null otherwise.
 */
private fun nearestMomentEdge(moments: List<ClipMoment>, window: ClipWindow, x: Float, width: Float, reach: Float): Double? {
    var best: Double? = null
    var bestDistance = reach
    for (moment in moments) {
        for (edge in doubleArrayOf(moment.startEpochSeconds, moment.endEpochSeconds)) {
            val distance = abs((window.fractionOf(edge) * width).toFloat() - x)
            if (distance <= bestDistance) {
                best = edge
                bestDistance = distance
            }
        }
    }
    return best
}

/**
 * Each detection as a rounded span from its start to its end in its category's colour — at full
 * strength inside the selection, faded outside it, the way the strip dims what won't be saved.
 * A detection too short to see still gets a dot.
 */
private fun DrawScope.drawMomentSpans(window: ClipWindow, range: ClipRange, moments: List<ClipMoment>, colorFor: (MomentCategory) -> Color) {
    val minWidth = size.height
    val radius = CornerRadius(size.height / 2)
    for (moment in moments) {
        val left = (window.fractionOf(moment.startEpochSeconds) * size.width).toFloat()
        val right = max((window.fractionOf(moment.endEpochSeconds) * size.width).toFloat(), left + minWidth)
        if (right < 0f || left > size.width) continue
        val overlapsClip = moment.endEpochSeconds >= range.startEpochSeconds && moment.startEpochSeconds <= range.endEpochSeconds
        drawRoundRect(
            color = colorFor(moment.category).copy(alpha = if (overlapsClip) 1f else 0.35f),
            topLeft = Offset(left, 0f),
            size = Size(right - left, size.height),
            cornerRadius = radius,
        )
    }
}

/**
 * The last moment the bubble named. A plain holder rather than state: it only ever changes in the
 * same composition that reads it, so there's nothing to invalidate.
 */
private class BubbleMemory(var epochSeconds: Double)

/**
 * Frames along the strip, one per slot of a fixed wall-clock grid: panning slides the frames it
 * already has rather than asking for new ones, and only the slots scrolling in are fetched. The
 * slot length is a round number of seconds, chosen so each frame is about [FRAME_WIDTH] wide.
 */
@Composable
private fun FilmstripFrames(window: ClipWindow, recorded: RecordedSpans, widthPx: Float, thumbnailUrl: (Double) -> String?) {
    val density = LocalDensity.current
    val frameWidthPx = with(density) { FRAME_WIDTH.toPx() }
    val slotSeconds = filmstripSlotSeconds(window.durationSeconds, widthPx / frameWidthPx)
    val slotWidthPx = (slotSeconds / window.durationSeconds * widthPx).toFloat()
    val slotWidth = with(density) { (slotWidthPx + 1f).toDp() }
    val first = floor(window.startEpochSeconds / slotSeconds).toLong()
    val last = ceil(window.endEpochSeconds / slotSeconds).toLong()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .clip(RoundedCornerShape(STRIP_CORNER))
            .background(FilmstripBackground),
    ) {
        for (slot in first..last) {
            val slotStart = slot * slotSeconds
            // Nothing recorded anywhere in this slot: leave it dark rather than ask for a 404.
            val middle = slotStart + slotSeconds / 2
            val probe = if (recorded.spans.any { middle in it }) middle else recorded.spans.firstOrNull { it.start < slotStart + slotSeconds && it.endInclusive > slotStart }?.start
            key(slot) {
                val x = ((slotStart - window.startEpochSeconds) / window.durationSeconds * widthPx).roundToInt()
                if (probe != null) {
                    AsyncImage(
                        model = thumbnailUrl(probe),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .offset { IntOffset(x, 0) }
                            .requiredWidth(slotWidth)
                            .fillMaxHeight(),
                    )
                }
            }
        }
    }
}

/**
 * The bubble above the strip naming the exact moment under the finger, centred on [fraction] of
 * the strip's width but kept wholly on screen. Fades and pops in and out with the gesture.
 */
@Composable
private fun TimeBubbleLane(visible: Boolean, fraction: Float, text: String) {
    Layout(
        content = {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(BUBBLE_FADE_MS)) + scaleIn(initialScale = 0.8f, animationSpec = tween(BUBBLE_FADE_MS)),
                exit = fadeOut(tween(BUBBLE_FADE_MS)) + scaleOut(targetScale = 0.8f, animationSpec = tween(BUBBLE_FADE_MS)),
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.Black,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.White)
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(BUBBLE_LANE_HEIGHT),
    ) { measurables, constraints ->
        val placeable = measurables.firstOrNull()?.measure(constraints.copy(minWidth = 0, minHeight = 0))
        layout(constraints.maxWidth, constraints.maxHeight) {
            if (placeable != null) {
                val centre = (fraction * constraints.maxWidth).roundToInt()
                val x = (centre - placeable.width / 2).coerceIn(0, max(0, constraints.maxWidth - placeable.width))
                placeable.place(x, (constraints.maxHeight - placeable.height) / 2)
            }
        }
    }
}

/** Shades whatever part of the window has no recording behind it. */
private fun DrawScope.drawRecordingGaps(window: ClipWindow, recorded: RecordedSpans) {
    var cursor = window.startEpochSeconds
    val shade = Color.Black.copy(alpha = 0.55f)
    fun shadeBetween(from: Double, to: Double) {
        if (to <= from) return
        val left = (window.fractionOf(from) * size.width).toFloat().coerceIn(0f, size.width)
        val right = (window.fractionOf(to) * size.width).toFloat().coerceIn(0f, size.width)
        if (right > left) drawRect(shade, topLeft = Offset(left, 0f), size = Size(right - left, size.height))
    }
    recorded.spans.sortedBy { it.start }.forEach { span ->
        if (span.endInclusive <= cursor) return@forEach
        if (span.start > window.endEpochSeconds) return@forEach
        shadeBetween(cursor, span.start)
        cursor = max(cursor, span.endInclusive)
    }
    shadeBetween(cursor, window.endEpochSeconds)
}

/**
 * The selection: a scrim over everything outside it, the accent frame around it, and the two
 * grips — rounded on their outer side, square where they meet the frame — with a pair of grip
 * lines each. [startGrip] / [endGrip] (0..1) swell the grip being held.
 */
private fun DrawScope.drawSelection(
    window: ClipWindow,
    range: ClipRange,
    accent: Color,
    grip: Color,
    startGrip: Float,
    endGrip: Float,
) {
    val w = size.width
    val h = size.height
    val startX = (window.fractionOf(range.startEpochSeconds) * w).toFloat()
    val endX = (window.fractionOf(range.endEpochSeconds) * w).toFloat()
    val scrim = Color.Black.copy(alpha = 0.6f)
    if (startX > 0f) drawRect(scrim, topLeft = Offset.Zero, size = Size(startX.coerceAtMost(w), h))
    if (endX < w) drawRect(scrim, topLeft = Offset(endX.coerceAtLeast(0f), 0f), size = Size(w - endX.coerceAtLeast(0f), h))

    val corner = STRIP_CORNER.toPx()
    val border = FRAME_BORDER.toPx()
    val left = startX.coerceIn(-corner * 4, w + corner * 4)
    val right = endX.coerceIn(-corner * 4, w + corner * 4)
    drawRoundRect(
        color = accent,
        topLeft = Offset(left + border / 2, border / 2),
        size = Size((right - left - border).coerceAtLeast(0f), h - border),
        cornerRadius = CornerRadius(corner),
        style = Stroke(width = border),
    )

    val gripWidth = GRIP_WIDTH.toPx()
    val swell = GRIP_SWELL.toPx()
    val startWidth = gripWidth + swell * startGrip
    val endWidth = gripWidth + swell * endGrip
    val round = CornerRadius(corner)
    drawPath(
        Path().apply {
            addRoundRect(RoundRect(left, 0f, left + startWidth, h, topLeftCornerRadius = round, topRightCornerRadius = CornerRadius.Zero, bottomRightCornerRadius = CornerRadius.Zero, bottomLeftCornerRadius = round))
        },
        color = accent,
    )
    drawPath(
        Path().apply {
            addRoundRect(RoundRect(right - endWidth, 0f, right, h, topLeftCornerRadius = CornerRadius.Zero, topRightCornerRadius = round, bottomRightCornerRadius = round, bottomLeftCornerRadius = CornerRadius.Zero))
        },
        color = accent,
    )
    drawGripLines(centreX = left + startWidth / 2, color = grip)
    drawGripLines(centreX = right - endWidth / 2, color = grip)
}

private fun DrawScope.drawGripLines(centreX: Float, color: Color) {
    val lineHeight = GRIP_LINE_HEIGHT.toPx()
    val stroke = 2.dp.toPx()
    val gap = 2.5.dp.toPx()
    val top = (size.height - lineHeight) / 2
    for (dx in listOf(-gap, gap)) {
        drawLine(color, Offset(centreX + dx, top), Offset(centreX + dx, top + lineHeight), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

/** A white bar a little taller than the strip, on a soft shadow so it reads over any frame. */
private fun DrawScope.drawPlayhead(x: Float) {
    val overhang = PLAYHEAD_OVERHANG.toPx()
    val width = PLAYHEAD_WIDTH.toPx()
    drawRoundRect(
        color = Color.Black.copy(alpha = 0.35f),
        topLeft = Offset(x - width / 2 - 1.dp.toPx(), -overhang - 1.dp.toPx()),
        size = Size(width + 2.dp.toPx(), size.height + overhang * 2 + 2.dp.toPx()),
        cornerRadius = CornerRadius(width),
    )
    drawRoundRect(
        color = Color.White,
        topLeft = Offset(x - width / 2, -overhang),
        size = Size(width, size.height + overhang * 2),
        cornerRadius = CornerRadius(width / 2),
    )
}

/**
 * How many seconds each filmstrip frame stands for: the first round step at least as long as
 * [windowSeconds] split into [framesAcross] frames, so the grid only changes when a pinch
 * changes the scale noticeably, never while panning.
 */
internal fun filmstripSlotSeconds(windowSeconds: Double, framesAcross: Float): Double {
    val raw = windowSeconds / framesAcross.toDouble().coerceAtLeast(1.0)
    return FILMSTRIP_STEPS.firstOrNull { it >= raw } ?: FILMSTRIP_STEPS.last()
}

/**
 * The trimmer as one accessibility node: what is selected, read as clock times and a length,
 * and the gestures as custom actions that run through the same callbacks a finger does, so a
 * nudge from a screen reader is bounded and previewed exactly like a drag.
 */
private fun Modifier.clipTrimmerSemantics(
    window: ClipWindow,
    range: ClipRange,
    playheadEpochSeconds: Double?,
    onTrimStart: (TrimHandle) -> Unit,
    onTrim: (TrimHandle, Double) -> Boolean,
    onTrimEnd: () -> Unit,
    onScrubStart: () -> Unit,
    onScrub: (Double) -> Unit,
    onScrubEnd: () -> Unit,
    onPan: (Double) -> Unit,
    onZoom: (factor: Double, focusEpochSeconds: Double) -> Unit,
): Modifier = semantics {
    val start = formatClockTime(range.startEpochSeconds, withSeconds = true)
    val end = formatClockTime(range.endEpochSeconds, withSeconds = true)
    contentDescription = "Clip trimmer"
    stateDescription = "From $start to $end, ${range.durationSeconds.roundToInt()} seconds"

    fun nudge(handle: TrimHandle, by: Double): Boolean {
        val from = if (handle == TrimHandle.START) range.startEpochSeconds else range.endEpochSeconds
        onTrimStart(handle)
        onTrim(handle, from + by)
        onTrimEnd()
        return true
    }

    fun scrub(by: Double): Boolean {
        val from = playheadEpochSeconds ?: range.startEpochSeconds
        onScrubStart()
        onScrub((from + by).coerceIn(range.startEpochSeconds, range.endEpochSeconds))
        onScrubEnd()
        return true
    }

    val centre = (range.startEpochSeconds + range.endEpochSeconds) / 2
    customActions = listOf(
        CustomAccessibilityAction("Start 1 second earlier") { nudge(TrimHandle.START, -ACCESSIBLE_STEP_SECONDS) },
        CustomAccessibilityAction("Start 1 second later") { nudge(TrimHandle.START, ACCESSIBLE_STEP_SECONDS) },
        CustomAccessibilityAction("End 1 second earlier") { nudge(TrimHandle.END, -ACCESSIBLE_STEP_SECONDS) },
        CustomAccessibilityAction("End 1 second later") { nudge(TrimHandle.END, ACCESSIBLE_STEP_SECONDS) },
        CustomAccessibilityAction("Playhead back 1 second") { scrub(-ACCESSIBLE_STEP_SECONDS) },
        CustomAccessibilityAction("Playhead forward 1 second") { scrub(ACCESSIBLE_STEP_SECONDS) },
        CustomAccessibilityAction("Zoom in") {
            onZoom(ACCESSIBLE_ZOOM_FACTOR, centre)
            true
        },
        CustomAccessibilityAction("Zoom out") {
            onZoom(1 / ACCESSIBLE_ZOOM_FACTOR, centre)
            true
        },
        CustomAccessibilityAction("Scroll earlier") {
            onPan(-window.durationSeconds / 2)
            true
        },
        CustomAccessibilityAction("Scroll later") {
            onPan(window.durationSeconds / 2)
            true
        },
    )
}

/** How far one accessibility action moves a grip or the playhead. */
private const val ACCESSIBLE_STEP_SECONDS = 1.0

/** How much one accessibility zoom action changes the strip's scale (a pinch's worth). */
private const val ACCESSIBLE_ZOOM_FACTOR = 2.0

/** Multiples of the recording snapshot's 2 s quantum, so every frame is a distinct snapshot. */
private val FILMSTRIP_STEPS = listOf(2.0, 4.0, 6.0, 8.0, 10.0, 16.0, 20.0, 30.0, 40.0, 60.0, 90.0, 120.0, 180.0, 240.0, 300.0, 600.0)

private val FilmstripBackground = Color(0xFF1A1A19)
private val STRIP_HEIGHT = 60.dp

/** Where the strip itself sits inside [ClipTrimmer] (under the time bubble's lane), for lining things up beside it. */
internal val ClipTrimmerStripTop: Dp get() = BUBBLE_LANE_HEIGHT
internal val ClipTrimmerStripHeight: Dp get() = STRIP_HEIGHT
private val STRIP_CORNER = 10.dp
private val FRAME_WIDTH = 44.dp
private val FRAME_BORDER = 3.dp
private val GRIP_WIDTH = 14.dp
private val GRIP_SWELL = 4.dp
private val GRIP_LINE_HEIGHT = 18.dp
private val PLAYHEAD_WIDTH = 3.dp
private val PLAYHEAD_OVERHANG = 5.dp
private val HANDLE_HIT_SLOP = 24.dp
private val EDGE_ZONE = 36.dp
private val BUBBLE_LANE_HEIGHT = 36.dp
private val MOMENT_LANE_HEIGHT = 4.dp
private val MOMENT_LANE_GAP = 8.dp

/** How close a held grip must come to a detection's edge before it snaps onto it. */
private val MOMENT_SNAP_REACH = 10.dp
private const val BUBBLE_FADE_MS = 140

/** At full push, the strip scrolls this many of its own widths per second. */
private const val AUTO_SCROLL_WINDOWS_PER_SECOND = 0.6
