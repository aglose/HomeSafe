package com.meticulouscreations.homesafe.ui.preview

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import com.meticulouscreations.homesafe.domain.model.RecordingSegment
import com.meticulouscreations.homesafe.ui.theme.FrigateTheme

/** Wraps preview content in the app's theme and background, matching how screens render for real. */
@Composable
fun FrigatePreview(content: @Composable () -> Unit) {
    FrigateTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            content()
        }
    }
}

/**
 * Provides a real [SharedTransitionScope] and [LocalNavAnimatedContentScope] for previewing
 * composables that participate in the Home <-> Camera Detail shared-element transition, without
 * requiring an actual Navigation 3 back stack.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SharedTransitionPreview(content: @Composable SharedTransitionScope.() -> Unit) {
    SharedTransitionLayout {
        AnimatedContent(targetState = Unit, label = "preview") {
            CompositionLocalProvider(LocalNavAnimatedContentScope provides this) {
                content()
            }
        }
    }
}

val previewNowEpochSeconds = 1_732_650_000.0

/** A few hours of recorded coverage ending "now", with a couple of motion/object spikes. */
val previewRecordingSegments: List<RecordingSegment> = buildList {
    var t = previewNowEpochSeconds - 3 * 60 * 60
    while (t < previewNowEpochSeconds) {
        val motion = when {
            t > previewNowEpochSeconds - 600 -> 40
            t > previewNowEpochSeconds - 5_400 && t < previewNowEpochSeconds - 5_000 -> 25
            else -> 0
        }
        val objects = if (motion > 30) 3 else 0
        add(RecordingSegment(startEpochSeconds = t, endEpochSeconds = t + 10.0, motion = motion, objects = objects))
        t += 10.0
    }
}
