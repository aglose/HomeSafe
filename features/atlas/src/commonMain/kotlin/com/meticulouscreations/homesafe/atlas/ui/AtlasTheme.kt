// The palette, type and shader switch below are a theme's own locals, as MaterialTheme's are:
// every atlas screen reads them and none passes them.
@file:Suppress("ktlint:compose:compositionlocal-allowlist")

package com.meticulouscreations.homesafe.atlas.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.meticulouscreations.homesafe.atlas.domain.TravelMode
import com.meticulouscreations.homesafe.atlas.domain.VisitRole

/**
 * The atlas app's colours: the Earth at night. Near-black with a little blue in it, and on it
 * the colours of light seen from above: the amber of roads driven often, white where they are
 * driven daily, cyan for the places stopped at, violet for the air.
 */
@Immutable
data class AtlasPalette(
    val background: Color = Color(0xFF04060B),
    val surface: Color = Color(0xFF0C1019),
    val surfaceRaised: Color = Color(0xFF151B28),
    val hairline: Color = Color(0x1FFFFFFF),
    val text: Color = Color(0xFFFFFFFF),
    val textMuted: Color = Color(0xC7FFFFFF),
    /** The small print: still 4.5:1 on a card. */
    val textFaint: Color = Color(0x94FFFFFF),
    /** The paths: where the app's own light comes from. */
    val trail: Color = Color(0xFFFFB454),
    val ember: Color = Color(0xFFFF6B3D),
    val place: Color = Color(0xFF5CE1E6),
    val air: Color = Color(0xFFB18CFF),
    val home: Color = Color(0xFFFFD86B),
    val work: Color = Color(0xFF6FA8FF),
    val good: Color = Color(0xFF5BE38C),
    val danger: Color = Color(0xFFFF5D5D),
) {
    fun mode(mode: TravelMode): Color = when (mode) {
        TravelMode.WALK, TravelMode.RUN -> good
        TravelMode.CYCLE, TravelMode.SKI -> Color(0xFF3CD6B4)
        TravelMode.DRIVE, TravelMode.MOTORCYCLE -> trail
        TravelMode.BUS, TravelMode.TRAIN, TravelMode.SUBWAY, TravelMode.TRAM -> work
        TravelMode.FLY -> air
        TravelMode.FERRY, TravelMode.BOAT -> place
        TravelMode.OTHER -> Color(0xFF8E97A8)
    }

    fun role(role: VisitRole): Color = when (role) {
        VisitRole.HOME -> home
        VisitRole.WORK -> work
        VisitRole.OTHER -> place
    }

    /** From nothing to the most there is, for the calendar's squares and the rhythm grids: dark, through the trail's amber, to white-hot. */
    fun heat(amount: Float): Color {
        val t = amount.coerceIn(0f, 1f)
        return when {
            t <= 0f -> surfaceRaised
            t < 0.5f -> lerp(Color(0xFF3A2A4D), ember, t / 0.5f)
            t < 0.85f -> lerp(ember, trail, (t - 0.5f) / 0.35f)
            else -> lerp(trail, Color(0xFFFFF4D6), (t - 0.85f) / 0.15f)
        }
    }

    private fun lerp(a: Color, b: Color, t: Float): Color = androidx.compose.ui.graphics.lerp(a, b, t.coerceIn(0f, 1f))
}

val LocalAtlasPalette = staticCompositionLocalOf { AtlasPalette() }

private const val TABULAR = "tnum"

/** The atlas screens' type, in the app's own sans, with numerals that line up in a column. */
@Immutable
class AtlasTypography(family: FontFamily) {
    val hero = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.02).em, fontFeatureSettings = TABULAR)
    val title = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.01).em, fontFeatureSettings = TABULAR)
    val headline = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp, fontFeatureSettings = TABULAR)
    val body = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 21.sp)
    val bodyStrong = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 21.sp, fontFeatureSettings = TABULAR)
    val label = TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 17.sp, fontFeatureSettings = TABULAR)
    val micro = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.08.em, fontFeatureSettings = TABULAR)
}

val LocalAtlasTypography = staticCompositionLocalOf { AtlasTypography(FontFamily.Default) }

/**
 * Whether the map's glow and the backdrops are drawn by their shaders. Always, in the app. A UI
 * test that isn't about them turns it off: off a GPU a shader runs on the CPU at seconds a frame,
 * and the plain fills that stand in say the same to a test.
 */
val LocalAtlasShaders = staticCompositionLocalOf { true }

/** Distances in kilometres rather than miles. */
val LocalAtlasMetric = staticCompositionLocalOf { false }

object AtlasTheme {
    val colors: AtlasPalette
        @Composable @ReadOnlyComposable
        get() = LocalAtlasPalette.current

    val type: AtlasTypography
        @Composable @ReadOnlyComposable
        get() = LocalAtlasTypography.current
}

internal val AtlasCardShape = RoundedCornerShape(24.dp)
internal val AtlasGutter = 16.dp

/** What every block of the atlas screens sits on. With [title] it opens with a small capital label. */
@Composable
internal fun AtlasCard(modifier: Modifier = Modifier, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val colors = AtlasTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(AtlasCardShape)
            .background(colors.surface)
            .border(1.dp, colors.hairline, AtlasCardShape)
            .padding(18.dp),
    ) {
        if (title != null) {
            Text(title.uppercase(), style = AtlasTheme.type.micro, color = colors.textFaint, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(12.dp))
        }
        content()
    }
}
