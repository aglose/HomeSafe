package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meticulouscreations.homesafe.domain.model.AlertSettings
import com.meticulouscreations.homesafe.domain.model.AlertZone
import com.meticulouscreations.homesafe.domain.model.CameraPipeline
import com.meticulouscreations.homesafe.domain.model.ConnectionRoute
import com.meticulouscreations.homesafe.domain.model.HouseholdPresence
import com.meticulouscreations.homesafe.domain.model.MomentCategory
import com.meticulouscreations.homesafe.domain.model.PresenceDevice
import com.meticulouscreations.homesafe.domain.model.ServerOverview
import com.meticulouscreations.homesafe.domain.model.formatMegabytes
import com.meticulouscreations.homesafe.domain.model.formatPercent
import com.meticulouscreations.homesafe.domain.model.formatRetentionDays
import com.meticulouscreations.homesafe.domain.model.formatUptime
import com.meticulouscreations.homesafe.domain.platform.NotificationPermission
import com.meticulouscreations.homesafe.ui.components.PulsingDot
import com.meticulouscreations.homesafe.ui.formatClockTime
import com.meticulouscreations.homesafe.viewmodel.SettingsUiState
import com.meticulouscreations.homesafe.viewmodel.SettingsViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlin.math.roundToInt

/**
 * The "Settings" tab: what the connected Frigate server is and is doing (live, from its stats
 * and config), the per-camera detection switches, and this device's alert preferences.
 */
@Composable
fun SettingsTabContent(onOpenClassifier: (String) -> Unit = {}, onOpenFaces: () -> Unit = {}) {
    val viewModel: SettingsViewModel = metroViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Coming back from the OS notification settings screen must be reflected without a relaunch,
    // and a classifier added on the server since the last visit should appear too.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshNotificationPermission()
        viewModel.refreshClassifiers()
        viewModel.refreshPresence()
        onPauseOrDispose { }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = TAB_CONTENT_HORIZONTAL_PADDING)
            // Applied after verticalScroll, so this is content padding: the page scrolls under
            // the shell's floating top bar and the bottom nav rather than stopping short of them.
            .padding(top = shellTopBarClearance() + 8.dp, bottom = bottomNavClearance()),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ServerSection(state, onRetry = viewModel::retryOverview)
        StorageSection(state.overview)
        DetectionSection(
            state = state,
            onDetection = viewModel::setCameraDetection,
            onMotion = viewModel::setCameraMotion,
            onDismissError = viewModel::dismissCameraError,
        )
        AlertsSection(
            state = state,
            onPushNotifications = viewModel::setPushNotifications,
            onZoneCategory = viewModel::setZoneCategory,
            onQuietFamiliar = viewModel::setQuietFamiliarPeople,
            onOpenSettings = viewModel::openNotificationSettings,
            onSendTest = viewModel::sendTestNotification,
        )
        AwaySection(state = state, onAway = viewModel::setAway)
        RecognitionSection(
            models = state.classifiers,
            faceRecognitionEnabled = state.overview?.faceRecognitionEnabled,
            onOpen = onOpenClassifier,
            onOpenFaces = onOpenFaces,
        )
    }
}

