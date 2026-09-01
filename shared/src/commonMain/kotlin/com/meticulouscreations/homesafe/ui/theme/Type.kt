package com.meticulouscreations.homesafe.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.albert_sans_medium
import homesafe.shared.generated.resources.albert_sans_regular
import homesafe.shared.generated.resources.albert_sans_semibold
import homesafe.shared.generated.resources.fraunces_semibold
import org.jetbrains.compose.resources.Font

@Composable
fun frauncesFontFamily(): FontFamily = FontFamily(
    Font(Res.font.fraunces_semibold, FontWeight.SemiBold),
)

@Composable
fun albertSansFontFamily(): FontFamily = FontFamily(
    Font(Res.font.albert_sans_regular, FontWeight.Normal),
    Font(Res.font.albert_sans_medium, FontWeight.Medium),
    Font(Res.font.albert_sans_semibold, FontWeight.SemiBold),
)

/**
 * Maps the Stitch design system's named text styles onto Material 3's type scale.
 * Only the roles actually used by the current screens are overridden; everything
 * else falls back to Material 3 defaults.
 */
@Composable
fun frigateTypography(): Typography {
    val fraunces = frauncesFontFamily()
    val albertSans = albertSansFontFamily()
    val base = Typography()
    return base.copy(
        // display-lg
        displayLarge = TextStyle(
            fontFamily = fraunces,
            fontWeight = FontWeight.SemiBold,
            fontSize = 32.sp,
            lineHeight = 38.4.sp,
            letterSpacing = (-0.02).em,
        ),
        // headline-md
        headlineMedium = TextStyle(
            fontFamily = fraunces,
            fontWeight = FontWeight.SemiBold,
            fontSize = 28.sp,
            lineHeight = 36.4.sp,
        ),
        // headline-sm
        headlineSmall = TextStyle(
            fontFamily = fraunces,
            fontWeight = FontWeight.SemiBold,
            fontSize = 22.sp,
            lineHeight = 28.6.sp,
        ),
        // body-lg
        bodyLarge = TextStyle(
            fontFamily = albertSans,
            fontWeight = FontWeight.Normal,
            fontSize = 18.sp,
            lineHeight = 27.sp,
        ),
        // body-md
        bodyMedium = TextStyle(
            fontFamily = albertSans,
            fontWeight = FontWeight.Normal,
            fontSize = 16.sp,
            lineHeight = 24.sp,
        ),
        // button-md (Material's button-text role)
        labelLarge = TextStyle(
            fontFamily = albertSans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            lineHeight = 18.sp,
            letterSpacing = 0.01.em,
        ),
        // label-md
        labelMedium = TextStyle(
            fontFamily = albertSans,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            lineHeight = 18.2.sp,
            letterSpacing = 0.02.em,
        ),
        // label-sm
        labelSmall = TextStyle(
            fontFamily = albertSans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp,
            lineHeight = 13.2.sp,
            letterSpacing = 0.05.em,
        ),
    )
}
