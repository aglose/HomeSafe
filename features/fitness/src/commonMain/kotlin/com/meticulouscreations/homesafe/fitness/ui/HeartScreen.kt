package com.meticulouscreations.homesafe.fitness.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.fitness.HeartUiState
import com.meticulouscreations.homesafe.fitness.domain.HeartProfile
import com.meticulouscreations.homesafe.fitness.domain.HeartSensor
import com.meticulouscreations.homesafe.fitness.domain.HeartSensorState
import com.meticulouscreations.homesafe.fitness.domain.HeartZone
import com.meticulouscreations.homesafe.fitness.domain.HeartZones
import com.meticulouscreations.homesafe.fitness.domain.SensorSighting
import com.meticulouscreations.homesafe.fitness.domain.ZoneBounds
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.fitness_heart_notes_accuracy
import homesafe.shared.generated.resources.fitness_heart_notes_screen
import homesafe.shared.generated.resources.fitness_heart_notes_title
import homesafe.shared.generated.resources.fitness_heart_sensor_allow
import homesafe.shared.generated.resources.fitness_heart_sensor_connect
import homesafe.shared.generated.resources.fitness_heart_sensor_find
import homesafe.shared.generated.resources.fitness_heart_sensor_forget
import homesafe.shared.generated.resources.fitness_heart_sensor_found
import homesafe.shared.generated.resources.fitness_heart_sensor_found_none
import homesafe.shared.generated.resources.fitness_heart_sensor_instruction
import homesafe.shared.generated.resources.fitness_heart_sensor_permission
import homesafe.shared.generated.resources.fitness_heart_sensor_stop
import homesafe.shared.generated.resources.fitness_heart_sensor_title
import homesafe.shared.generated.resources.fitness_heart_sensor_use
import homesafe.shared.generated.resources.fitness_heart_signal_far
import homesafe.shared.generated.resources.fitness_heart_signal_mid
import homesafe.shared.generated.resources.fitness_heart_signal_near
import homesafe.shared.generated.resources.fitness_heart_zone_range
import homesafe.shared.generated.resources.fitness_heart_zones_age
import homesafe.shared.generated.resources.fitness_heart_zones_age_less
import homesafe.shared.generated.resources.fitness_heart_zones_age_more
import homesafe.shared.generated.resources.fitness_heart_zones_estimated
import homesafe.shared.generated.resources.fitness_heart_zones_max
import homesafe.shared.generated.resources.fitness_heart_zones_max_less
import homesafe.shared.generated.resources.fitness_heart_zones_max_more
import homesafe.shared.generated.resources.fitness_heart_zones_mode_age
import homesafe.shared.generated.resources.fitness_heart_zones_mode_max
import homesafe.shared.generated.resources.fitness_heart_zones_reserve
import homesafe.shared.generated.resources.fitness_heart_zones_resting
import homesafe.shared.generated.resources.fitness_heart_zones_resting_less
import homesafe.shared.generated.resources.fitness_heart_zones_resting_more
import homesafe.shared.generated.resources.fitness_heart_zones_resting_use
import homesafe.shared.generated.resources.fitness_heart_zones_save
import homesafe.shared.generated.resources.fitness_heart_zones_saved
import homesafe.shared.generated.resources.fitness_heart_zones_title
import org.jetbrains.compose.resources.stringResource

/**
 * Where the heart rate is set up: the sensor (find one, choose it, forget it), and the zones (a
 * maximum heart rate the lifter knows, or their age to estimate one from, and a resting rate if
 * they want the zones drawn on the reserve). Nothing about the zones is saved until Save is
 * pressed; the table under the steppers shows what the numbers on them would give.
 */
@Composable
internal fun HeartScreen(heart: HeartUiState, padding: PaddingValues, actions: FitnessActions, modifier: Modifier = Modifier) {
    val stopSearch by rememberUpdatedState(actions.onStopHeartSearch)
    // Leaving with the list still up: nothing was chosen, so the looking stops.
    DisposableEffect(Unit) { onDispose { stopSearch() } }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = FitnessGutter).testTag("fitness_heart_page"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SensorCard(heart, actions)
        ZonesCard(heart.settings.profile, actions.onSaveHeartProfile)
        FitnessCard(title = stringResource(Res.string.fitness_heart_notes_title)) {
            Text(stringResource(Res.string.fitness_heart_notes_accuracy), style = FitnessTheme.type.body, color = FitnessTheme.colors.textMuted)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(Res.string.fitness_heart_notes_screen), style = FitnessTheme.type.body, color = FitnessTheme.colors.textMuted)
        }
    }
}

