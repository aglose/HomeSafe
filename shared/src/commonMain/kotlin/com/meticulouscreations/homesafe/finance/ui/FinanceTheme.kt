// The palette and type below are a theme's own locals, as MaterialTheme's are: every finance
// screen reads them and none passes them.
@file:Suppress("ktlint:compose:compositionlocal-allowlist")

package com.meticulouscreations.homesafe.finance.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.meticulouscreations.homesafe.finance.domain.Signal

/**
 * The finance app's own palette: Robinhood's dark mode — true black, one neon green for money
 * made and one hot orange-red for money lost — so it reads as a different app from the camera
 * screens' warm greys the moment it opens, while the type stays the app's own.
 */
@Immutable
data class FinancePalette(
    val background: Color = Color(0xFF000000),
    val surface: Color = Color(0xFF0E0F0E),
    val surfaceRaised: Color = Color(0xFF17191A),
    val hairline: Color = Color(0xFF242628),
    val textPrimary: Color = Color(0xFFFFFFFF),
    val textSecondary: Color = Color(0xFF9BA1A6),
    /** At least 4.5:1 on the background and on cards: it carries the small print and the chart axes. */
    val textTertiary: Color = Color(0xFF777E82),
    val gain: Color = Color(0xFF00C805),
    val loss: Color = Color(0xFFFF5000),
    /** Robinhood's newer neon, for highlights that aren't up or down. */
    val accent: Color = Color(0xFFCCFF00),
    val watch: Color = Color(0xFFFFB800),
    val cool: Color = Color(0xFF5AC8FA),
    val violet: Color = Color(0xFFB18CFF),
    /** The categorical set, in order, for donuts and stacked bars. */
    val categorical: List<Color> = listOf(
        Color(0xFF00C805),
        Color(0xFF5AC8FA),
        Color(0xFFCCFF00),
        Color(0xFFB18CFF),
        Color(0xFFFFB800),
        Color(0xFFFF7AB6),
        Color(0xFF3DDBD9),
        Color(0xFFFF5000),
    ),
) {
    fun direction(change: Double): Color = if (change >= 0) gain else loss

    fun signal(signal: Signal?): Color = when (signal) {
        Signal.CALM -> gain
        Signal.WATCH -> watch
        Signal.DANGER -> loss
        null -> textSecondary
    }
}

val LocalFinancePalette = staticCompositionLocalOf { FinancePalette() }

/** Lining, equal-width figures, so a rolling price doesn't jiggle sideways. */
internal const val TABULAR = "tnum"

/** The finance screens' type, in the app's own sans with tabular figures wherever numbers sit. */
@Immutable
class FinanceTypography(family: FontFamily) {
    val hero = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 40.sp, lineHeight = 46.sp, letterSpacing = (-0.02).em, fontFeatureSettings = TABULAR)
    val title = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.01).em, fontFeatureSettings = TABULAR)
    val section = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 24.sp)
    val body = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 21.sp)
    val bodyStrong = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 21.sp, fontFeatureSettings = TABULAR)
    val label = TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 17.sp, fontFeatureSettings = TABULAR)
    val micro = TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.04.em, fontFeatureSettings = TABULAR)
}

val LocalFinanceTypography = staticCompositionLocalOf { FinanceTypography(FontFamily.Default) }

object FinanceTheme {
    val colors: FinancePalette
        @Composable @ReadOnlyComposable
        get() = LocalFinancePalette.current

    val type: FinanceTypography
        @Composable @ReadOnlyComposable
        get() = LocalFinanceTypography.current
}
