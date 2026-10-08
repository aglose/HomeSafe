package com.meticulouscreations.homesafe.fitness.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.fitness.FitnessUiState
import com.meticulouscreations.homesafe.fitness.HeartUiState
import com.meticulouscreations.homesafe.fitness.WorkoutHeart
import com.meticulouscreations.homesafe.fitness.ZoneNotice
import com.meticulouscreations.homesafe.fitness.domain.HeartRecovery
import com.meticulouscreations.homesafe.fitness.domain.HeartSensor
import com.meticulouscreations.homesafe.fitness.domain.HeartSensorState
import com.meticulouscreations.homesafe.fitness.domain.HeartSummary
import com.meticulouscreations.homesafe.fitness.domain.HeartZone
import com.meticulouscreations.homesafe.text.resolve
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.fitness_heart_bpm
import homesafe.shared.generated.resources.fitness_heart_described
import homesafe.shared.generated.resources.fitness_heart_described_status
import homesafe.shared.generated.resources.fitness_heart_described_zone
import homesafe.shared.generated.resources.fitness_heart_last_average
import homesafe.shared.generated.resources.fitness_heart_last_peak
import homesafe.shared.generated.resources.fitness_heart_last_recorded
import homesafe.shared.generated.resources.fitness_heart_last_title
import homesafe.shared.generated.resources.fitness_heart_last_when
import homesafe.shared.generated.resources.fitness_heart_no_reading
import homesafe.shared.generated.resources.fitness_heart_notice_down
import homesafe.shared.generated.resources.fitness_heart_notice_resting
import homesafe.shared.generated.resources.fitness_heart_notice_up
import homesafe.shared.generated.resources.fitness_heart_open
import homesafe.shared.generated.resources.fitness_heart_recovery
import homesafe.shared.generated.resources.fitness_heart_recovery_described
import homesafe.shared.generated.resources.fitness_heart_recovery_level
import homesafe.shared.generated.resources.fitness_heart_sensor_unnamed
import homesafe.shared.generated.resources.fitness_heart_set_max
import homesafe.shared.generated.resources.fitness_heart_status_bluetooth_off
import homesafe.shared.generated.resources.fitness_heart_status_connected
import homesafe.shared.generated.resources.fitness_heart_status_connecting
import homesafe.shared.generated.resources.fitness_heart_status_lost
import homesafe.shared.generated.resources.fitness_heart_status_off
import homesafe.shared.generated.resources.fitness_heart_status_permission
import homesafe.shared.generated.resources.fitness_heart_status_scanning
import homesafe.shared.generated.resources.fitness_heart_status_searching
import homesafe.shared.generated.resources.fitness_heart_status_unsupported
import homesafe.shared.generated.resources.fitness_heart_status_waiting
import homesafe.shared.generated.resources.fitness_heart_time_described
import homesafe.shared.generated.resources.fitness_heart_zone_below
import homesafe.shared.generated.resources.fitness_heart_zone_label
import homesafe.shared.generated.resources.fitness_heart_zone_line
import org.jetbrains.compose.resources.stringResource
import kotlin.math.exp

/**
 * The heart rate on a workout: a heart beating in time with the wearer's, the beats a minute in
 * the logger's own big numerals, and the zone as a block of its colour with its number in it,
 * all sized to be read from a phone lying on the bench. Under them the five zones as a strip,
 * the one the heart is in lit, with the time this workout has spent in each.
 *
 * When the heart settles into another zone the panel says so for as long as the notice stands
 * (a few seconds; the view model takes it away), its edge thickening in the new zone's colour.
 * The buzz that goes with it is [ZoneBuzz]. With no reading the panel says what the sensor is
 * doing instead. A tap opens the heart-rate page.
 */
