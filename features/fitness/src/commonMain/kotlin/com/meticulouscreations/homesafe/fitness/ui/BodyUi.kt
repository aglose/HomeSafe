package com.meticulouscreations.homesafe.fitness.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.fitness.HealthAccess
import com.meticulouscreations.homesafe.fitness.HealthUiState
import com.meticulouscreations.homesafe.fitness.domain.BodyBoard
import com.meticulouscreations.homesafe.fitness.domain.Readiness
import com.meticulouscreations.homesafe.fitness.domain.ReadinessFactor
import com.meticulouscreations.homesafe.fitness.domain.ReadinessLevel
import com.meticulouscreations.homesafe.fitness.domain.SleepBoard
import com.meticulouscreations.homesafe.fitness.domain.SleepNight
import com.meticulouscreations.homesafe.fitness.domain.StepsBoard
import com.meticulouscreations.homesafe.fitness.ui.shader.PlasmaRing
import com.meticulouscreations.homesafe.text.resolve
import com.meticulouscreations.homesafe.ui.theme.albertSansFontFamily
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.fitness_body_connect_body
import homesafe.shared.generated.resources.fitness_body_connect_button
import homesafe.shared.generated.resources.fitness_body_connect_hint
import homesafe.shared.generated.resources.fitness_body_failed
import homesafe.shared.generated.resources.fitness_body_manage_button
import homesafe.shared.generated.resources.fitness_body_missing_body
import homesafe.shared.generated.resources.fitness_body_missing_button
import homesafe.shared.generated.resources.fitness_body_not_shared
import homesafe.shared.generated.resources.fitness_body_nothing_shared
import homesafe.shared.generated.resources.fitness_body_part_resting
import homesafe.shared.generated.resources.fitness_body_part_variability
import homesafe.shared.generated.resources.fitness_body_rationale_change
import homesafe.shared.generated.resources.fitness_body_rationale_done
import homesafe.shared.generated.resources.fitness_body_rationale_heart
import homesafe.shared.generated.resources.fitness_body_rationale_intro
import homesafe.shared.generated.resources.fitness_body_rationale_privacy
import homesafe.shared.generated.resources.fitness_body_rationale_runs
import homesafe.shared.generated.resources.fitness_body_rationale_sleep
import homesafe.shared.generated.resources.fitness_body_rationale_steps
import homesafe.shared.generated.resources.fitness_body_rationale_title
import homesafe.shared.generated.resources.fitness_body_readiness
import homesafe.shared.generated.resources.fitness_body_readiness_described
import homesafe.shared.generated.resources.fitness_body_readiness_high
import homesafe.shared.generated.resources.fitness_body_readiness_high_advice
import homesafe.shared.generated.resources.fitness_body_readiness_learning
import homesafe.shared.generated.resources.fitness_body_readiness_low
import homesafe.shared.generated.resources.fitness_body_readiness_low_advice
import homesafe.shared.generated.resources.fitness_body_readiness_moderate
import homesafe.shared.generated.resources.fitness_body_readiness_moderate_advice
import homesafe.shared.generated.resources.fitness_body_readiness_none
import homesafe.shared.generated.resources.fitness_body_readiness_unshared
import homesafe.shared.generated.resources.fitness_body_readiness_unshared_title
import homesafe.shared.generated.resources.fitness_body_readiness_waiting
import homesafe.shared.generated.resources.fitness_body_readiness_waiting_title
import homesafe.shared.generated.resources.fitness_body_reading
import homesafe.shared.generated.resources.fitness_body_refused
import homesafe.shared.generated.resources.fitness_body_sleep
import homesafe.shared.generated.resources.fitness_body_sleep_average
import homesafe.shared.generated.resources.fitness_body_sleep_described
import homesafe.shared.generated.resources.fitness_body_sleep_described_stages
import homesafe.shared.generated.resources.fitness_body_sleep_none
import homesafe.shared.generated.resources.fitness_body_sleep_span
import homesafe.shared.generated.resources.fitness_body_steps
import homesafe.shared.generated.resources.fitness_body_steps_average
import homesafe.shared.generated.resources.fitness_body_steps_described
import homesafe.shared.generated.resources.fitness_body_steps_described_average
import homesafe.shared.generated.resources.fitness_body_steps_no_average
import homesafe.shared.generated.resources.fitness_body_title
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

