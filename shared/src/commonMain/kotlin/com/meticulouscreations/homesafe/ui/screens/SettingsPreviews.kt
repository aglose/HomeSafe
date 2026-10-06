package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.meticulouscreations.homesafe.domain.model.ActiveConnection
import com.meticulouscreations.homesafe.domain.model.AlertSettings
import com.meticulouscreations.homesafe.domain.model.CameraPipeline
import com.meticulouscreations.homesafe.domain.model.CameraZone
import com.meticulouscreations.homesafe.domain.model.ClassifierModel
import com.meticulouscreations.homesafe.domain.model.ConnectionRoute
import com.meticulouscreations.homesafe.domain.model.HouseholdPresence
import com.meticulouscreations.homesafe.domain.model.PresenceDevice
import com.meticulouscreations.homesafe.domain.model.QuietHours
import com.meticulouscreations.homesafe.domain.model.RetentionPolicy
import com.meticulouscreations.homesafe.domain.model.ServerOverview
import com.meticulouscreations.homesafe.domain.model.StorageUsage
import com.meticulouscreations.homesafe.domain.platform.NotificationPermission
import com.meticulouscreations.homesafe.ui.preview.FrigatePreview
import com.meticulouscreations.homesafe.viewmodel.SettingsUiState
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.alerts_title
import homesafe.shared.generated.resources.presence_title
import homesafe.shared.generated.resources.settings_alerts_subtitle
import homesafe.shared.generated.resources.settings_away_subtitle
import org.jetbrains.compose.resources.stringResource

/*
 * The Settings tab and the two pages its first rows open, drawn from one fixture household. Only
 * the JVM renderer and Studio draw these, so they stay private (see HomePreviews.kt for the ones
 * Layoutlib draws too).
 */

private fun previewCamera(name: String, detecting: Boolean = true, zones: List<String> = emptyList()) = CameraPipeline(
    name = name,
    enabled = true,
    detectionEnabled = detecting,
    motionEnabled = true,
    cameraFps = 5.0,
    detectionFps = if (detecting) 5.7 else 0.0,
    skippedFps = 0.0,
    zones = zones.map { CameraZone(it) },
)

private val previewOverview = ServerOverview(
    version = "0.17.2",
    latestVersion = "0.17.2",
    uptimeSeconds = 273_600,
    cpuPercent = 9.0,
    memoryPercent = 38.0,
    recordingsStorage = StorageUsage("/media/frigate/recordings", usedMb = 550_000.0, totalMb = 1_000_000.0),
    detector = null,
    gpus = emptyList(),
    retention = RetentionPolicy(7.0, 14.0, 30.0, 30.0),
    faceRecognitionEnabled = true,
    licensePlateRecognitionEnabled = false,
    semanticSearchEnabled = true,
    cameras = listOf(
        previewCamera("front_door", zones = listOf("porch")),
        previewCamera("driveway", zones = listOf("driveway", "street")),
        previewCamera("back_yard", detecting = false),
    ),
    canEditConfig = true,
)

private val previewSettings = SettingsUiState(
    connection = ActiveConnection(serverUrl = "https://frigate.example.ts.net", localUrl = null, route = ConnectionRoute.TAILSCALE),
    overview = previewOverview,
    alerts = AlertSettings(pushNotificationsEnabled = true, quietHours = QuietHours(enabled = true)),
    notificationsSupported = true,
    notificationPermission = NotificationPermission.GRANTED,
    classifiers = listOf(ClassifierModel(name = "known_cars", objects = listOf("car"))),
    presence = HouseholdPresence(
        devices = listOf(
            PresenceDevice(name = "Google Pixel 10 Pro XL", platform = "android", away = false, isThisDevice = true, id = "pixel", build = "release"),
            PresenceDevice(name = "Apple iPhone", platform = "ios", away = true, updatedEpochSeconds = 1_732_650_000.0, id = "iphone", build = "release"),
        ),
        everyoneAway = false,
    ),
)

@Composable
private fun SettingsHomePreviewContent(state: SettingsUiState, initialFold: SettingsFold? = null) {
    FrigatePreview {
        SettingsHome(
            state = state,
            onOpenAlerts = {},
            onOpenAway = {},
            onOpenClassifier = {},
            onOpenFaces = {},
            onOpenServer = {},
            onDetection = { _, _ -> },
            onMotion = { _, _ -> },
            onDismissCameraError = {},
            onTone = {},
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = TAB_CONTENT_HORIZONTAL_PADDING, vertical = 24.dp),
            initialFold = initialFold,
        )
    }
}

@Preview(name = "Settings", widthDp = 412, heightDp = 915)
@Composable
private fun SettingsHomePreview() {
    SettingsHomePreviewContent(previewSettings)
}

/** Before the server, the relay or the OS has answered anything: every row still has a line to say. */
@Preview(name = "Settings, loading", widthDp = 412, heightDp = 915)
@Composable
private fun SettingsHomeLoadingPreview() {
    SettingsHomePreviewContent(SettingsUiState(notificationsSupported = true))
}

@Preview(name = "Settings, cameras open", widthDp = 412, heightDp = 915)
@Composable
private fun SettingsHomeCamerasPreview() {
    SettingsHomePreviewContent(previewSettings, initialFold = SettingsFold.CAMERAS)
}

@Preview(name = "Settings, economy commentary open", widthDp = 412, heightDp = 915)
@Composable
private fun SettingsHomeEconomyPreview() {
    SettingsHomePreviewContent(previewSettings, initialFold = SettingsFold.ECONOMY)
}

@Preview(name = "Settings · Alerts page", widthDp = 412, heightDp = 915)
@Composable
private fun AlertsPagePreview() {
    FrigatePreview {
        SettingsPage(title = stringResource(Res.string.alerts_title), subtitle = stringResource(Res.string.settings_alerts_subtitle), onBack = {}) {
            AlertsSection(
                state = previewSettings,
                onPushNotifications = {},
                onZoneCategory = { _, _, _ -> },
                onPreset = {},
                onQuietHours = {},
                onOnlyWhenAway = {},
                onQuietFamiliar = {},
                onLoadVolume = {},
                onOpenSettings = {},
                onSendTest = {},
            )
        }
    }
}

@Preview(name = "Settings · Away mode page", widthDp = 412, heightDp = 915)
@Composable
private fun AwayPagePreview() {
    FrigatePreview {
        SettingsPage(title = stringResource(Res.string.presence_title), subtitle = stringResource(Res.string.settings_away_subtitle), onBack = {}) {
            AwaySection(
                state = previewSettings,
                onAway = {},
                onDecides = {},
                onAutomatic = {},
                onRequestLocation = {},
                onSetHomeHere = {},
                onClearHome = {},
                onRemoveDevice = {},
            )
        }
    }
}