@Composable
internal fun HeartPanel(heart: HeartUiState, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val colors = FitnessTheme.colors
    val type = FitnessTheme.type
    val bpm = heart.bpm
    val zone = heart.zone
    val tint by animateColorAsState(if (bpm != null && heart.bounds != null) colors.zone(zone) else colors.textFaint, tween(400), label = "heartTint")
    val notice = heart.notice
    val flare by animateFloatAsState(if (notice != null) 1f else 0f, tween(300), label = "heartNotice")
    val status = heartStatus(heart.sensor)
    val described = when {
        bpm == null -> stringResource(Res.string.fitness_heart_described_status, status)
        zone != null -> stringResource(Res.string.fitness_heart_described_zone, bpm, zone.number, stringResource(zone.label))
        else -> stringResource(Res.string.fitness_heart_described, bpm)
    }
    Column(
        modifier
            .fillMaxWidth()
            .clip(FitnessCardShape)
            .background(colors.surface)
            .border((1 + 1.5f * flare).dp, lerp(colors.hairline, tint, if (bpm != null) 0.45f + 0.55f * flare else 0f), FitnessCardShape)
            .clickable(role = Role.Button, onClickLabel = stringResource(Res.string.fitness_heart_open), onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag("fitness_heart"),
    ) {
        Row(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = described }, verticalAlignment = Alignment.CenterVertically) {
            PulsingHeart(bpm, tint, Modifier.size(52.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                bpm?.toString() ?: stringResource(Res.string.fitness_heart_no_reading),
                style = type.numeral,
                color = if (bpm != null) colors.text else colors.textFaint,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.alignByBaseline(),
            )
            if (bpm != null) {
                Spacer(Modifier.width(6.dp))
                Text(stringResource(Res.string.fitness_heart_bpm), style = type.label, color = colors.textFaint, maxLines = 1, modifier = Modifier.alignByBaseline())
            }
            Spacer(Modifier.weight(1f))
            if (bpm != null && heart.bounds != null) ZoneBadge(zone, tint)
        }
        val line = when {
            notice != null -> null
            bpm == null -> status
            heart.bounds == null -> stringResource(Res.string.fitness_heart_set_max)
            else -> null
        }
        if (notice != null) {
            NoticeLine(notice, Modifier.padding(top = 8.dp))
        } else if (line != null) {
            Text(line, style = type.label, color = colors.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp).testTag("fitness_heart_status"))
        }
        if (heart.bounds != null) {
            Spacer(Modifier.height(10.dp))
            ZoneStrip(zone.takeIf { bpm != null }, heart.summary)
        }
    }
}

/**
 * The buzz for a change of zone: once for each notice, long for up and short for down. No sound.
 * It sits at the top of the app and not in the panel, which is in a scrolling list and would
 * buzz again each time it came back into view.
 */
@Composable
internal fun ZoneBuzz(notice: ZoneNotice?) {
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(notice?.token) {
        if (notice != null) haptics.performHapticFeedback(if (notice.rising) HapticFeedbackType.LongPress else HapticFeedbackType.Confirm)
    }
}

/** The zone, to be read from across a bench: its number large on its own colour, its name under it. */
@Composable
private fun ZoneBadge(zone: HeartZone?, tint: Color, modifier: Modifier = Modifier) {
    val colors = FitnessTheme.colors
    val type = FitnessTheme.type
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.widthIn(min = 92.dp).clip(shape).background(tint.copy(alpha = 0.2f)).border(1.dp, tint.copy(alpha = 0.7f), shape).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (zone == null) {
            Text(stringResource(Res.string.fitness_heart_zone_below), style = type.headline, color = colors.textMuted, maxLines = 1)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.fitness_heart_zone_label).uppercase(), style = type.micro, color = tint, maxLines = 1)
                Spacer(Modifier.width(6.dp))
                AnimatedContent(zone.number, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) }, label = "heartZone") { number ->
                    Text(number.toString(), style = type.hero, color = tint, maxLines = 1)
                }
            }
            Text(stringResource(zone.label), style = type.label, color = colors.text, maxLines = 1)
        }
    }
}

/** A change of zone, said once: which way, and where to. */
@Composable
private fun NoticeLine(notice: ZoneNotice, modifier: Modifier = Modifier) {
    val to = notice.to
    val tint = FitnessTheme.colors.zone(to)
    Row(modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("fitness_heart_notice"), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (notice.rising) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            when {
                to == null -> stringResource(Res.string.fitness_heart_notice_resting)
                notice.rising -> stringResource(Res.string.fitness_heart_notice_up, to.number, stringResource(to.label))
                else -> stringResource(Res.string.fitness_heart_notice_down, to.number, stringResource(to.label))
            },
            style = FitnessTheme.type.bodyStrong,
            color = tint,
            maxLines = 1,
        )
    }
}

/**
 * A heart that beats [bpm] times a minute, with a ring going out from it at each beat. The beat
 * is a pair, a strong one and a lighter one after it, as a pulse is felt. It is kept in step by
 * moving a phase on each frame at the current rate, so a change of rate bends the rhythm and
 * doesn't jump it. Still when there is no reading, and in a preview.
 */