/** The sensor: what its link is doing, how to make the band findable, and the one thing to do about it next. */
@Composable
private fun SensorCard(heart: HeartUiState, actions: FitnessActions) {
    val colors = FitnessTheme.colors
    val type = FitnessTheme.type
    val state = heart.sensor
    val chosen = heart.settings.sensor
    FitnessCard(Modifier.testTag("fitness_heart_sensor_card"), title = stringResource(Res.string.fitness_heart_sensor_title)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val live = state is HeartSensorState.Connected
            Box(Modifier.size(40.dp).clip(CircleShape).background((if (live) colors.good else colors.textFaint).copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Bluetooth, contentDescription = null, tint = if (live) colors.good else colors.textMuted, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                if (chosen != null) Text(sensorName(chosen), style = type.headline, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(heartStatus(state), style = type.body, color = colors.textMuted, modifier = Modifier.testTag("fitness_heart_sensor_status"))
            }
            heart.bpm?.let { Text(it.toString(), style = type.title, color = colors.zone(heart.zone).takeIf { heart.bounds != null } ?: colors.text, maxLines = 1) }
        }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(Res.string.fitness_heart_sensor_instruction), style = type.body, color = colors.textMuted)
        if (state == HeartSensorState.PermissionNeeded) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(Res.string.fitness_heart_sensor_permission), style = type.body, color = colors.amber)
        }
        if (state is HeartSensorState.Searching) {
            Spacer(Modifier.height(14.dp))
            CardLabel(stringResource(Res.string.fitness_heart_sensor_found))
            Spacer(Modifier.height(4.dp))
            if (state.found.isEmpty()) {
                Text(stringResource(Res.string.fitness_heart_sensor_found_none), style = type.body, color = colors.textFaint, modifier = Modifier.padding(vertical = 8.dp))
            } else {
                state.found.forEachIndexed { index, sighting -> FoundSensor(sighting, actions.onChooseHeartSensor, Modifier.testTag("fitness_heart_found_$index")) }
            }
        }
        Spacer(Modifier.height(14.dp))
        // Nothing is being tried: the one thing to do is to try.
        val idle = state == HeartSensorState.Off || state == HeartSensorState.PermissionNeeded
        if (idle) {
            ForgeButton(
                stringResource(
                    if (state == HeartSensorState.PermissionNeeded) {
                        Res.string.fitness_heart_sensor_allow
                    } else if (chosen == null) {
                        Res.string.fitness_heart_sensor_find
                    } else {
                        Res.string.fitness_heart_sensor_connect
                    },
                ),
                actions.onConnectHeart,
                Modifier.fillMaxWidth().testTag("fitness_heart_connect"),
                icon = Icons.Filled.Bluetooth,
                height = 48.dp,
            )
        } else if (chosen == null && state != HeartSensorState.Unsupported) {
            // Looking for something to choose (or waiting for Bluetooth to come on so as to look).
            GhostButton(stringResource(Res.string.fitness_heart_sensor_stop), actions.onStopHeartSearch, Modifier.fillMaxWidth().testTag("fitness_heart_stop"))
        }
        if (chosen != null) {
            if (idle) Spacer(Modifier.height(10.dp))
            GhostButton(stringResource(Res.string.fitness_heart_sensor_forget), actions.onForgetHeartSensor, Modifier.fillMaxWidth().testTag("fitness_heart_forget"), tint = colors.textMuted)
        }
    }
}

/** A sensor a scan has heard, to be chosen with a tap: its name and how near it seems. */
@Composable
private fun FoundSensor(sighting: SensorSighting, onChoose: (HeartSensor) -> Unit, modifier: Modifier = Modifier) {
    val colors = FitnessTheme.colors
    val type = FitnessTheme.type
    val name = sensorName(sighting.sensor)
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClickLabel = stringResource(Res.string.fitness_heart_sensor_use, name)) { onChoose(sighting.sensor) }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = type.bodyStrong, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(
                    if (sighting.rssi >= NEAR_DBM) {
                        Res.string.fitness_heart_signal_near
                    } else if (sighting.rssi >= CLOSE_DBM) {
                        Res.string.fitness_heart_signal_mid
                    } else {
                        Res.string.fitness_heart_signal_far
                    },
                ),
                style = type.label,
                color = colors.textFaint,
                maxLines = 1,
            )
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = colors.textFaint, modifier = Modifier.size(22.dp))
    }
}

/** A band on the wrist of the hand holding the phone is about this loud; one across the gym floor is under [CLOSE_DBM]. */
private const val NEAR_DBM = -60
private const val CLOSE_DBM = -78

/**
 * The zones' numbers: a maximum the lifter knows or an age to estimate one from, and whether to
 * count from a resting rate. The steppers start from what is saved, or with nothing saved from a
 * round number to step away from, which is nobody's until Save says so.
 */
