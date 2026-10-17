// Public on purpose: :androidApp's screenshot tests call this from another module (see HomePreviews.kt).
@file:Suppress("ktlint:compose:preview-public-check")

package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.appicon.AppIcon
import com.meticulouscreations.homesafe.ui.theme.FrigateTheme

/** The "App icon" row's fold: every icon the home screen can show, the one in use ringed. As wide as the fold on a phone. */
@Preview(name = "App icons")
@Composable
fun AppIconOptionsPreview() {
    FrigateTheme {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            AppIconOptions(icon = AppIcon.DEFAULT, onIcon = {}, modifier = Modifier.width(364.dp).padding(16.dp))
        }
    }
}