@Composable
private fun PulsingHeart(bpm: Int?, tint: Color, modifier: Modifier = Modifier) {
    val still = LocalInspectionMode.current
    val rate by rememberUpdatedState(bpm)
    // How far through the beat, 0 to 1. Read only where it is drawn.
    val phase = remember { mutableFloatStateOf(0f) }
    val beating = bpm != null && !still
    LaunchedEffect(beating) {
        if (!beating) {
            phase.floatValue = REST_PHASE
            return@LaunchedEffect
        }
        var last = 0L
        // An infinite animation's frames, as the rest timer's are: nothing waiting for animations to settle waits on a heartbeat.
        while (true) {
            withInfiniteAnimationFrameNanos { now ->
                if (last != 0L) phase.floatValue = (phase.floatValue + (now - last) / 1e9f * (rate ?: 60) / 60f) % 1f
                last = now
            }
        }
    }
    Box(
        modifier.drawBehind {
            val reach = size.minDimension / 2
            drawCircle(tint.copy(alpha = 0.14f), radius = reach * 0.72f)
            if (beating) drawCircle(tint.copy(alpha = 0.4f * (1f - phase.floatValue)), radius = reach * (0.6f + 0.4f * phase.floatValue))
        },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Favorite,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(26.dp).graphicsLayer {
                val swell = heartSwell(phase.floatValue)
                scaleX = swell
                scaleY = swell
            },
        )
    }
}

/** Where a heart that isn't beating is held: between beats. */
private const val REST_PHASE = 0.6f

/** How big the heart is drawn at [phase] of a beat: a strong swell at its start and a lighter one a quarter of the way on. */
private fun heartSwell(phase: Float): Float = 1f + 0.24f * bump(phase, 0.04f) + 0.12f * bump(phase, 0.3f)

private fun bump(phase: Float, at: Float): Float {
    val d = (phase - at) / 0.07f
    return exp(-d * d)
}

/**
 * The five zones side by side, zone 1 on the left, the one the heart is in ([current]) lit and
 * the rest dim. With a [summary], under each is how long the workout has spent there.
 */
