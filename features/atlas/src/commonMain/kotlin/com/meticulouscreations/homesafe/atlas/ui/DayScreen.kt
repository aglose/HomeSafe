package com.meticulouscreations.homesafe.atlas.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.atlas.AtlasUiState
import com.meticulouscreations.homesafe.atlas.domain.AtlasAnalyzer
import com.meticulouscreations.homesafe.atlas.domain.AtlasHistory
import com.meticulouscreations.homesafe.atlas.domain.Move
import com.meticulouscreations.homesafe.atlas.domain.SECONDS_PER_DAY
import com.meticulouscreations.homesafe.atlas.domain.Visit
import com.meticulouscreations.homesafe.atlas.domain.VisitRole
import com.meticulouscreations.homesafe.atlas.domain.localMinute
import com.meticulouscreations.homesafe.weather.data.MapTileSource
import com.meticulouscreations.homesafe.weather.domain.WebMercator
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.atlas_day_jump_earlier
import homesafe.shared.generated.resources.atlas_day_jump_later
import homesafe.shared.generated.resources.atlas_day_move_detail
import homesafe.shared.generated.resources.atlas_day_next
import homesafe.shared.generated.resources.atlas_day_nothing
import homesafe.shared.generated.resources.atlas_day_pause
import homesafe.shared.generated.resources.atlas_day_play
import homesafe.shared.generated.resources.atlas_day_previous
import homesafe.shared.generated.resources.atlas_day_ribbon
import homesafe.shared.generated.resources.atlas_day_ribbon_whole
import homesafe.shared.generated.resources.atlas_day_span
import homesafe.shared.generated.resources.atlas_day_summary
import homesafe.shared.generated.resources.atlas_day_visit_detail
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

private const val REPLAY_MS = 14_000

/** One thing that happened in a day, cut to the day's own midnight-to-midnight: a stay or a stretch of travel. */
@Immutable
internal sealed interface DayEvent {
    val start: Long
    val end: Long

    data class Stay(val visit: Visit, val role: VisitRole, override val start: Long, override val end: Long) : DayEvent

    data class Travel(val move: Move, override val start: Long, override val end: Long) : DayEvent
}

/** A day's events in order, with the span they cover. */
@Immutable
internal class DayPlan(val events: List<DayEvent>, val dayStart: Long, val offsetMinutes: Int) {
    val start: Long get() = events.firstOrNull()?.start ?: dayStart
    val end: Long get() = events.lastOrNull()?.end ?: dayStart

    companion object {
        fun of(state: AtlasUiState, day: Int): DayPlan {
            val history = state.history
            val visits = history.visitsOn(day)
            val moves = history.movesOn(day)
            val offset = visits.firstOrNull()?.offsetMinutes ?: moves.firstOrNull()?.offsetMinutes ?: 0
            val from = day * SECONDS_PER_DAY - offset * 60L
            val until = from + SECONDS_PER_DAY
            val era = state.analysis.homeAt(day)
            val events = visits.map { DayEvent.Stay(it, AtlasAnalyzer.role(it, era), maxOf(it.start, from), minOf(it.end, until)) } +
                moves.map { DayEvent.Travel(it, maxOf(it.start, from), minOf(it.end, until)) }
            return DayPlan(events.filter { it.end > it.start }.sortedBy { it.start }, from, offset)
        }
    }
}

/**
 * One day, replayed. The map holds the day's own paths; the ribbon under it is the day from
 * midnight to midnight, coloured by where it was spent and how it was travelled. Press play and
 * the route draws itself in the order it was driven, a point of light at its head; drag along
 * the ribbon to put the head at any moment. Below, everything that happened, in order.
 */
