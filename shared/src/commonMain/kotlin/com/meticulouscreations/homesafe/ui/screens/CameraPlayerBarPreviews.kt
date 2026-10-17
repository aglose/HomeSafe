// Public on purpose: :androidApp's screenshot tests call this from another module (see HomePreviews.kt).
@file:Suppress("ktlint:compose:preview-public-check")

package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import com.meticulouscreations.homesafe.domain.model.StreamQuality
import com.meticulouscreations.homesafe.ui.theme.FrigateTheme

/*
 * The bar under a camera's player, under a stand-in for the video, in the two states that look
 * most different: at the live edge with sound on, and watching a recording on the silent SD stream.
 */

@Preview(name = "Camera player bar", widthDp = PREVIEW_WIDTH_DP)
@Composable
fun CameraPlayerBarPreview() {
    FrigateTheme {
        Column(modifier = Modifier.width(PREVIEW_WIDTH_DP.dp).background(MaterialTheme.colorScheme.background), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            PlayerBarSample(isLive = true, quality = StreamQuality.HIGH, isMuted = false, hasAudio = true)
            PlayerBarSample(isLive = false, quality = StreamQuality.LOW, isMuted = true, hasAudio = false)
        }
    }
}

@Composable
private fun PlayerBarSample(isLive: Boolean, quality: StreamQuality, isMuted: Boolean, hasAudio: Boolean) {
    Column {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(MaterialTheme.colorScheme.surfaceContainerHigh))
        CameraQuickActions(
            displayName = "Front Door",
            quality = quality,
            hasQualityChoice = true,
            isMuted = isMuted,
            hasAudio = hasAudio,
            onQualitySelect = {},
            onQualityUnavailable = {},
            onToggleSound = {},
            onClip = {},
        ) {
            LivePill(isLive = isLive, onClick = {})
            if (!isLive) BehindLiveReadout(playheadEpochSeconds = SAMPLE_NOW - 125.0, nowEpochSeconds = SAMPLE_NOW, onVideo = false)
        }
    }
}

private const val PREVIEW_WIDTH_DP = 360

/** 8:44 PM UTC on a day in October 2026, sample data for the readout. */
private const val SAMPLE_NOW = 1_792_269_840.0
