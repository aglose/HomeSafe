// The palette, type and units below are a theme's own locals, as MaterialTheme's are: every
// weather screen reads them and none passes them.
@file:Suppress("ktlint:compose:compositionlocal-allowlist")

package com.meticulouscreations.homesafe.weather.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.meticulouscreations.homesafe.weather.domain.WeatherUnits

/**
 * The weather app's colours. There is no background among them: the background is the sky,
 * which is anything from noon blue to a thunderstorm at night, so everything here is chosen to
 * sit on any of those. Text is white, in three strengths; cards are a dark glass that deepens
 * whatever is behind them rather than covering it.
 */
@Immutable
data class WeatherPalette(
    val onSky: Color = Color(0xFFFFFFFF),
    val onSkyMuted: Color = Color(0xD1FFFFFF),
    /** The small print: at least 4.5:1 on a card over the brightest sky the shaders draw. */
    val onSkyFaint: Color = Color(0x99FFFFFF),
    val card: Color = Color(0x570A1220),
    /** A card that has to be read against a bright sky with nothing else behind it: the top bar's pills, the nav. */
    val cardSolid: Color = Color(0xB80A1220),
    val cardBorder: Color = Color(0x24FFFFFF),
    val hairline: Color = Color(0x1FFFFFFF),
    /** Opaque, for sheets and pages that cover the sky. */
    val surface: Color = Color(0xFF0C121C),
    val surfaceRaised: Color = Color(0xFF161E2B),
    val accent: Color = Color(0xFF8FD0FF),
    val sun: Color = Color(0xFFFFCB52),
    val rain: Color = Color(0xFF5EB6FF),
    val snow: Color = Color(0xFFEAF2FF),
    val good: Color = Color(0xFF79E2A4),
    val watch: Color = Color(0xFFFFC14D),
    val danger: Color = Color(0xFFFF6B5C),
    val violet: Color = Color(0xFFC08BFF),
) {
    /**
     * A temperature as a colour, the same wherever one is drawn (the ten-day bars, the hourly
     * curve): deep blue at -15 °C through teal and gold to red at 40 °C.
     */
    fun temperature(celsius: Double): Color {
        val stops = TEMPERATURE_STOPS
        val c = celsius.coerceIn(stops.first().first, stops.last().first)
        val i = stops.indexOfLast { it.first <= c }.coerceIn(0, stops.lastIndex - 1)
        val (fromC, from) = stops[i]
        val (toC, to) = stops[i + 1]
        val t = ((c - fromC) / (toC - fromC)).toFloat()
        return Color(from.red + (to.red - from.red) * t, from.green + (to.green - from.green) * t, from.blue + (to.blue - from.blue) * t)
    }

    private companion object {
        val TEMPERATURE_STOPS = listOf(
            -15.0 to Color(0xFF6E7BFF),
            -2.0 to Color(0xFF5AA9FF),
            8.0 to Color(0xFF52D6D0),
            17.0 to Color(0xFF8BE07A),
            24.0 to Color(0xFFFFD84D),
            31.0 to Color(0xFFFF9A3D),
            40.0 to Color(0xFFFF5340),
        )
    }
}

val LocalWeatherPalette = staticCompositionLocalOf { WeatherPalette() }

private const val TABULAR = "tnum"

/**
 * The weather screens' type, in the app's own sans. Whatever sits straight on the sky carries a
 * soft shadow, so it holds up over a white cloud as well as a night.
 */
@Immutable
class WeatherTypography(family: FontFamily) {
    private val lift = Shadow(Color(0x66000000), Offset(0f, 1.5f), 10f)

    /** The temperature. */
    val hero = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 92.sp, lineHeight = 96.sp, letterSpacing = (-0.045).em, fontFeatureSettings = TABULAR, shadow = lift)
    val heroSmall = TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.02).em, fontFeatureSettings = TABULAR)
    val title = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.01).em, shadow = lift)
    val headline = TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 23.sp, shadow = lift)
    val body = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 21.sp)
    val bodyStrong = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 21.sp, fontFeatureSettings = TABULAR)
    val value = TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 26.sp, lineHeight = 31.sp, letterSpacing = (-0.01).em, fontFeatureSettings = TABULAR)
    val label = TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 17.sp, fontFeatureSettings = TABULAR)
    val micro = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.08.em, fontFeatureSettings = TABULAR)
}

val LocalWeatherTypography = staticCompositionLocalOf { WeatherTypography(FontFamily.Default) }

/** The units the reader chose, for every number on the weather screens. */
val LocalWeatherUnits = staticCompositionLocalOf { WeatherUnits() }

object WeatherTheme {
    val colors: WeatherPalette
        @Composable @ReadOnlyComposable
        get() = LocalWeatherPalette.current

    val type: WeatherTypography
        @Composable @ReadOnlyComposable
        get() = LocalWeatherTypography.current

    val units: WeatherUnits
        @Composable @ReadOnlyComposable
        get() = LocalWeatherUnits.current
}

internal val WeatherCardShape = RoundedCornerShape(22.dp)

/**
 * A pane of dark glass over the sky: what every block of the weather screens sits in. With
 * [title] it opens with a small capital label and its icon, the way the system's weather tiles
 * do. [padding] is round the content; 0 lets a strip or a list run to the card's edges.
 */
@Composable
internal fun WeatherCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    icon: ImageVector? = null,
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = WeatherTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(WeatherCardShape)
            .background(colors.card)
            .border(1.dp, colors.cardBorder, WeatherCardShape),
    ) {
        if (title != null) {
            // The label keeps the card's margin even when the content runs edge to edge.
            CardLabel(title, Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp), icon)
            Spacer(Modifier.size(10.dp))
        }
        Column(Modifier.fillMaxWidth().padding(start = padding, end = padding, bottom = padding, top = if (title != null) 0.dp else padding), content = content)
    }
}

/** A card's small capital heading. */
@Composable
internal fun CardLabel(title: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val colors = WeatherTheme.colors
    Row(modifier.semantics { heading() }, verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = colors.onSkyFaint, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(title.uppercase(), style = WeatherTheme.type.micro, color = colors.onSkyFaint, maxLines = 1)
    }
}