@Composable
internal fun DayScreen(
    state: AtlasUiState,
    tiles: MapTileSource,
    day: Int,
    padding: PaddingValues,
    onDay: (Int) -> Unit,
    onOpenPlace: (String) -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = true,
) {
    val colors = AtlasTheme.colors
    val history = state.history
    val plan = remember(state.history, state.analysis, day) { DayPlan.of(state, day) }
    val tracks = remember(state.paths, plan) {
        state.paths?.tracks.orEmpty().filter { it.end >= plan.dayStart && it.start <= plan.dayStart + SECONDS_PER_DAY }
    }
    val map = remember(day) { AtlasMapState() }
    val scope = rememberCoroutineScope()
    // Where the head is, as a share of the day's span; 1 is the whole day drawn.
    val progress = remember(day) { Animatable(1f) }
    var playing by remember(day) { mutableStateOf(false) }

    LaunchedEffect(plan, tracks) {
        val points = plan.events.flatMap {
            when (it) {
                is DayEvent.Stay -> listOf(it.visit.latitude to it.visit.longitude)
                is DayEvent.Travel -> listOf(it.move.latitude to it.move.longitude, it.move.toLatitude to it.move.toLongitude)
            }
        } + tracks.filter { it.size > 0 }.flatMap { track ->
            // The box each track keeps round itself: two corners take it in as well as every point would.
            listOf(WebMercator.latitude(track.minY) to WebMercator.longitude(track.minX), WebMercator.latitude(track.maxY) to WebMercator.longitude(track.maxX))
        }
        MapBounds.of(points)?.let { map.show(it) }
    }
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        if (progress.value >= 1f) progress.snapTo(0f)
        progress.animateTo(1f, tween(((1f - progress.value) * REPLAY_MS).toInt().coerceAtLeast(1), easing = LinearEasing))
        playing = false
    }

    val homeKey = state.analysis.homeAt(day)?.home
    val markers = remember(plan) {
        plan.events.filterIsInstance<DayEvent.Stay>().distinctBy { it.visit.placeKey }.map {
            MapMarker(it.visit.placeKey, it.visit.latitude, it.visit.longitude, it.role, 0.35f)
        }
    }
    val flights = remember(plan) { plan.events.filterIsInstance<DayEvent.Travel>().map { it.move }.filter { it.mode == com.meticulouscreations.homesafe.atlas.domain.TravelMode.FLY } }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).testTag("atlas_day")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            AtlasRoundButton(Icons.Filled.ChevronLeft, stringResource(Res.string.atlas_day_previous), { onDay(day - 1) }, Modifier.testTag("atlas_day_previous"), enabled = day > history.firstDay)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(dateText(day, weekday = true), style = AtlasTheme.type.title, color = colors.text, maxLines = 1)
                val index = state.analysis.days.index(day)
                if (index != null && plan.events.isNotEmpty()) {
                    val places = state.analysis.days.places[index]
                    Text(
                        pluralStringResource(Res.plurals.atlas_day_summary, places, distanceText((state.analysis.days.groundMeters[index] + state.analysis.days.airMeters[index]).toDouble()), places),
                        style = AtlasTheme.type.label,
                        color = colors.textFaint,
                    )
                }
            }
            AtlasRoundButton(Icons.Filled.ChevronRight, stringResource(Res.string.atlas_day_next), { onDay(day + 1) }, Modifier.testTag("atlas_day_next"), enabled = day < state.today)
        }
        Spacer(Modifier.height(10.dp))

        if (plan.events.isEmpty()) {
            Text(
                stringResource(Res.string.atlas_day_nothing),
                style = AtlasTheme.type.body,
                color = colors.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 48.dp, bottom = 20.dp),
            )
            // The nearest days either side that do have something, rather than a day at a time through a gap.
            val (earlier, later) = remember(history, day) { nearestDaysWithData(history, day) }
            Column(Modifier.fillMaxWidth().padding(horizontal = AtlasGutter), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (earlier != null) DayJump(stringResource(Res.string.atlas_day_jump_earlier, dateText(earlier)), { onDay(earlier) }, Modifier.testTag("atlas_day_jump_earlier"))
                if (later != null) DayJump(stringResource(Res.string.atlas_day_jump_later, dateText(later)), { onDay(later) }, Modifier.testTag("atlas_day_jump_later"))
            }
            return@Column
        }

        Box(Modifier.padding(horizontal = AtlasGutter).fillMaxWidth().height(320.dp).clip(AtlasCardShape).border(1.dp, colors.hairline, AtlasCardShape)) {
            AtlasMap(
                state = map,
                tiles = tiles,
                modifier = Modifier.fillMaxSize(),
                markers = markers,
                flights = flights,
                highlight = tracks,
                head = { if (progress.value >= 1f) Long.MAX_VALUE else plan.start + ((plan.end - plan.start) * progress.value).toLong() },
                animated = active,
                onTapMarker = { onOpenPlace(it.key) },
            )
            AtlasRoundButton(
                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                stringResource(if (playing) Res.string.atlas_day_pause else Res.string.atlas_day_play),
                { playing = !playing },
                Modifier.align(Alignment.BottomEnd).padding(12.dp).testTag("atlas_day_play"),
                size = 48.dp,
                tint = colors.background,
                fill = colors.trail,
            )
        }
        Spacer(Modifier.height(14.dp))

        DayRibbon(
            plan = plan,
            head = { progress.value },
            onScrub = { share ->
                playing = false
                scope.launch { progress.snapTo(share) }
            },
            modifier = Modifier.padding(horizontal = AtlasGutter),
        )
        Spacer(Modifier.height(16.dp))

        Column(Modifier.padding(horizontal = AtlasGutter), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            plan.events.forEach { event ->
                when (event) {
                    is DayEvent.Stay -> {
                        val place = history.place(event.visit.placeKey).let { if (it.key == homeKey?.key) homeKey else it }
                        DayRow(
                            tint = colors.role(event.role),
                            title = placeTitle(place, history),
                            detail = stringResource(Res.string.atlas_day_visit_detail, span(event, plan), durationText(event.end - event.start)),
                            onClick = { onOpenPlace(event.visit.placeKey) },
                        )
                    }

                    is DayEvent.Travel -> DayRow(
                        tint = colors.mode(event.move.mode),
                        title = stringResource(event.move.mode.label),
                        detail = stringResource(Res.string.atlas_day_move_detail, span(event, plan), distanceText(event.move.meters), durationText(event.end - event.start)),
                        icon = event.move.mode.icon,
                    )
                }
            }
        }
    }
}