private val TileShape = RoundedCornerShape(18.dp)

/**
 * On Today: what the band says about the body. A readiness score with what it was read from,
 * today's steps against the week, and last night's sleep. Before the health store is connected
 * it is the invitation to connect it, and says where the data comes from and that it stays on
 * the phone.
 */
@Composable
internal fun BodyCard(health: HealthUiState, onConnect: () -> Unit, onManage: () -> Unit, modifier: Modifier = Modifier, animated: Boolean = true) {
    val colors = FitnessTheme.colors
    val type = FitnessTheme.type
    FitnessCard(modifier.testTag("fitness_body"), title = stringResource(Res.string.fitness_body_title)) {
        when {
            health.access == HealthAccess.MISSING -> {
                Text(stringResource(Res.string.fitness_body_missing_body), style = type.body, color = colors.textMuted)
                Spacer(Modifier.height(14.dp))
                ForgeButton(stringResource(Res.string.fitness_body_missing_button), onConnect, Modifier.fillMaxWidth().testTag("fitness_body_connect"), icon = Icons.Filled.MonitorHeart, height = 48.dp)
            }

            health.access == HealthAccess.OFF -> {
                Text(stringResource(Res.string.fitness_body_connect_body), style = type.body, color = colors.textMuted)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(Res.string.fitness_body_connect_hint), style = type.label, color = colors.textFaint)
                if (health.refused) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(Res.string.fitness_body_refused), style = type.body, color = colors.amber, modifier = Modifier.testTag("fitness_body_refused"))
                }
                Spacer(Modifier.height(14.dp))
                ForgeButton(stringResource(Res.string.fitness_body_connect_button), onConnect, Modifier.fillMaxWidth().testTag("fitness_body_connect"), icon = Icons.Filled.MonitorHeart, height = 48.dp)
                if (health.refused) {
                    Spacer(Modifier.height(10.dp))
                    ManageButton(onManage)
                }
            }

            !health.loaded -> Text(stringResource(Res.string.fitness_body_reading), style = type.body, color = colors.textMuted)

            !health.bodyShared -> {
                Text(stringResource(Res.string.fitness_body_nothing_shared), style = type.body, color = colors.textMuted)
                Spacer(Modifier.height(14.dp))
                ManageButton(onManage)
            }

            else -> {
                ReadinessRow(health.body, animated)
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StepsTile(health.body.steps, Modifier.weight(1f).fillMaxHeight())
                    SleepTile(health.body.sleep, Modifier.weight(1f).fillMaxHeight())
                }
                if (health.failed) {
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(Res.string.fitness_body_failed), style = type.label, color = colors.textFaint, modifier = Modifier.testTag("fitness_body_failed"))
                }
                // Android's sheet is only for the first asking: a kind left out then can only be added over there.
                if (health.partlyShared) {
                    Spacer(Modifier.height(12.dp))
                    ManageButton(onManage)
                }
            }
        }
    }
}

/** The way to the health store's own page, where each kind of record is switched on and off. */
@Composable
internal fun ManageButton(onManage: () -> Unit, modifier: Modifier = Modifier) {
    GhostButton(stringResource(Res.string.fitness_body_manage_button), onManage, modifier.fillMaxWidth().testTag("fitness_body_manage"), icon = Icons.Filled.Settings, tint = FitnessTheme.colors.textMuted)
}

