package com.meticulouscreations.homesafe.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.entryProvider
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.meticulouscreations.homesafe.domain.model.ActiveConnection
import com.meticulouscreations.homesafe.domain.model.AlertSettings
import com.meticulouscreations.homesafe.domain.model.CameraPipeline
import com.meticulouscreations.homesafe.domain.model.CameraZone
import com.meticulouscreations.homesafe.domain.model.ClassifierModel
import com.meticulouscreations.homesafe.domain.model.ConnectionRoute
import com.meticulouscreations.homesafe.domain.model.HomeLocation
import com.meticulouscreations.homesafe.domain.model.HouseholdPresence
import com.meticulouscreations.homesafe.domain.model.PresenceDevice
import com.meticulouscreations.homesafe.domain.model.QuietHours
import com.meticulouscreations.homesafe.domain.model.RetentionPolicy
import com.meticulouscreations.homesafe.domain.model.ServerOverview
import com.meticulouscreations.homesafe.domain.model.StorageUsage
import com.meticulouscreations.homesafe.domain.platform.LocationAccess
import com.meticulouscreations.homesafe.domain.platform.NotificationPermission
import com.meticulouscreations.homesafe.finance.domain.BudgetAlertSwitches
import com.meticulouscreations.homesafe.maps.GoogleMapsStatus
import com.meticulouscreations.homesafe.maps.MapProvider
import com.meticulouscreations.homesafe.maps.MapSettings
import com.meticulouscreations.homesafe.text.UiText
import com.meticulouscreations.homesafe.text.asUiText
import com.meticulouscreations.homesafe.ui.preview.FrigatePreview
import com.meticulouscreations.homesafe.vault.VaultState
import com.meticulouscreations.homesafe.vault.note
import com.meticulouscreations.homesafe.viewmodel.BudgetAlertsState
import com.meticulouscreations.homesafe.viewmodel.SettingsUiState
import com.meticulouscreations.homesafe.weather.domain.WeatherNoticeSettings
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.alerts_finance_error
import homesafe.shared.generated.resources.alerts_title
import homesafe.shared.generated.resources.maps_google_rejected
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
    appIconsSupported = true,
    weatherNotices = WeatherNoticeSettings(dailyOutlook = false),
    budgetAlerts = BudgetAlertsState.Ready(BudgetAlertSwitches(total = true, buckets = true)),
    presence = HouseholdPresence(
        devices = listOf(
            PresenceDevice(name = "Google Pixel 10 Pro XL", platform = "android", away = false, isThisDevice = true, id = "pixel"),
            PresenceDevice(name = "Apple iPhone", platform = "ios", away = true, updatedEpochSeconds = 1_732_650_000.0, id = "iphone"),
        ),
        everyoneAway = false,
    ),
)

@Composable
private fun SettingsHomePreviewContent(state: SettingsUiState, initialFold: SettingsFold? = null) {
    FrigatePreview {
        SettingsHome(
            state = state,
            onOpenNotifications = {},
            onOpenAway = {},
            onOpenClassifier = {},
            onOpenFaces = {},
            onOpenServer = {},
            onDetection = { _, _ -> },
            onMotion = { _, _ -> },
            onDismissCameraError = {},
            onTone = {},
            onFinanceDrawerItems = {},
            onFigureStyle = {},
            onAppIcon = {},
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = TAB_CONTENT_HORIZONTAL_PADDING, vertical = 24.dp),
            initialFold = initialFold,
        )
    }
}

@Preview(name = "Settings", widthDp = 412, heightDp = 915)
@Preview(name = "Settings, in Spanish", widthDp = 412, heightDp = 915, locale = "es")
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

@Preview(name = "Settings, exercise figures open", widthDp = 412, heightDp = 915)
@Composable
private fun SettingsHomeFiguresPreview() {
    SettingsHomePreviewContent(previewSettings, initialFold = SettingsFold.FIGURES)
}

@Preview(name = "Settings, maps open on Google, key needed", widthDp = 412, heightDp = 1400)
@Composable
private fun SettingsHomeMapsNoKeyPreview() {
    SettingsHomePreviewContent(previewSettings.copy(maps = MapSettings(provider = MapProvider.GOOGLE)), initialFold = SettingsFold.MAPS)
}

@Preview(name = "Settings, maps open on Google, key refused", widthDp = 412, heightDp = 1400)
@Composable
private fun SettingsHomeMapsRefusedPreview() {
    val refused = GoogleMapsStatus.Failed(UiText.of(Res.string.maps_google_rejected, 403))
    SettingsHomePreviewContent(previewSettings.copy(maps = MapSettings(MapProvider.GOOGLE, hasGoogleKey = true, google = refused)), initialFold = SettingsFold.MAPS)
}

@Preview(name = "Settings, maps open on Google, waiting to be let in", widthDp = 412, heightDp = 1400)
@Composable
private fun SettingsHomeMapsWaitingPreview() {
    SettingsHomePreviewContent(previewSettings.copy(maps = MapSettings(MapProvider.GOOGLE, keysNote = VaultState.Waiting.note())), initialFold = SettingsFold.MAPS)
}