/** The nearest day before [day] and the nearest after it that have a stay or a move on record, either null where there is none. */
private fun nearestDaysWithData(history: AtlasHistory, day: Int): Pair<Int?, Int?> {
    if (history.isEmpty) return null to null
    fun held(on: Int) = history.visitsOn(on).isNotEmpty() || history.movesOn(on).isNotEmpty()
    val earlier = (minOf(day - 1, history.lastDay) downTo history.firstDay).firstOrNull(::held)
    val later = (maxOf(day + 1, history.firstDay)..history.lastDay).firstOrNull(::held)
    return earlier to later
}

/** A way out of an empty day, to one that has something. */
@Composable
private fun DayJump(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AtlasTheme.colors
    Text(
        label,
        style = AtlasTheme.type.label,
        color = colors.text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(CircleShape)
            .background(colors.surfaceRaised)
            .border(1.dp, colors.hairline, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

@Composable
private fun span(event: DayEvent, plan: DayPlan): String =
    stringResource(Res.string.atlas_day_span, clockText(localMinute(event.start, plan.offsetMinutes)), clockText(localMinute(event.end, plan.offsetMinutes)))

@Composable
private fun DayRow(tint: Color, title: String, detail: String, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: (() -> Unit)? = null) {
    val colors = AtlasTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 6.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(tint.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            if (icon != null) Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp)) else Box(Modifier.size(10.dp).clip(CircleShape).background(tint))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = if (icon == null) AtlasTheme.type.bodyStrong else AtlasTheme.type.body, color = if (icon == null) colors.text else colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = AtlasTheme.type.label, color = colors.textFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The day from midnight to midnight as one band: stays in the colour of what the place is, travel
 * as a thinner bar in the colour of how. [head] (a share of the span from the day's first event
 * to its last) draws a playhead; a tap or a drag along the band moves it ([onScrub]).
 */
@Composable
internal fun DayRibbon(plan: DayPlan, head: () -> Float, onScrub: (Float) -> Unit, modifier: Modifier = Modifier) {
    val colors = AtlasTheme.colors
    val measurer = rememberTextMeasurer(cacheSize = 8)
    val tickStyle = AtlasTheme.type.micro.copy(color = colors.textFaint)
    val description = stringResource(Res.string.atlas_day_ribbon)
    val hours = listOf(0, 6, 12, 18).map { hourText(it) }
    val span = (plan.end - plan.start).coerceAtLeast(1)
    // Where the playhead is, to the five minutes: said to a screen reader, and changing no more
    // often than that while the day plays.
    val headMinute by remember(plan) { derivedStateOf { if (head() >= 1f) -1 else localMinute(plan.start + (span * head()).toLong(), plan.offsetMinutes) / 5 * 5 } }
    val position = if (headMinute < 0) stringResource(Res.string.atlas_day_ribbon_whole) else clockText(headMinute)
    // And as a share of the day, to the hundredth: read where it is said, which while the day
    // plays would otherwise be said over on every frame.
    val headShare by remember(plan) { derivedStateOf { (head().coerceIn(0f, 1f) * 100).roundToInt() / 100f } }

    fun share(x: Float, width: Float): Float {
        val at = plan.dayStart + (x / width).coerceIn(0f, 1f) * SECONDS_PER_DAY
        return ((at - plan.start) / span).coerceIn(0f, 1f)
    }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(64.dp)
            .semantics {
                contentDescription = description
                stateDescription = position
                progressBarRangeInfo = ProgressBarRangeInfo(headShare, 0f..1f)
                setProgress { to ->
                    onScrub(to.coerceIn(0f, 1f))
                    true
                }
            }
            .testTag("atlas_day_ribbon")
            .pointerInput(plan) { detectTapGestures { onScrub(share(it.x, size.width.toFloat())) } }
            .pointerInput(plan) { detectDragGestures { change, _ -> onScrub(share(change.position.x, size.width.toFloat())) } },
    ) {
        val band = 34.dp.toPx()
        val corner = CornerRadius(8.dp.toPx())
        drawRoundRect(colors.surfaceRaised, Offset.Zero, Size(size.width, band), corner)

        fun x(epoch: Long): Float = ((epoch - plan.dayStart).toFloat() / SECONDS_PER_DAY).coerceIn(0f, 1f) * size.width
        plan.events.forEach { event ->
            val from = x(event.start)
            val width = (x(event.end) - from).coerceAtLeast(1.5f)
            when (event) {
                is DayEvent.Stay -> drawRoundRect(colors.role(event.role), Offset(from, 0f), Size(width, band), CornerRadius(3.dp.toPx()), alpha = 0.9f)
                is DayEvent.Travel -> drawRect(colors.mode(event.move.mode), Offset(from, band * 0.32f), Size(width, band * 0.36f))
            }
        }
        hours.forEachIndexed { i, label ->
            val at = size.width * i / 4f
            drawLine(colors.hairline, Offset(at, band), Offset(at, band + 6.dp.toPx()))
            drawText(measurer, label, Offset(at + 3.dp.toPx(), band + 5.dp.toPx()), tickStyle)
        }
        val progress = head()
        if (progress < 1f) {
            val at = x(plan.start + (span * progress).toLong())
            drawLine(Color.White, Offset(at, -4.dp.toPx()), Offset(at, band + 4.dp.toPx()), strokeWidth = 2.dp.toPx())
            drawCircle(Color.White, 4.dp.toPx(), Offset(at, -4.dp.toPx()))
        }
    }
}