@Composable
private fun ServerSection(state: SettingsUiState, onRetry: () -> Unit) {
    SettingsSection(title = "Server", icon = Icons.Filled.Dns) {
        state.connection?.let { connection ->
            SettingsCaption("Connected to ${connection.activeUrl}")
            SettingsCaption(
                when (connection.route) {
                    ConnectionRoute.LOCAL_NETWORK -> "Local network — direct over Wi-Fi, no VPN hop"

                    ConnectionRoute.TAILSCALE ->
                        if (connection.localUrl == null) "Tailscale" else "Tailscale — the local address isn't reachable from here"
                },
            )
        }
        val overview = state.overview
        when {
            overview == null && state.overviewError != null -> LoadFailedRow(state.overviewError, onRetry)

            overview == null -> LoadingRow("Reading server stats…")

            else -> {
                SettingsInfoGrid(
                    listOf(
                        InfoItem("Version", overview.version.ifBlank { "—" }, note = overview.latestVersion?.takeIf { overview.updateAvailable }?.let { "Update available: $it" }),
                        InfoItem("Uptime", formatUptime(overview.uptimeSeconds)),
                        InfoItem("CPU", formatPercent(overview.cpuPercent)),
                        InfoItem("Memory", formatPercent(overview.memoryPercent)),
                    ),
                )
                overview.detector?.let { detector ->
                    val model = listOfNotNull(
                        detector.modelType,
                        detector.inputWidth?.let { w -> detector.inputHeight?.let { h -> "$w×$h" } },
                    ).joinToString(" ")
                    InfoRow(
                        label = "Detector",
                        value = listOfNotNull(detector.type, model.takeIf { it.isNotBlank() }).joinToString(" · "),
                        note = detector.inferenceMs?.let { "Inference ${formatMillis(it)} per frame" } ?: "No inference yet",
                    )
                }
                overview.gpus.forEach { gpu ->
                    InfoRow(
                        label = "GPU",
                        value = gpu.name,
                        note = listOfNotNull(
                            gpu.gpuPercent?.let { "${formatPercent(it)} busy" },
                            gpu.memoryPercent?.let { "${formatPercent(it)} memory" },
                            gpu.decoderPercent?.let { "${formatPercent(it)} decoder" },
                        ).joinToString(" · ").ifBlank { null },
                    )
                }
                if (state.overviewError != null) {
                    SettingsCaption("Last refresh failed: ${state.overviewError}", error = true)
                }
            }
        }
    }
}