/** The colours a level of readiness is drawn in: green to push, amber for an ordinary day, ember to hold back. */
@Composable
internal fun levelTints(level: ReadinessLevel): Pair<Color, Color> {
    val colors = FitnessTheme.colors
    return when (level) {
        ReadinessLevel.HIGH -> colors.good to Color(0xFF0E8F7A)
        ReadinessLevel.MODERATE -> colors.amber to colors.ember
        ReadinessLevel.LOW -> colors.ember to Color(0xFFB3123A)
    }
}

internal fun levelName(level: ReadinessLevel): StringResource = when (level) {
    ReadinessLevel.HIGH -> Res.string.fitness_body_readiness_high
    ReadinessLevel.MODERATE -> Res.string.fitness_body_readiness_moderate
    ReadinessLevel.LOW -> Res.string.fitness_body_readiness_low
}

private fun levelAdvice(level: ReadinessLevel): StringResource = when (level) {
    ReadinessLevel.HIGH -> Res.string.fitness_body_readiness_high_advice
    ReadinessLevel.MODERATE -> Res.string.fitness_body_readiness_moderate_advice
    ReadinessLevel.LOW -> Res.string.fitness_body_readiness_low_advice
}

/**
 * The readiness score as a ring with its number in it, and beside it what that means for today
 * and the readings behind it. With no score it says why: nothing from last night yet, or
 * nothing shared to read one from.
 */
