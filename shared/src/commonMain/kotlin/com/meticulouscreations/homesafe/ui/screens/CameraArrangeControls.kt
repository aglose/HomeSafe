package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.domain.model.CameraOrientation
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.camera_tilt
import homesafe.shared.generated.resources.camera_tilt_value
import homesafe.shared.generated.resources.camera_turn_left
import homesafe.shared.generated.resources.camera_turn_right
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * A camera card's controls in Arrange mode, along its bottom edge over the video: a quarter turn
 * either way, and between them the fine tilt for a camera mounted a little crooked, in whole
 * degrees. The picture above turns as they move, so the right way up is found by eye. Turns are
 * saved at once ([onTurn]); the tilt while it moves is only shown ([onTilt]) and is saved when the
 * slider is let go ([onTiltRelease]).
 */
@Composable
internal fun CameraArrangeControls(
    orientation: CameraOrientation,
    onTurn: (clockwise: Boolean) -> Unit,
    onTilt: (degrees: Float) -> Unit,
    onTiltRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tiltLabel = stringResource(Res.string.camera_tilt)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(onClick = { onTurn(false) }) {
            Icon(Icons.Filled.RotateLeft, contentDescription = stringResource(Res.string.camera_turn_left), tint = Color.White)
        }
        Slider(
            value = orientation.tiltDegrees,
            onValueChange = { onTilt(it.roundToInt().toFloat()) },
            onValueChangeFinished = onTiltRelease,
            valueRange = -CameraOrientation.MAX_TILT_DEGREES..CameraOrientation.MAX_TILT_DEGREES,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White.copy(alpha = 0.8f),
                inactiveTrackColor = Color.White.copy(alpha = 0.3f),
            ),
            modifier = Modifier.weight(1f).semantics { contentDescription = tiltLabel },
        )
        Text(
            text = stringResource(Res.string.camera_tilt_value, orientation.tiltDegrees.roundToInt()),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(min = 36.dp),
        )
        IconButton(onClick = { onTurn(true) }) {
            Icon(Icons.Filled.RotateRight, contentDescription = stringResource(Res.string.camera_turn_right), tint = Color.White)
        }
    }
}
