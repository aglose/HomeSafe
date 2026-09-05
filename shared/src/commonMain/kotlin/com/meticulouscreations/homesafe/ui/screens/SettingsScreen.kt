package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meticulouscreations.homesafe.domain.model.ConnectionRoute
import com.meticulouscreations.homesafe.domain.model.DetectionSettings
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.domain.usecase.ObserveSettingsUseCase
import com.meticulouscreations.homesafe.domain.usecase.UpdateSettingsUseCase
import com.meticulouscreations.homesafe.viewmodel.SettingsViewModel

/** The "Settings" tab's content: server info, storage, detection pipeline, and alert toggles. */
@Composable
fun SettingsTabContent(
    observeSettingsUseCase: ObserveSettingsUseCase,
    updateSettingsUseCase: UpdateSettingsUseCase,
    connectionRepository: ConnectionRepository,
) {
    val viewModel = viewModel { SettingsViewModel(observeSettingsUseCase, updateSettingsUseCase, connectionRepository) }
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val activeConnection by viewModel.activeConnection.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 8.dp, bottom = bottomNavClearance()),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        SettingsSection(title = "Server Information", icon = Icons.Filled.Dns) {
            activeConnection?.let { connection ->
                Text(
                    text = "Connected to ${connection.activeUrl}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = when (connection.route) {
                        ConnectionRoute.LOCAL_NETWORK -> "Local network — direct over Wi-Fi, no VPN hop"
                        ConnectionRoute.TAILSCALE ->
                            if (connection.localUrl == null) "Tailscale" else "Tailscale — the local address isn't reachable from here"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SettingsInfoGrid(
                listOf(
                    "Version" to "v0.12.1-7b003a3",
                    "Uptime" to "14 days, 3 hours",
                    "CPU Usage" to "12%",
                    "Memory" to "2.4 GB / 8.0 GB",
                ),
            )
        }

        SettingsSection(title = "Storage & Disk", icon = Icons.Filled.Storage) {
            StorageUsageBar(
                label = "Main Recordings (/media/frigate)",
                usedFraction = 0.64f,
                usedText = "1.2 TB Used",
                totalText = "1.8 TB Total",
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
            SettingsToggleRow(
                title = "Auto-Purge Old Media",
                description = "Delete events older than 30 days to free space.",
                checked = settings.autoPurgeOldMedia,
                onCheckedChange = { viewModel.updateSettings(settings.copy(autoPurgeOldMedia = it)) },
            )
        }

        SettingsSection(title = "Detection Pipeline", icon = Icons.Filled.PersonSearch) {
            SettingsToggleRow(
                title = "Global Motion Detection",
                description = "Enable pixel-level motion analysis across all feeds.",
                checked = settings.globalMotionDetection,
                onCheckedChange = { viewModel.updateSettings(settings.copy(globalMotionDetection = it)) },
            )
            SettingsToggleRow(
                title = "Coral Edge TPU Inference",
                description = "Hardware acceleration for object detection.",
                checked = settings.coralEdgeInference,
                onCheckedChange = { viewModel.updateSettings(settings.copy(coralEdgeInference = it)) },
            )
            SettingsToggleRow(
                title = "Face Recognition",
                description = "Attempt to identify known profiles in events.",
                checked = settings.faceRecognition,
                onCheckedChange = { viewModel.updateSettings(settings.copy(faceRecognition = it)) },
            )
        }

        SettingsSection(title = "Alerts", icon = Icons.Filled.Notifications) {
            SettingsToggleRow(
                title = "Push Notifications",
                description = "Receive alerts for critical detection events.",
                checked = settings.pushNotificationsEnabled,
                onCheckedChange = { viewModel.updateSettings(settings.copy(pushNotificationsEnabled = it)) },
            )
        }
    }
}

@Composable
private fun SettingsSection(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Text(text = title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        }
        content()
    }
}

@Composable
private fun SettingsInfoGrid(items: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        items.chunked(2).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                rowItems.forEach { (label, value) ->
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = label.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = value,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StorageUsageBar(label: String, usedFraction: Float, usedText: String, totalText: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                text = "${(usedFraction * 100).toInt()}% Used",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(usedFraction)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primaryContainer),
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = usedText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text = totalText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsToggleRow(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(text = description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimaryContainer,
                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        )
    }
}