@Composable
private fun ReadinessRow(body: BodyBoard, animated: Boolean, modifier: Modifier = Modifier) {
    val colors = FitnessTheme.colors
    val type = FitnessTheme.type
    val readiness = body.readiness
    val tints = readiness?.let { levelTints(it.level) } ?: (colors.textFaint to colors.textFaint)
    val title = when {
        readiness != null -> stringResource(levelName(readiness.level))
        body.readinessShared -> stringResource(Res.string.fitness_body_readiness_waiting_title)
        else -> stringResource(Res.string.fitness_body_readiness_unshared_title)
    }
    val described = readiness?.let { stringResource(Res.string.fitness_body_readiness_described, it.score, title) }
    Row(modifier.fillMaxWidth().testTag("fitness_body_readiness"), verticalAlignment = Alignment.CenterVertically) {
        Box(
            // The tag before the clearing: clearing drops the semantics of what comes after it, the number's own tag included.
            Modifier.size(92.dp).testTag("fitness_body_ring").then(if (described != null) Modifier.clearAndSetSemantics { contentDescription = described } else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            PlasmaRing((readiness?.score ?: 0) / 100f, tints.first, tints.second, colors.surfaceRaised, Modifier.matchParentSize(), running = animated && readiness != null)
            Text(
                readiness?.score?.toString() ?: stringResource(Res.string.fitness_body_readiness_none),
                style = type.title,
                color = if (readiness != null) colors.text else colors.textFaint,
                maxLines = 1,
                modifier = Modifier.testTag("fitness_body_score"),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            CardLabel(stringResource(Res.string.fitness_body_readiness))
            Text(title, style = type.headline, color = if (readiness != null) tints.first else colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            Text(
                when {
                    readiness != null -> stringResource(levelAdvice(readiness.level))
                    body.readinessShared -> stringResource(Res.string.fitness_body_readiness_waiting)
                    else -> stringResource(Res.string.fitness_body_readiness_unshared)
                },
                style = type.label,
                color = colors.textMuted,
            )
            if (readiness != null) ReadinessParts(readiness)
        }
    }
}

/** What the score was read from, a line each: the reading and what is usual for this person. Sleep has its own tile below. */
@Composable
private fun ReadinessParts(readiness: Readiness, modifier: Modifier = Modifier) {
    val colors = FitnessTheme.colors
    val type = FitnessTheme.type
    Column(modifier) {
        readiness.part(ReadinessFactor.VARIABILITY)?.let { part ->
            Text(stringResource(Res.string.fitness_body_part_variability, part.value.roundToInt(), (part.usual ?: part.value).roundToInt()), style = type.label, color = colors.textFaint, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
        }
        readiness.part(ReadinessFactor.RESTING)?.let { part ->
            Text(stringResource(Res.string.fitness_body_part_resting, part.value.roundToInt(), (part.usual ?: part.value).roundToInt()), style = type.label, color = colors.textFaint, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
        }
        if (readiness.learning) {
            Text(stringResource(Res.string.fitness_body_readiness_learning), style = type.label, color = colors.textFaint, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** Today's steps, the week as seven bars with today's lit, and what a day usually comes to. */
@Composable
private fun StepsTile(steps: StepsBoard?, modifier: Modifier = Modifier) {
    val colors = FitnessTheme.colors
    val type = FitnessTheme.type
    val today = steps?.let { FitnessFormat.grouped(it.today) }
    val usual = steps?.average?.let { FitnessFormat.grouped(it) }
    val described = when {
        today == null -> stringResource(Res.string.fitness_body_not_shared)
        usual != null -> stringResource(Res.string.fitness_body_steps_described_average, today, usual)
        else -> stringResource(Res.string.fitness_body_steps_described, today)
    }
    Column(modifier.clip(TileShape).background(colors.surfaceRaised).testTag("fitness_body_steps").clearAndSetSemantics { contentDescription = described }.padding(12.dp)) {
        CardLabel(stringResource(Res.string.fitness_body_steps))
        if (steps == null || today == null) {
            Text(stringResource(Res.string.fitness_body_not_shared), style = type.body, color = colors.textFaint, modifier = Modifier.padding(top = 6.dp))
            return@Column
        }
        Text(today, style = type.title, color = colors.text, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(8.dp))
        val most = steps.days.maxOf { it.steps }.coerceAtLeast(1)
        Row(Modifier.fillMaxWidth().height(34.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
            steps.days.forEachIndexed { index, day ->
                val isToday = index == steps.days.lastIndex
                Box(
                    Modifier
                        .weight(1f)
                        // A day with nothing still has a foot, so the week reads as seven days.
                        .fillMaxHeight((day.steps.toFloat() / most).coerceAtLeast(0.06f))
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (isToday) colors.amber else colors.textFaint.copy(alpha = 0.45f)),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            usual?.let { stringResource(Res.string.fitness_body_steps_average, it) } ?: stringResource(Res.string.fitness_body_steps_no_average),
            style = type.label,
            color = colors.textFaint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * Last night: how long was slept, the night as a bar of its stages (the deeper the sleep the
 * fuller the colour, and the time awake left dim), when it began and ended, and what a night
 * usually comes to.
 */
@Composable
private fun SleepTile(sleep: SleepBoard?, modifier: Modifier = Modifier) {
    val colors = FitnessTheme.colors
    val type = FitnessTheme.type
    val night = sleep?.lastNight
    val slept = night?.let { FitnessFormat.duration(it.asleepSeconds).resolve() }
    val described = when {
        sleep == null -> stringResource(Res.string.fitness_body_not_shared)

        night == null || slept == null -> stringResource(Res.string.fitness_body_sleep_none)

        night.staged -> stringResource(
            Res.string.fitness_body_sleep_described_stages,
            slept,
            FitnessFormat.duration(night.deepSeconds).resolve(),
            FitnessFormat.duration(night.lightSeconds).resolve(),
            FitnessFormat.duration(night.remSeconds).resolve(),
            FitnessFormat.duration(night.awakeSeconds).resolve(),
        )

        else -> stringResource(Res.string.fitness_body_sleep_described, slept)
    }
    Column(modifier.clip(TileShape).background(colors.surfaceRaised).testTag("fitness_body_sleep").clearAndSetSemantics { contentDescription = described }.padding(12.dp)) {
        CardLabel(stringResource(Res.string.fitness_body_sleep))
        if (sleep == null) {
            Text(stringResource(Res.string.fitness_body_not_shared), style = type.body, color = colors.textFaint, modifier = Modifier.padding(top = 6.dp))
            return@Column
        }
        if (night == null || slept == null) {
            Text(stringResource(Res.string.fitness_body_sleep_none), style = type.body, color = colors.textFaint, modifier = Modifier.padding(top = 6.dp))
        } else {
            Text(slept, style = type.title, color = colors.text, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(8.dp))
            StageBar(night)
            Text(
                stringResource(
                    Res.string.fitness_body_sleep_span,
                    FitnessFormat.timeOfDay(night.startEpochSeconds, night.startUtcOffsetSeconds).resolve(),
                    FitnessFormat.timeOfDay(night.endEpochSeconds, night.utcOffsetSeconds).resolve(),
                ),
                style = type.label,
                color = colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        sleep.averageSeconds?.let {
            Text(
                stringResource(Res.string.fitness_body_sleep_average, FitnessFormat.duration(it).resolve()),
                style = type.label,
                color = colors.textFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** A night's stages in proportion, deepest first: deep, REM, light, then the time awake. A night with no stages is one bar. */
@Composable
private fun StageBar(night: SleepNight, modifier: Modifier = Modifier) {
    val colors = FitnessTheme.colors
    val parts = if (night.staged) {
        listOf(night.deepSeconds to colors.violet, night.remSeconds to colors.violet.copy(alpha = 0.62f), night.lightSeconds to colors.violet.copy(alpha = 0.32f), night.awakeSeconds to colors.hairline)
    } else {
        listOf(night.asleepSeconds to colors.violet.copy(alpha = 0.62f))
    }
    Row(modifier.fillMaxWidth().height(10.dp).clip(CircleShape), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        parts.filter { it.first > 0 }.forEach { (seconds, color) -> Box(Modifier.weight(seconds.toFloat()).fillMaxHeight().background(color)) }
    }
}

/**
 * What the app reads from the health store and why, as the store's own settings link to it and
 * as Android requires before it will show the permission sheet at all. A page of its own, shown
 * outside the app (see `HealthRationaleActivity`), so it brings the fitness screens' colours and
 * type with it.
 */
@Composable
fun HealthRationale(onDone: () -> Unit, modifier: Modifier = Modifier) {
    val palette = remember { FitnessPalette() }
    val fontFamily = albertSansFontFamily()
    val typography = remember(fontFamily) { FitnessTypography(fontFamily) }
    CompositionLocalProvider(LocalFitnessPalette provides palette, LocalFitnessTypography provides typography) {
        val colors = FitnessTheme.colors
        val type = FitnessTheme.type
        Column(
            modifier.fillMaxSize().background(colors.background).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = FitnessGutter, vertical = 20.dp).testTag("fitness_health_rationale"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(Res.string.fitness_body_rationale_title), style = type.hero, color = colors.text)
            Text(stringResource(Res.string.fitness_body_rationale_intro), style = type.body, color = colors.textMuted)
            FitnessCard {
                listOf(
                    Res.string.fitness_body_rationale_steps,
                    Res.string.fitness_body_rationale_sleep,
                    Res.string.fitness_body_rationale_heart,
                    Res.string.fitness_body_rationale_runs,
                ).forEachIndexed { index, line ->
                    if (index > 0) Spacer(Modifier.height(10.dp))
                    Text(stringResource(line), style = type.body, color = colors.text)
                }
            }
            Text(stringResource(Res.string.fitness_body_rationale_privacy), style = type.body, color = colors.textMuted)
            Text(stringResource(Res.string.fitness_body_rationale_change), style = type.body, color = colors.textMuted)
            Spacer(Modifier.height(4.dp))
            ForgeButton(stringResource(Res.string.fitness_body_rationale_done), onDone, Modifier.fillMaxWidth().testTag("fitness_health_rationale_done"), icon = Icons.Filled.Check, height = 48.dp)
        }
    }
}
