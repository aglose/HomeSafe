package com.meticulouscreations.homesafe.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

@Composable
fun FrigateTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalFrigateExtraColors provides FrigateDarkExtraColors) {
        MaterialTheme(
            colorScheme = FrigateDarkColorScheme,
            typography = frigateTypography(),
            content = content,
        )
    }
}
