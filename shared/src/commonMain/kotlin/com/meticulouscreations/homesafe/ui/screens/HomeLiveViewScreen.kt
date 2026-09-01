package com.meticulouscreations.homesafe.ui.screens

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
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meticulouscreations.homesafe.network.FrigateCamera
import com.meticulouscreations.homesafe.network.FrigateSessionRepository
import com.meticulouscreations.homesafe.ui.components.PulsingDot
import com.meticulouscreations.homesafe.ui.theme.LocalFrigateExtraColors

/** The "Home" tab's content: greeting, status, and the cameras reported by the connected Frigate server. */
@Composable
fun HomeTabContent(sessionRepository: FrigateSessionRepository) {
    val extraColors = LocalFrigateExtraColors.current
    val session by sessionRepository.session.collectAsStateWithLifecycle()
    val cameras = session?.cameras.orEmpty()

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

        if (cameras.isEmpty()) {
            Text(
                text = "No cameras found on this server.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                cameras.forEach { camera -> CameraCard(camera) }
            }
        }
    }
}

@Composable
private fun CameraCard(camera: FrigateCamera, modifier: Modifier = Modifier) {
    val extraColors = LocalFrigateExtraColors.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f), RoundedCornerShape(20.dp))
            .background(
                Brush.verticalGradient(
                    listOf(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.colorScheme.surfaceContainerHigh),
                ),
            ),
    ) {
        Icon(
            imageVector = Icons.Filled.Videocam,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
            modifier = Modifier.align(Alignment.Center).size(56.dp),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = camera.name,
                style = MaterialTheme.typography.headlineSmall,
                color = extraColors.textPrimary,
            )
            StatusBadge(enabled = camera.enabled, textColor = extraColors.textPrimary, pillColor = extraColors.glassFill)
        }
    }
}

@Composable
private fun StatusBadge(enabled: Boolean, textColor: Color, pillColor: Color) {
    val statusColor = if (enabled) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
    Row(
        modifier = Modifier
            .background(pillColor, CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.1f), CircleShape)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PulsingDot(color = statusColor, size = 6.dp, pulsing = enabled)
        Text(
            text = if (enabled) "Enabled" else "Disabled",
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
        )
    }
}
