package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.ui.theme.FrigateTheme

/*
 * What sits under a Nest camera's video: the button that opens Google Home to talk through the
 * camera, and the line on why the page has nothing else. Under a stand-in for the video, and
 * again on a small phone with the text enlarged. Then the page under the header at rest and
 * zoomed, when the video has taken it over down to the nav bar and pushed the footer away, for a
 * wide picture and a tall one shown whole.
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

@Preview(name = "Nest camera page, at rest", widthDp = 360, heightDp = 640)
@Composable
private fun NestVideoAtRestPreview() {
    NestVideoAndFooterSample(takeover = 0f, boxAspect = 16f / 9f)
}

@Preview(name = "Nest camera page, zoomed", widthDp = 360, heightDp = 640)
@Composable
private fun NestVideoZoomedPreview() {
    NestVideoAndFooterSample(takeover = 1f, boxAspect = 16f / 9f)
}

@Preview(name = "Nest camera page, tall picture, at rest", widthDp = 360, heightDp = 640)
@Composable
private fun NestVideoTallAtRestPreview() {
    NestVideoAndFooterSample(takeover = 0f, boxAspect = 3f / 4f)
}

@Preview(name = "Nest camera page, tall picture, zoomed", widthDp = 360, heightDp = 640)
@Composable
private fun NestVideoTallZoomedPreview() {
    NestVideoAndFooterSample(takeover = 1f, boxAspect = 3f / 4f)
}

/** The page under a stand-in header, with a gradient for the picture so its box's edges show. */
@Composable
private fun NestVideoAndFooterSample(takeover: Float, boxAspect: Float) {
    FrigateTheme {
        val restBox = remember { NestVideoRestBox() }
        Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Box(modifier = Modifier.fillMaxWidth().height(72.dp))
            NestVideoAndFooter(
                takeover = { takeover },
                boxAspect = boxAspect,
                bottomInsetPx = 160,
                restBox = restBox,
                backdrop = MaterialTheme.colorScheme.surfaceContainerLowest,
                video = {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .restBox(restBox)
                                .align(Alignment.Center)
                                .background(Brush.linearGradient(listOf(Color(0xFF2E5A88), Color(0xFFB0703C)))),
                        )
                    }
                },
                footer = { NestCameraFooter(onTalk = {}) },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
    }
}
