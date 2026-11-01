// The palette, type and shader switch below are a theme's own locals, as MaterialTheme's are:
// every packages screen reads them and none passes them.
@file:Suppress("ktlint:compose:compositionlocal-allowlist")

package com.meticulouscreations.homesafe.packages.ui

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
import com.meticulouscreations.homesafe.packages.domain.CameraVerdict
import com.meticulouscreations.homesafe.packages.domain.Carrier
import com.meticulouscreations.homesafe.packages.domain.Parcel
import com.meticulouscreations.homesafe.packages.domain.ParcelStage

/**
 * The packages app's colours: a porch at dusk. Warm near-black, the kraft and tape of a box, the
 * cream of a shipping label, and a teal of its own for what the camera saw. Green means it's here
 * and red that something needs a person; violet is for surprises and nothing else.
 */
@Immutable
data class PackagesPalette(
    val background: Color = Color(0xFF0D0A08),
    val surface: Color = Color(0xFF17120E),
    val surfaceRaised: Color = Color(0xFF221B15),
    val hairline: Color = Color(0x24FFE8C8),
    val text: Color = Color(0xFFFFF7EA),
    val textMuted: Color = Color(0xC9FFF0DC),
    /** The small print: still 4.5:1 on a card. */
    val textFaint: Color = Color(0x99FFEBD2),
    val kraft: Color = Color(0xFFC89A5E),
    val tape: Color = Color(0xFFF0C678),
    val label: Color = Color(0xFFF5EAD5),
    val camera: Color = Color(0xFF58DCCB),
    val good: Color = Color(0xFF7BDF96),
    val warn: Color = Color(0xFFFFB457),
    val danger: Color = Color(0xFFFF7468),
    val gift: Color = Color(0xFFC79BFF),
    val giftDeep: Color = Color(0xFF4A2C86),
    val onTheWay: Color = Color(0xFF79B4FF),
) {
    fun stage(stage: ParcelStage): Color = when (stage) {
        ParcelStage.ORDERED -> kraft
        ParcelStage.SHIPPED -> onTheWay
        ParcelStage.OUT_FOR_DELIVERY -> tape
        ParcelStage.DELIVERED -> good
    }

    fun verdict(verdict: CameraVerdict?): Color = when (verdict) {
        CameraVerdict.CONFIRMED -> good
        CameraVerdict.SEEN, CameraVerdict.CAMERA_ONLY -> camera
        CameraVerdict.NOT_SEEN -> warn
        null -> textFaint
    }

    /** Each carrier in its own colour, softened for the dark, as a badge: none is anyone's trademark colour exactly. */
    fun carrier(carrier: Carrier): Color = when (carrier) {
        Carrier.AMAZON -> Color(0xFFFFB454)
        Carrier.UPS -> Color(0xFFD69A55)
        Carrier.USPS -> Color(0xFF6FA8FF)
        Carrier.FEDEX -> Color(0xFFB98CFF)
        Carrier.DHL -> Color(0xFFFFD84D)
        Carrier.ONTRAC -> Color(0xFF58DCCB)
        Carrier.LASERSHIP -> Color(0xFF7BDF96)
        Carrier.VEHO -> Color(0xFFFF8FB1)
        Carrier.OTHER -> Color(0xFFB9AFA3)
    }

    /** From nothing to the most there is, for the hours-of-the-week grid: dark, through kraft, to label cream. */
    fun heat(amount: Float): Color {
        val t = amount.coerceIn(0f, 1f)
        return when {
            t <= 0f -> surfaceRaised
            t < 0.5f -> androidx.compose.ui.graphics.lerp(Color(0xFF4A3524), kraft, t / 0.5f)
            else -> androidx.compose.ui.graphics.lerp(kraft, Color(0xFFFFF0CF), (t - 0.5f) / 0.5f)
        }
    }
}

val LocalPackagesPalette = staticCompositionLocalOf { PackagesPalette() }

private const val TABULAR = "tnum"

/** The packages screens' type, in the app's own sans, with numerals that line up in a column. */
@Immutable
class PackagesTypography(family: FontFamily) {
    val display = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = (-0.025).em, fontFeatureSettings = TABULAR)
    val title = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.01).em, fontFeatureSettings = TABULAR)
    val headline = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp, fontFeatureSettings = TABULAR)
    val body = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 21.sp)
    val bodyStrong = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 21.sp, fontFeatureSettings = TABULAR)
    val label = TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 17.sp, fontFeatureSettings = TABULAR)
    val micro = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.08.em, fontFeatureSettings = TABULAR)
}

val LocalPackagesTypography = staticCompositionLocalOf { PackagesTypography(FontFamily.Default) }

/**
 * Whether the backdrops, the box and the route are drawn by their shaders. Always, in the app. A UI
 * test that isn't about them turns it off: off a GPU a shader runs on the CPU at seconds a frame,
 * and the plain fills that stand in say the same to a test.
 */
val LocalPackagesShaders = staticCompositionLocalOf { true }

object PackagesTheme {
    val colors: PackagesPalette
        @Composable @ReadOnlyComposable
        get() = LocalPackagesPalette.current

    val type: PackagesTypography
        @Composable @ReadOnlyComposable
        get() = LocalPackagesTypography.current
}

internal val PackagesCardShape = RoundedCornerShape(22.dp)

/** What every block of the packages screens sits on. With [title] it opens with a small capital label. */
@Composable
internal fun PackagesCard(modifier: Modifier = Modifier, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val colors = PackagesTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(PackagesCardShape)
            .background(colors.surface)
            .border(1.dp, colors.hairline, PackagesCardShape)
            .padding(18.dp),
    ) {
        if (title != null) {
            Text(title.uppercase(), style = PackagesTheme.type.micro, color = colors.textFaint, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(12.dp))
        }
        content()
    }
}

/** The tint of a parcel's journey, by what state it is in. */
@Composable
@ReadOnlyComposable
internal fun Parcel.tint(): Color {
    val colors = PackagesTheme.colors
    return when {
        masked -> colors.gift
        needsAttention -> colors.warn
        else -> colors.stage(stage)
    }
}
