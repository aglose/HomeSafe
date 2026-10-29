package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.ui.theme.FrigateTheme

/*
 * What sits under a Nest camera's video: the button that opens Google Home to talk through the
 * camera, and the line on why the page has nothing else. Under a stand-in for the video, and
 * again on a small phone with the text enlarged.
 */

@Preview(name = "Nest camera footer", widthDp = 360)
@Composable
private fun NestCameraFooterPreview() {
    NestCameraFooterSample(widthDp = 360)
}

@Preview(name = "Nest camera footer, small phone, large text", widthDp = 320, fontScale = 1.5f)
@Composable
private fun NestCameraFooterNarrowPreview() {
    NestCameraFooterSample(widthDp = 320)
}

@Composable
private fun NestCameraFooterSample(widthDp: Int) {
    FrigateTheme {
        Column(modifier = Modifier.width(widthDp.dp).background(MaterialTheme.colorScheme.background)) {
            Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(MaterialTheme.colorScheme.surfaceContainerHigh))
            NestCameraFooter(onTalk = {})
        }
    }
}