@Composable
internal fun ZoneStrip(current: HeartZone?, summary: HeartSummary?, modifier: Modifier = Modifier) {
    val colors = FitnessTheme.colors
    Row(modifier.fillMaxWidth().testTag("fitness_heart_strip"), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        HeartZone.entries.forEach { zone ->
            val lit = zone == current
            val tint = colors.zone(zone)
            val fill by animateColorAsState(if (lit) tint else tint.copy(alpha = 0.22f), tween(300), label = "zoneSegment")
            val spent = summary?.takeIf { !it.isEmpty }?.let { FitnessFormat.spent(it.millisIn(zone)).resolve() }
            val spoken = spent?.let { stringResource(Res.string.fitness_heart_time_described, zone.number, stringResource(zone.label), it) }
            Column(Modifier.weight(1f).then(if (spoken != null) Modifier.clearAndSetSemantics { contentDescription = spoken } else Modifier), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().height(if (lit) 10.dp else 6.dp).clip(CircleShape).background(fill))
                if (spent != null) {
                    Text(spent, style = FitnessTheme.type.micro, color = if (lit) colors.text else colors.textFaint, maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

/**
 * In the rest timer: the heart coming back down. The highest it read since the set and what it
 * reads now, with the zone it has fallen to, so the next set can wait for the heart as well as
 * the clock.
 */
@Composable
internal fun RecoveryLine(recovery: HeartRecovery, zone: HeartZone?, zoned: Boolean, modifier: Modifier = Modifier) {
    val colors = FitnessTheme.colors
    val type = FitnessTheme.type
    val tint = if (zoned) colors.zone(zone) else colors.textMuted
    val spoken = stringResource(Res.string.fitness_heart_recovery_described, recovery.bpm, recovery.drop)
    // The tag before the clearing: a node's semantics are gathered from the last modifier back, and clearing drops what came after it.
    Row(modifier.fillMaxWidth().testTag("fitness_rest_recovery").clearAndSetSemantics { contentDescription = spoken }, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Favorite, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            if (recovery.drop > 0) stringResource(Res.string.fitness_heart_recovery, recovery.peakBpm, recovery.bpm) else stringResource(Res.string.fitness_heart_recovery_level, recovery.bpm),
            style = type.bodyStrong,
            color = colors.text,
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
        if (zoned) {
            Text(
                zone?.let { stringResource(Res.string.fitness_heart_zone_line, it.number, stringResource(it.label)) } ?: stringResource(Res.string.fitness_heart_zone_below),
                style = type.label,
                color = tint,
                maxLines = 1,
            )
        }
    }
}

/** On Today: what the heart did in the last workout a sensor was on for. Its average and peak, and the time in each zone as bars. */
@Composable
internal fun LastHeartCard(last: WorkoutHeart, state: FitnessUiState, modifier: Modifier = Modifier) {
    val colors = FitnessTheme.colors
    val type = FitnessTheme.type
    val summary = last.summary
    FitnessCard(modifier.testTag("fitness_heart_last"), title = stringResource(Res.string.fitness_heart_last_title)) {
        Text(
            stringResource(
                Res.string.fitness_heart_last_when,
                stringResource(last.workout.focus.label),
                FitnessFormat.whenText(last.workout.startedAtEpochSeconds, state.nowEpochSeconds, state.utcOffsetSeconds).resolve(),
            ),
            style = type.body,
            color = colors.textMuted,
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat(summary.averageBpm?.toString() ?: stringResource(Res.string.fitness_heart_no_reading), stringResource(Res.string.fitness_heart_last_average), Modifier.weight(1f))
            Stat(summary.peakBpm.toString(), stringResource(Res.string.fitness_heart_last_peak), Modifier.weight(1f))
            Stat(FitnessFormat.spent(summary.totalMillis).resolve(), stringResource(Res.string.fitness_heart_last_recorded), Modifier.weight(1f))
        }
        Spacer(Modifier.height(14.dp))
        // The longest stretch fills the row; the rest are drawn against it.
        val longest = summary.zoneMillis.max().coerceAtLeast(1L)
        HeartZone.entries.reversed().forEach { zone ->
            val spent = FitnessFormat.spent(summary.millisIn(zone)).resolve()
            val spoken = stringResource(Res.string.fitness_heart_time_described, zone.number, stringResource(zone.label), spent)
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clearAndSetSemantics { contentDescription = spoken }, verticalAlignment = Alignment.CenterVertically) {
                Text(zone.number.toString(), style = type.bodyStrong, color = colors.zone(zone), modifier = Modifier.width(18.dp))
                Text(stringResource(zone.label), style = type.label, color = colors.textMuted, maxLines = 1, modifier = Modifier.width(84.dp))
                Box(Modifier.weight(1f).height(8.dp).clip(CircleShape).background(colors.surfaceRaised)) {
                    Box(Modifier.fillMaxWidth(summary.millisIn(zone).toFloat() / longest).height(8.dp).clip(CircleShape).background(colors.zone(zone)))
                }
                Text(spent, style = type.label, color = colors.text, maxLines = 1, textAlign = TextAlign.End, modifier = Modifier.width(60.dp))
            }
        }
    }
}

/** What the sensor's link is doing, in a line. */
@Composable
internal fun heartStatus(sensor: HeartSensorState): String = when (sensor) {
    HeartSensorState.Unsupported -> stringResource(Res.string.fitness_heart_status_unsupported)
    HeartSensorState.Off -> stringResource(Res.string.fitness_heart_status_off)
    HeartSensorState.PermissionNeeded -> stringResource(Res.string.fitness_heart_status_permission)
    HeartSensorState.BluetoothOff -> stringResource(Res.string.fitness_heart_status_bluetooth_off)
    is HeartSensorState.Searching -> stringResource(Res.string.fitness_heart_status_searching)
    is HeartSensorState.Scanning -> stringResource(Res.string.fitness_heart_status_scanning, sensorName(sensor.sensor))
    is HeartSensorState.Connecting -> stringResource(Res.string.fitness_heart_status_connecting, sensorName(sensor.sensor))
    is HeartSensorState.Lost -> stringResource(Res.string.fitness_heart_status_lost, sensorName(sensor.sensor))
    is HeartSensorState.Connected -> if (sensor.bpm == null) stringResource(Res.string.fitness_heart_status_waiting) else stringResource(Res.string.fitness_heart_status_connected, sensorName(sensor.sensor))
}

/** A sensor's own name, or a plain one for a sensor that gave none. */
@Composable
internal fun sensorName(sensor: HeartSensor): String = sensor.name.ifEmpty { stringResource(Res.string.fitness_heart_sensor_unnamed) }
