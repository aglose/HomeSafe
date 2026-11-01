// The palette and type below are a theme's own locals, as MaterialTheme's are: every charging screen
// reads them and none passes them.
@file:Suppress("ktlint:compose:compositionlocal-allowlist")

package com.meticulouscreations.homesafe.charging.ui

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
import com.meticulouscreations.homesafe.charging.domain.ChargePlace
import com.meticulouscreations.homesafe.charging.domain.RatePeriod

/**
 * The charging app's colours: a garage at night, lit by a charge port's green. Green is home and
 * cheap, blue the charging done at a fast charger, violet anywhere else, and amber what costs most.
 */
@Immutable
data class ChargingPalette(
    val background: Color = Color(0xFF060A09),
    val surface: Color = Color(0xFF0D1412),
    val surfaceRaised: Color = Color(0xFF17211E),
    val hairline: Color = Color(0x1FFFFFFF),
    val text: Color = Color(0xFFFFFFFF),
    val textMuted: Color = Color(0xC7FFFFFF),
    /** The small print: still 4.5:1 on a card. */
    val textFaint: Color = Color(0x94FFFFFF),
    /** A charge port's light: where the app's own colour comes from. */
    val home: Color = Color(0xFF4CE08A),
    val fast: Color = Color(0xFF5CB8FF),
    val other: Color = Color(0xFFB79BFF),
    val peak: Color = Color(0xFFFFB454),
    val danger: Color = Color(0xFFFF6B5D),
) {
    fun place(place: ChargePlace): Color = when (place) {
        ChargePlace.HOME -> home
        ChargePlace.SUPERCHARGER, ChargePlace.FAST -> fast
        ChargePlace.OTHER -> other
    }

    fun period(period: RatePeriod?): Color = if (period == RatePeriod.PEAK) peak else home
}

val LocalChargingPalette = staticCompositionLocalOf { ChargingPalette() }

private const val TABULAR = "tnum"

/** The charging screens' type, in the app's own sans, with numerals that line up in a column. */
@Immutable
class ChargingTypography(family: FontFamily) {
    val hero = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 44.sp, lineHeight = 50.sp, letterSpacing = (-0.02).em, fontFeatureSettings = TABULAR)
    val title = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.01).em, fontFeatureSettings = TABULAR)
    val headline = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp, fontFeatureSettings = TABULAR)
    val body = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 21.sp, fontFeatureSettings = TABULAR)
    val bodyStrong = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 21.sp, fontFeatureSettings = TABULAR)
    val label = TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 17.sp, fontFeatureSettings = TABULAR)
    val micro = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.08.em, fontFeatureSettings = TABULAR)
}

val LocalChargingTypography = staticCompositionLocalOf { ChargingTypography(FontFamily.Default) }

object ChargingTheme {
    val colors: ChargingPalette
        @Composable @ReadOnlyComposable
        get() = LocalChargingPalette.current

    val type: ChargingTypography
        @Composable @ReadOnlyComposable
        get() = LocalChargingTypography.current
}

internal val ChargingCardShape = RoundedCornerShape(24.dp)

/** What every block of the charging screens sits on. With [title] it opens with a small capital label. */
@Composable
internal fun ChargingCard(modifier: Modifier = Modifier, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val colors = ChargingTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(ChargingCardShape)
            .background(colors.surface)
            .border(1.dp, colors.hairline, ChargingCardShape)
            .padding(18.dp),
    ) {
        if (title != null) {
            Text(title.uppercase(), style = ChargingTheme.type.micro, color = colors.textFaint, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(12.dp))
        }
        content()
    }
}