@Composable
private fun StorageSection(overview: ServerOverview?) {
    SettingsSection(title = "Storage & Retention", icon = Icons.Filled.Storage) {
        if (overview == null) {
            LoadingRow("Reading disk usage…")
            return@SettingsSection
        }
        val storage = overview.recordingsStorage
        if (storage == null) {
            SettingsCaption("The server didn't report its recordings disk.")
        } else {
            StorageUsageBar(
                label = "Recordings (${storage.path})",
                usedFraction = storage.usedFraction,
                usedText = "${formatMegabytes(storage.usedMb)} used",
                totalText = "${formatMegabytes(storage.totalMb)} total",
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
        val retention = overview.retention
        SettingsInfoGrid(
            listOf(
                InfoItem("Continuous", formatRetentionDays(retention.continuousDays)),
                InfoItem("Motion", formatRetentionDays(retention.motionDays)),
                InfoItem("Alerts", retention.alertDays?.let(::formatRetentionDays) ?: "Default"),
                InfoItem("Detections", retention.detectionDays?.let(::formatRetentionDays) ?: "Default"),
            ),
        )
        SettingsCaption("Frigate deletes recordings older than these on its own. Retention is set in its config.yml.")
    }
}

@Composable
private fun DetectionSection(
    state: SettingsUiState,
    onDetection: (String, Boolean) -> Unit,
    onMotion: (String, Boolean) -> Unit,
    onDismissError: () -> Unit,
) {
    SettingsSection(title = "Detection Pipeline", icon = Icons.Filled.PersonSearch) {
        val overview = state.overview
        if (overview == null) {
            LoadingRow("Reading camera pipelines…")
            return@SettingsSection
        }
        if (!overview.canEditConfig) {
            SettingsCaption("Signed in as a viewer: the switches below are read-only. An admin account can change them.")
        }
        overview.cameras.forEachIndexed { index, camera ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
            CameraPipelineRows(
                camera = camera,
                editable = overview.canEditConfig,
                busy = camera.name in state.busyCameras,
                onDetection = { onDetection(camera.name, it) },
                onMotion = { onMotion(camera.name, it) },
            )
        }
        state.cameraError?.let { error ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                SettingsCaption(error, error = true, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismissError) { Text("Dismiss") }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
        InfoRow(label = "Face recognition", value = onOff(overview.faceRecognitionEnabled))
        InfoRow(label = "License plate recognition", value = onOff(overview.licensePlateRecognitionEnabled))
        InfoRow(label = "Semantic search", value = onOff(overview.semanticSearchEnabled))
        SettingsCaption("These AI features are set in Frigate's config.yml and need a Frigate restart to change.")
    }
}

/** A camera's name and live throughput, then its two switches. Motion can't go off while detection needs it — Frigate's own rule. */
@Composable
private fun CameraPipelineRows(
    camera: CameraPipeline,
    editable: Boolean,
    busy: Boolean,
    onDetection: (Boolean) -> Unit,
    onMotion: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = camera.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Text(
                    text = cameraThroughput(camera),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SettingsToggleRow(
            title = "Object detection",
            description = if (camera.detectionEnabled) "Looking for people, vehicles, and animals." else "Off — no detections or alerts from this camera.",
            checked = camera.detectionEnabled,
            enabled = editable && !busy && camera.enabled,
            onCheckedChange = onDetection,
        )
        SettingsToggleRow(
            title = "Motion detection",
            description = when {
                camera.detectionEnabled -> "Needed while object detection is on."
                camera.motionEnabled -> "Records motion segments and feeds the detector."
                else -> "Off — only continuous recording."
            },
            checked = camera.motionEnabled,
            enabled = editable && !busy && camera.enabled && !camera.detectionEnabled,
            onCheckedChange = onMotion,
        )
    }
}

@Composable
private fun AlertsSection(
    state: SettingsUiState,
    onPushNotifications: (Boolean) -> Unit,
    onZoneCategory: (AlertZone, MomentCategory, Boolean) -> Unit,
    onQuietFamiliar: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onSendTest: () -> Unit,
) {
    SettingsSection(title = "Alerts", icon = Icons.Filled.Notifications) {
        if (!state.notificationsSupported) {
            SettingsToggleRow(
                title = "Notifications",
                description = "Not available on this platform. Use the Android or iOS app for alerts.",
                checked = false,
                enabled = false,
                onCheckedChange = {},
            )
            return@SettingsSection
        }
        val blocked = state.notificationPermission == NotificationPermission.DENIED
        SettingsToggleRow(
            title = "Notifications",
            description = when {
                blocked -> "Blocked in system settings. Allow notifications for HomeSafe to turn this on."
                state.pushNotificationsActive -> "On — a notification for each new detection while HomeSafe is running."
                else -> "Get a notification when a camera sees something."
            },
            checked = state.pushNotificationsActive,
            enabled = !blocked,
            onCheckedChange = onPushNotifications,
        )
        if (blocked) {
            OutlinedButton(onClick = onOpenSettings) { Text("Open notification settings") }
        }
        if (state.pushNotificationsActive) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
            SettingsCaption("Choose what to hear about, place by place. Zones come from each camera's detection zones.")
            val cameras = state.overview?.cameras?.filter { it.enabled }
            when {
                cameras == null -> SettingsCaption("Loading cameras…")

                cameras.isEmpty() -> SettingsCaption("No cameras on this server.")

                else -> cameras.forEach { camera ->
                    CameraAlertZones(camera = camera, alerts = state.alerts, onZoneCategory = onZoneCategory)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
            val faces = state.overview?.faceRecognitionEnabled
            SettingsToggleRow(
                title = "Only strangers",
                description = when (faces) {
                    true -> if (state.alerts.quietFamiliarPeople) {
                        "On — people Frigate recognises come and go quietly. Anyone it can't place still notifies."
                    } else {
                        "Skip the notification when Frigate recognises the person. Name faces under Recognition below."
                    }

                    false -> "Needs face recognition, which is off in Frigate's config."

                    null -> "Needs face recognition on the server."
                },
                checked = faces == true && state.alerts.quietFamiliarPeople,
                enabled = faces == true,
                onCheckedChange = onQuietFamiliar,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onSendTest) { Text("Send test notification") }
                if (state.testNotificationSent) SettingsCaption("Sent")
            }
            SettingsCaption(
                "HomeSafe checks Frigate for new detections every 15 seconds while it's open or recently in the background. " +
                    "Frigate has no push service for phones, so nothing arrives once the system stops the app.",
            )
        }
    }
}

/**
 * Away mode: this phone's "I'm away" switch, the household's phones, and what happens once the
 * last one leaves. The relay on the Frigate box keeps the answer, so both phones see the same thing.
 */
@Composable
private fun AwaySection(state: SettingsUiState, onAway: (Boolean) -> Unit) {
    SettingsSection(title = "Away mode", icon = Icons.Filled.Home) {
        val relayUnreachable = state.presence == HouseholdPresence.EMPTY && state.awayError != null
        val switchEnabled = state.awaySupported && !state.awayBusy && !relayUnreachable
        // A debug install may still flip its own switch — the relay simply doesn't count it.
        val thisDeviceCounts = state.presence.thisDevice?.countsForAway != false
        SettingsToggleRow(
            title = "I'm away",
            description = when {
                !state.awaySupported -> "Not available on this platform yet. Use the Android app to set presence."
                relayUnreachable -> "The push relay on the Frigate box can't be reached, so presence can't be changed right now."
                !thisDeviceCounts -> "This is a debug build, so its switch doesn't decide whether the house is empty."
                state.thisDeviceAway -> "This phone counts as out of the house."
                else -> "This phone counts as home."
            },
            checked = state.thisDeviceAway,
            enabled = switchEnabled,
            onCheckedChange = onAway,
        )
        if (state.presence.devices.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
            state.presence.devices.forEach { device -> PresenceDeviceRow(device) }
            if (state.presence.everyoneAway) {
                SettingsCaption("Nobody home — alerts are escalated on both phones.", error = true)
            }
        }
        state.awayError?.let { SettingsCaption(it, error = true) }
        SettingsCaption(
            "When everyone is away, every person seen on any camera notifies loudly on both phones — " +
                "on its own \"Away alerts\" channel, ignoring the zone rules above. Flip the switch back when you're home.",
        )
    }
}

/**
 * "Google Pixel 10 Pro XL · away since 4:12 PM" / "· home" — one line per phone the relay knows.
 * A debug install is greyed out and says so: it hears the alerts but doesn't get a vote.
 */
@Composable
private fun PresenceDeviceRow(device: PresenceDevice) {
    val name = device.name.ifBlank { device.platform.replaceFirstChar { it.uppercase() }.ifBlank { "Unnamed device" } }
    val status = when {
        device.away && device.updatedEpochSeconds != null -> "away since ${formatClockTime(device.updatedEpochSeconds)}"
        device.away -> "away"
        else -> "home"
    }
    val suffix = (if (device.isThisDevice) " · this phone" else "") + (if (device.countsForAway) "" else " · debug, not counted")
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PulsingDot(
            color = when {
                !device.countsForAway -> MaterialTheme.colorScheme.outline
                device.away -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.secondary
            },
            size = 6.dp,
            pulsing = device.away && device.countsForAway,
        )
        Text(
            text = "$name · $status$suffix",
            style = MaterialTheme.typography.bodyMedium,
            color = if (device.countsForAway) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---- Building blocks ---------------------------------------------------------------------------

private data class InfoItem(val label: String, val value: String, val note: String? = null)

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
private fun SettingsCaption(text: String, error: Boolean = false, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
private fun LoadingRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        SettingsCaption(text)
    }
}

@Composable
private fun LoadFailedRow(message: String, onRetry: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SettingsCaption("Couldn't reach the server: $message", error = true, modifier = Modifier.weight(1f))
        TextButton(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun SettingsInfoGrid(items: List<InfoItem>) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        items.chunked(2).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                rowItems.forEach { item ->
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = item.label.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = item.value,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        item.note?.let { Text(text = it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary) }
                    }
                }
                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** A full-width label/value pair, for values too long for the two-column grid. */
@Composable
private fun InfoRow(label: String, value: String, note: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        note?.let { Text(text = it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(end = 12.dp),
            )
            Text(
                text = "${(usedFraction * 100).roundToInt()}% used",
                style = MaterialTheme.typography.labelMedium,
                color = if (usedFraction >= 0.9f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
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
                    .background(if (usedFraction >= 0.9f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primaryContainer),
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SettingsCaption(usedText)
            SettingsCaption(totalText)
        }
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val textAlpha = if (enabled) 1f else 0.6f
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = textAlpha))
            Text(text = description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = textAlpha))
        }
        // Material's disabled-checked thumb is the surface colour, which on this dark theme makes a
        // locked "on" switch read as "off". A locked switch still has to show its state, so the
        // disabled colours are the enabled ones, dimmed.
        val colors = MaterialTheme.colorScheme
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.onPrimaryContainer,
                checkedTrackColor = colors.primaryContainer,
                disabledCheckedThumbColor = colors.onPrimaryContainer.copy(alpha = 0.5f),
                disabledCheckedTrackColor = colors.primaryContainer.copy(alpha = 0.4f),
                disabledUncheckedThumbColor = colors.onSurfaceVariant.copy(alpha = 0.4f),
                disabledUncheckedTrackColor = colors.surfaceContainerHighest.copy(alpha = 0.4f),
                disabledUncheckedBorderColor = colors.outline.copy(alpha = 0.2f),
            ),
        )
    }
}

private fun onOff(enabled: Boolean) = if (enabled) "On" else "Off"

/** "5.7 detections/s · 5 fps" — what the pipeline is doing right now; "Disabled" for a camera turned off in config. */
private fun cameraThroughput(camera: CameraPipeline): String {
    if (!camera.enabled) return "Disabled"
    val parts = listOfNotNull(
        camera.detectionFps?.takeIf { camera.detectionEnabled }?.let { "${it.format1()} detections/s" },
        camera.cameraFps?.let { "${it.roundToInt()} fps" },
        camera.skippedFps?.takeIf { it > 0 }?.let { "${it.format1()} skipped" },
    )
    return parts.joinToString(" · ").ifBlank { "No stats yet" }
}

/** "6.8 ms" */
private fun formatMillis(ms: Double): String = "${ms.format1()} ms"

private fun Double.format1(): String {
    val scaled = (this * 10).roundToInt()
    return if (scaled % 10 == 0) "${scaled / 10}" else "${scaled / 10}.${scaled % 10}"
}

/**
 * One camera's places — each drawn zone, then "anywhere else" — with a chip per category that
 * alerts there. A chip reflects the effective choice, so a zone the user never touched shows
 * the defaults rather than nothing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CameraAlertZones(
    camera: CameraPipeline,
    alerts: AlertSettings,
    onZoneCategory: (AlertZone, MomentCategory, Boolean) -> Unit,
) {
    val places = camera.zones.map { AlertZone(camera.name, it.name) to it.displayName } +
        (AlertZone(camera.name, null) to if (camera.zones.isEmpty()) "Anywhere" else "Anywhere else")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = camera.displayName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        places.forEach { (place, label) ->
            val chosen = alerts.categoriesFor(place)
            Column(modifier = Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ALERT_CATEGORIES.forEach { (category, name) ->
                        val selected = category in chosen
                        FilterChip(
                            selected = selected,
                            onClick = { onZoneCategory(place, category, !selected) },
                            label = { Text(name) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                        )
                    }
                }
            }
        }
    }
}

private val ALERT_CATEGORIES = listOf(
    MomentCategory.PEOPLE to "People",
    MomentCategory.VEHICLES to "Vehicles",
    MomentCategory.ANIMALS to "Animals",
)