@Composable
private fun ZonesCard(profile: HeartProfile, onSave: (HeartProfile) -> Unit) {
    val colors = FitnessTheme.colors
    val type = FitnessTheme.type
    var byAge by rememberSaveable(profile) { mutableStateOf(profile.maxBpm == null) }
    var max by rememberSaveable(profile) { mutableIntStateOf(profile.maxBpm ?: 185) }
    var age by rememberSaveable(profile) { mutableIntStateOf(profile.age ?: 30) }
    var useResting by rememberSaveable(profile) { mutableStateOf(profile.restingBpm != null) }
    var resting by rememberSaveable(profile) { mutableIntStateOf(profile.restingBpm ?: 60) }
    // An age that was saved is kept when a maximum is entered over it: it costs nothing, and going back to the estimate finds it.
    val draft = HeartProfile(maxBpm = if (byAge) null else max, age = if (byAge) age else profile.age, restingBpm = if (useResting) resting else null)
    val bounds = HeartZones.bounds(draft)
    FitnessCard(Modifier.testTag("fitness_heart_zones_card"), title = stringResource(Res.string.fitness_heart_zones_title)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceChip(stringResource(Res.string.fitness_heart_zones_mode_age), byAge, { byAge = true }, Modifier.testTag("fitness_heart_mode_age"))
            ChoiceChip(stringResource(Res.string.fitness_heart_zones_mode_max), !byAge, { byAge = false }, Modifier.testTag("fitness_heart_mode_max"))
        }
        Spacer(Modifier.height(8.dp))
        // On a line of its own: it is a switch beside the choice above, not a third way of choosing.
        ChoiceChip(stringResource(Res.string.fitness_heart_zones_resting_use), useResting, { useResting = !useResting }, Modifier.testTag("fitness_heart_use_resting"), tint = colors.ice)
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (byAge) {
                NumberStepper(
                    label = stringResource(Res.string.fitness_heart_zones_age),
                    value = age.toString(),
                    order = age.toDouble(),
                    onStep = { age = (age + it).coerceIn(HeartZones.AGE_RANGE) },
                    minusLabel = stringResource(Res.string.fitness_heart_zones_age_less),
                    plusLabel = stringResource(Res.string.fitness_heart_zones_age_more),
                    modifier = Modifier.weight(1f).testTag("fitness_heart_age"),
                )
            } else {
                NumberStepper(
                    label = stringResource(Res.string.fitness_heart_zones_max),
                    value = max.toString(),
                    order = max.toDouble(),
                    onStep = { max = (max + it).coerceIn(HeartZones.MAX_RANGE) },
                    minusLabel = stringResource(Res.string.fitness_heart_zones_max_less),
                    plusLabel = stringResource(Res.string.fitness_heart_zones_max_more),
                    modifier = Modifier.weight(1f).testTag("fitness_heart_max"),
                )
            }
            if (useResting) {
                NumberStepper(
                    label = stringResource(Res.string.fitness_heart_zones_resting),
                    value = resting.toString(),
                    order = resting.toDouble(),
                    onStep = { resting = (resting + it).coerceIn(HeartZones.RESTING_RANGE) },
                    minusLabel = stringResource(Res.string.fitness_heart_zones_resting_less),
                    plusLabel = stringResource(Res.string.fitness_heart_zones_resting_more),
                    modifier = Modifier.weight(1f).testTag("fitness_heart_resting"),
                    tint = colors.ice,
                )
            }
        }
        if (bounds != null) {
            Spacer(Modifier.height(14.dp))
            ZoneTable(bounds)
            if (bounds.estimated) {
                Spacer(Modifier.height(10.dp))
                Text(stringResource(Res.string.fitness_heart_zones_estimated, bounds.maxBpm), style = type.label, color = colors.textFaint)
            }
            bounds.restingBpm?.let {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(Res.string.fitness_heart_zones_reserve, it), style = type.label, color = colors.textFaint)
            }
        }
        Spacer(Modifier.height(14.dp))
        val changed = draft != profile
        ForgeButton(
            stringResource(if (changed) Res.string.fitness_heart_zones_save else Res.string.fitness_heart_zones_saved),
            { onSave(draft) },
            Modifier.fillMaxWidth().testTag("fitness_heart_save"),
            icon = Icons.Filled.Check,
            enabled = changed,
            height = 48.dp,
        )
    }
}

/** The five zones, hardest at the top as on a gym's wall chart, each with the beats a minute it covers. */
@Composable
private fun ZoneTable(bounds: ZoneBounds, modifier: Modifier = Modifier) {
    val colors = FitnessTheme.colors
    val type = FitnessTheme.type
    Column(modifier.testTag("fitness_heart_zone_table")) {
        HeartZone.entries.reversed().forEach { zone ->
            val range = bounds.range(zone)
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(22.dp).clip(CircleShape).background(colors.zone(zone).copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
                    Text(zone.number.toString(), style = type.micro, color = colors.zone(zone))
                }
                Spacer(Modifier.width(10.dp))
                Text(stringResource(zone.label), style = type.bodyStrong, color = colors.text, maxLines = 1, modifier = Modifier.weight(1f))
                Text(
                    stringResource(Res.string.fitness_heart_zone_range, range.first, range.last),
                    style = type.bodyStrong,
                    color = colors.textMuted,
                    maxLines = 1,
                    textAlign = TextAlign.End,
                    modifier = Modifier.testTag("fitness_heart_range_${zone.number}"),
                )
            }
        }
    }
}
