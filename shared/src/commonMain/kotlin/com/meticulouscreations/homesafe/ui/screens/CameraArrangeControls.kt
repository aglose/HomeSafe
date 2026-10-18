package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.camera_frame_button
import homesafe.shared.generated.resources.camera_turn_left
import homesafe.shared.generated.resources.camera_turn_right
import org.jetbrains.compose.resources.stringResource

/**
 * A camera card's controls in Arrange mode, along its bottom edge over the video: a quarter turn
 * either way for a camera mounted on its side or upside down, saved at once ([onTurn]), and
 * between them the way into the framing editor ([onFrame]), where the picture is straightened,
 * zoomed and cropped. The picture above turns as they're pressed, so the right way up is found by eye.
 */
@Composable
internal fun CameraArrangeControls(
    onTurn: (clockwise: Boolean) -> Unit,
    onFrame: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = { onTurn(false) }) {
            Icon(Icons.Filled.RotateLeft, contentDescription = stringResource(Res.string.camera_turn_left), tint = Color.White)
        }
        FilledTonalButton(
            onClick = onFrame,
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color.White.copy(alpha = 0.2f), contentColor = Color.White),
            modifier = Modifier.testTag(CAMERA_FRAME_BUTTON_TEST_TAG),
        ) {
            Icon(Icons.Filled.Crop, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(Res.string.camera_frame_button))
        }
        IconButton(onClick = { onTurn(true) }) {
            Icon(Icons.Filled.RotateRight, contentDescription = stringResource(Res.string.camera_turn_right), tint = Color.White)
        }
    }
}

/** The card's way into the framing editor in Arrange mode. */
internal const val CAMERA_FRAME_BUTTON_TEST_TAG = "camera_frame_button"
