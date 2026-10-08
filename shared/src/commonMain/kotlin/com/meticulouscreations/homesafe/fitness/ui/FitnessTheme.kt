// The palette, type and shader switch below are a theme's own locals, as MaterialTheme's are:
// every fitness screen reads them and none passes them.
@file:Suppress("ktlint:compose:compositionlocal-allowlist")

package com.meticulouscreations.homesafe.fitness.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.meticulouscreations.homesafe.fitness.domain.HeartZone
import com.meticulouscreations.homesafe.fitness.domain.PhaseKind
import com.meticulouscreations.homesafe.fitness.domain.VolumeStatus
import com.meticulouscreations.homesafe.fitness.domain.WorkoutFocus

/**
 * The fitness app's colours: a forge. Near-black iron, and on it the colours metal goes as it
 * heats, ember through amber to white-gold, with one cold blue for a cut. Each workout has its
 * own heat ([focus]) and each phase its own light ([phase]), so the screen says which day and
 * which stretch of the year it is before a word is read.
 */
@Immutable
data class FitnessPalette(
    val background: Color = Color(0xFF07080A),
    val surface: Color = Color(0xFF111318),
    val surfaceRaised: Color = Color(0xFF1B1E25),
    val hairline: Color = Color(0x1FFFFFFF),
    val text: Color = Color(0xFFFFFFFF),
    val textMuted: Color = Color(0xC7FFFFFF),
    /** The small print: still 4.5:1 on a card. */
    val textFaint: Color = Color(0x94FFFFFF),
    val ember: Color = Color(0xFFFF5A1F),
    val amber: Color = Color(0xFFFFB238),
    /** A record. */
    val gold: Color = Color(0xFFFFD86B),
    val ice: Color = Color(0xFF45D4FF),
    val violet: Color = Color(0xFFA78BFF),
    val good: Color = Color(0xFF5BE38C),
    val danger: Color = Color(0xFFFF5D5D),
) {
    /** A workout's two colours, the brighter first. */
    fun focus(focus: WorkoutFocus): Pair<Color, Color> = when (focus) {
        WorkoutFocus.CHEST -> Color(0xFFFF6A2B) to Color(0xFFB3123A)
        WorkoutFocus.BACK -> Color(0xFF3FB6FF) to Color(0xFF2B2FCB)
        WorkoutFocus.LEGS -> Color(0xFF7BEA5B) to Color(0xFF0E8F7A)
        WorkoutFocus.SHOULDERS -> Color(0xFFFFC23D) to Color(0xFFD9541E)
        WorkoutFocus.ARMS -> Color(0xFFC08BFF) to Color(0xFF5B2FD6)
    }

    /** A phase's two colours: a bulk burns, a cut is cold, maintenance sits between. */
    fun phase(kind: PhaseKind): Pair<Color, Color> = when (kind) {
        PhaseKind.BULK -> ember to Color(0xFFB3123A)
        PhaseKind.CUT -> ice to Color(0xFF2B4BCB)
        PhaseKind.MAINTAIN -> violet to Color(0xFF14808F)
    }

    /**
     * A heart-rate zone's colour, the ones heart-rate monitors use: grey, blue, green, orange,
     * red as the effort rises. Under zone 1 (null) there is none to speak of.
     */
    fun zone(zone: HeartZone?): Color = when (zone) {
        null -> textFaint
        HeartZone.VERY_LIGHT -> Color(0xFF9FB3C8)
        HeartZone.LIGHT -> ice
        HeartZone.MODERATE -> good
        HeartZone.HARD -> amber
        HeartZone.MAXIMUM -> danger
    }

    fun volume(status: VolumeStatus): Color = when (status) {
        VolumeStatus.NONE -> Color(0xFF2A2E37)
        VolumeStatus.LOW -> Color(0xFF7B5BD6)
        VolumeStatus.BUILDING -> amber
        VolumeStatus.ON_TARGET -> good
        VolumeStatus.HIGH -> ember
    }
}

val LocalFitnessPalette = staticCompositionLocalOf { FitnessPalette() }

private const val TABULAR = "tnum"

/** The fitness screens' type, in the app's own sans: big tabular numerals, since the numbers are what is read at arm's length mid-set. */
@Immutable
class FitnessTypography(family: FontFamily) {
    /** The weight and reps being logged. */
    val numeral = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 52.sp, lineHeight = 58.sp, letterSpacing = (-0.03).em, fontFeatureSettings = TABULAR)
    val hero = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.02).em, fontFeatureSettings = TABULAR)
    val title = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.01).em, fontFeatureSettings = TABULAR)
    val headline = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp, fontFeatureSettings = TABULAR)
    val body = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 21.sp)
    val bodyStrong = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 21.sp, fontFeatureSettings = TABULAR)
    val label = TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 17.sp, fontFeatureSettings = TABULAR)
    val micro = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.08.em, fontFeatureSettings = TABULAR)
}

val LocalFitnessTypography = staticCompositionLocalOf { FitnessTypography(FontFamily.Default) }

/**
 * Whether the forge, the fibres, the rings and the record burst are drawn by their shaders.
 * Always, in the app. A UI test that isn't about them turns it off: off a GPU a shader runs on
 * the CPU at seconds a frame, and the plain gradients that stand in say the same to a test.
 */
val LocalFitnessShaders = staticCompositionLocalOf { true }

object FitnessTheme {
    val colors: FitnessPalette
        @Composable @ReadOnlyComposable
        get() = LocalFitnessPalette.current

    val type: FitnessTypography
        @Composable @ReadOnlyComposable
        get() = LocalFitnessTypography.current
}

internal val FitnessCardShape = RoundedCornerShape(24.dp)
internal val FitnessGutter = 16.dp

/** A slab of iron: what every block of the fitness screens sits on. With [title] it opens with a small capital label. */
@Composable
internal fun FitnessCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = FitnessTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(FitnessCardShape)
            .background(colors.surface)
            .border(1.dp, colors.hairline, FitnessCardShape)
            .padding(padding),
    ) {
        if (title != null) CardLabel(title, Modifier.padding(bottom = 12.dp))
        content()
    }
}

/** A card's small capital heading. */
@Composable
internal fun CardLabel(title: String, modifier: Modifier = Modifier, color: Color = FitnessTheme.colors.textFaint) {
    Text(title.uppercase(), style = FitnessTheme.type.micro, color = color, maxLines = 1, modifier = modifier.semantics { heading() })
}