@Preview(name = "Settings, app icon open", widthDp = 412, heightDp = 915)
@Composable
private fun SettingsHomeAppIconPreview() {
    SettingsHomePreviewContent(previewSettings, initialFold = SettingsFold.APP_ICON)
}

@Preview(name = "Settings · Notifications page", widthDp = 412, heightDp = 915)
@Preview(name = "Settings · Notifications page, in Spanish", widthDp = 412, heightDp = 915, locale = "es")
@Composable
private fun NotificationsPagePreview() {
    FrigatePreview { NotificationsPage() }
}

/** All of it, every app's card down to the budget's. */
@Preview(name = "Settings · Notifications page, whole", widthDp = 412, heightDp = 3000)
@Composable
private fun NotificationsPageWholePreview() {
    FrigatePreview { NotificationsPage() }
}

/** Everything muted, the cameras' notifications off, the weather app's switched off too, and the relay not answering about the budget. */
@Preview(name = "Settings · Notifications page, apps off", widthDp = 412, heightDp = 1500)
@Composable
private fun NotificationsPageAppsOffPreview() {
    FrigatePreview {
        NotificationsPage(
            previewSettings.copy(
                notificationsMuted = true,
                alerts = AlertSettings.DEFAULT,
                weatherNotices = WeatherNoticeSettings(enabled = false),
                budgetAlerts = BudgetAlertsState.Failed(UiText.of(Res.string.alerts_finance_error, "HTTP 502".asUiText())),
            ),
        )
    }
}

@Composable
private fun NotificationsPage(state: SettingsUiState = previewSettings) {
    SettingsPage(title = stringResource(Res.string.alerts_title), subtitle = stringResource(Res.string.settings_alerts_subtitle), onBack = {}) {
        MuteAllCard(muted = state.notificationsMuted, onMute = {})
        AlertsSection(
            state = state,
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
        OtherAppsNotifications(state = state, onOpenDoors = {}, onWeatherNotices = {}, onAllowNotifications = {}, onBudgetAlerts = {}, onRetryBudget = {})
    }
}

/** A tablet on its side: the list down the start side, with Notifications open beside it and its row marked. */
@Preview(name = "Settings, two panes", widthDp = 1194, heightDp = 834)
@Composable
private fun SettingsTwoPanesPreview() {
    SettingsTwoPanesPreviewContent(open = NotificationsRoute)
}

/** The same before a row has been picked. */
@Preview(name = "Settings, two panes, nothing open", widthDp = 834, heightDp = 1194)
@Composable
private fun SettingsTwoPanesEmptyPreview() {
    SettingsTwoPanesPreviewContent(open = null)
}

@Composable
private fun SettingsTwoPanesPreviewContent(open: Any?) {
    // The page side is a NavDisplay, which wants a back dispatcher; a preview has no activity to give it one.
    val back = remember {
        object : NavigationEventDispatcherOwner {
            override val navigationEventDispatcher = NavigationEventDispatcher()
        }
    }
    FrigatePreview {
        CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides back) { SettingsTwoPanesPreviewPanes(open) }
    }
}

@Composable
private fun SettingsTwoPanesPreviewPanes(open: Any?) {
    val backStack = remember { mutableStateListOf<Any>(SettingsHomeRoute).apply { open?.let(::add) } }
    val pages = entryProvider<Any> {
        entry<SettingsHomeRoute> {}
        entry<NotificationsRoute> { NotificationsPage() }
    }
    SettingsTwoPanes(backStack, pages) {
        SettingsHome(
            state = previewSettings,
            onOpenNotifications = {},
            onOpenAway = {},
            onOpenClassifier = {},
            onOpenFaces = {},
            onOpenServer = {},
            onDetection = { _, _ -> },
            onMotion = { _, _ -> },
            onDismissCameraError = {},
            onTone = {},
            onFinanceDrawerItems = {},
            onFigureStyle = {},
            onAppIcon = {},
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = contentGutter(), vertical = 24.dp),
        )
    }
}

@Preview(name = "Settings · Away mode page", widthDp = 412, heightDp = 915)
@Composable
private fun AwayPagePreview() {
    FrigatePreview {
        SettingsPage(title = stringResource(Res.string.presence_title), subtitle = stringResource(Res.string.settings_away_subtitle), onBack = {}) {
            AwaySection(
                // A phone that can watch the fence, with home set and location "always": the usual state.
                state = previewSettings.copy(
                    geofenceSupported = true,
                    locationAccess = LocationAccess.ALWAYS,
                    presence = previewSettings.presence.copy(home = HomeLocation(latitude = 40.0, longitude = -75.0, radiusMeters = 150.0)),
                ),
                onAway = {},
                onRequestLocation = {},
                onSetHomeHere = {},
                onClearHome = {},
                onRemoveDevice = {},
            )
        }
    }
}
