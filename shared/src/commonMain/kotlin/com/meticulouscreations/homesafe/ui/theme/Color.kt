package com.meticulouscreations.homesafe.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

val FrigateDarkColorScheme = darkColorScheme(
    primary = Color(0xFFFFB59D),
    onPrimary = Color(0xFF5C1901),
    primaryContainer = Color(0xFFD77655),
    onPrimaryContainer = Color(0xFF511400),
    inversePrimary = Color(0xFF994629),
    secondary = Color(0xFFB9CCAC),
    onSecondary = Color(0xFF25351E),
    secondaryContainer = Color(0xFF3D4E35),
    onSecondaryContainer = Color(0xFFABBE9F),
    tertiary = Color(0xFFC9C6C0),
    onTertiary = Color(0xFF31302C),
    tertiaryContainer = Color(0xFF93908B),
    onTertiaryContainer = Color(0xFF2B2A26),
    background = Color(0xFF131312),
    onBackground = Color(0xFFE5E2E0),
    surface = Color(0xFF1C1C1A),
    onSurface = Color(0xFFE5E2E0),
    surfaceVariant = Color(0xFF353533),
    onSurfaceVariant = Color(0xFFDBC1B9),
    surfaceTint = Color(0xFFFFB59D),
    inverseSurface = Color(0xFFE5E2E0),
    inverseOnSurface = Color(0xFF31302F),
    error = Color(0xFFD9534F),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFFA38C85),
    outlineVariant = Color(0xFF55433D),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF3A3938),
    surfaceDim = Color(0xFF131312),
    surfaceContainer = Color(0xFF20201E),
    surfaceContainerHigh = Color(0xFF2A2A29),
    surfaceContainerHighest = Color(0xFF353533),
    surfaceContainerLow = Color(0xFF1C1C1A),
    surfaceContainerLowest = Color(0xFF0E0E0D),
    primaryFixed = Color(0xFFFFDBD0),
    primaryFixedDim = Color(0xFFFFB59D),
    onPrimaryFixed = Color(0xFF390C00),
    onPrimaryFixedVariant = Color(0xFF7A2F14),
    secondaryFixed = Color(0xFFD5E9C7),
    secondaryFixedDim = Color(0xFFB9CCAC),
    onSecondaryFixed = Color(0xFF111F0B),
    onSecondaryFixedVariant = Color(0xFF3B4B33),
    tertiaryFixed = Color(0xFFE6E2DC),
    tertiaryFixedDim = Color(0xFFC9C6C0),
    onTertiaryFixed = Color(0xFF1C1C18),
    onTertiaryFixedVariant = Color(0xFF484742),
)

/** Tokens from the Stitch design system that don't have a Material 3 color-role equivalent. */
@Immutable
data class FrigateExtraColors(
    val textPrimary: Color,
    val glassFill: Color,
)

val FrigateDarkExtraColors = FrigateExtraColors(
    textPrimary = Color(0xFFF4F3EF),
    glassFill = Color(0xFF1C1C1A).copy(alpha = 0.6f),
)

val LocalFrigateExtraColors = staticCompositionLocalOf { FrigateDarkExtraColors }
