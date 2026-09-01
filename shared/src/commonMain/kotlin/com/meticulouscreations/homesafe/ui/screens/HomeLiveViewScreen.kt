package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Nature
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.meticulouscreations.homesafe.ui.components.PulsingDot
import com.meticulouscreations.homesafe.ui.theme.LocalFrigateExtraColors
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.camera_backyard
import homesafe.shared.generated.resources.camera_front_door
import org.jetbrains.compose.resources.painterResource

/** The "Home" tab's content: greeting, status, and the live camera feed cards. */
@Composable
fun HomeTabContent() {
    val extraColors = LocalFrigateExtraColors.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 8.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Good Evening",
                style = MaterialTheme.typography.displayLarge,
                color = extraColors.textPrimary,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PulsingDot(color = MaterialTheme.colorScheme.secondary)
                Text(
                    text = "System Secure",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            CameraCard(
                name = "Front Door",
                icon = Icons.Filled.Videocam,
                image = painterResource(Res.drawable.camera_front_door),
                footerText = "1080p HD • 24 FPS",
            )
            CameraCard(
                name = "Backyard",
                icon = Icons.Filled.Nature,
                image = painterResource(Res.drawable.camera_backyard),
                grayscale = true,
                nightVision = true,
            )
        }
    }
}

@Composable
private fun CameraCard(
    name: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    image: Painter,
    modifier: Modifier = Modifier,
    grayscale: Boolean = false,
    footerText: String? = null,
    nightVision: Boolean = false,
) {
    val extraColors = LocalFrigateExtraColors.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f), RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Image(
            painter = image,
            contentDescription = "$name camera feed",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            colorFilter = if (grayscale) ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) else null,
        )

        // Top gradient + title + LIVE badge
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)))
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = extraColors.textPrimary.copy(alpha = 0.8f),
                    modifier = Modifier.size(20.dp),
                )
                Text(text = name, style = MaterialTheme.typography.headlineSmall, color = extraColors.textPrimary)
            }
            LiveBadge(textColor = extraColors.textPrimary, pillColor = extraColors.glassFill)
        }

        if (footerText != null) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(extraColors.glassFill, CircleShape)
                        .border(1.dp, Color.White.copy(alpha = 0.1f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Mic, contentDescription = "Talk", tint = extraColors.textPrimary)
                }
                Text(
                    text = footerText,
                    style = MaterialTheme.typography.labelSmall,
                    color = extraColors.textPrimary.copy(alpha = 0.7f),
                )
            }
        }

        if (nightVision) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    Icons.Filled.Visibility,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "Night Vision",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}

@Composable
private fun LiveBadge(textColor: Color, pillColor: Color) {
    Row(
        modifier = Modifier
            .background(pillColor, CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.1f), CircleShape)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PulsingDot(color = MaterialTheme.colorScheme.error, size = 6.dp)
        Text(
            text = "LIVE",
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.1.em),
            color = textColor,
        )
    }
}
